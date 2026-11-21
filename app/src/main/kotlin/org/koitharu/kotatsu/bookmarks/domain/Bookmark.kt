package org.koitharu.kotatsu.bookmarks.domain

import android.graphics.RectF
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.isImage
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaPage
import java.time.Instant
import java.util.Base64
import java.util.Locale

data class Bookmark(
	val manga: Manga,
	val pageId: Long,
	val chapterId: Long,
	val page: Int,
	val scroll: Int,
	val imageUrl: String,
	val createdAt: Instant,
	val percent: Float,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		// page_id is the row's own key: several highlights can sit on one page
		return other is Bookmark &&
			manga.id == other.manga.id &&
			pageId == other.pageId
	}

	fun toMangaPage() = MangaPage(
		id = pageId,
		url = imageUrl,
		preview = imageUrl.takeIf {
			MimeTypes.getMimeTypeFromUrl(it)?.isImage == true
		},
		source = manga.source,
	)
}

data class EpubHighlight(
	val start: Int,
	val end: Int,
	val text: String,
)

val Bookmark.epubHighlight: EpubHighlight?
	get() = decodeEpubHighlight(page, imageUrl)

internal fun decodeEpubHighlight(start: Int, url: String): EpubHighlight? {
	if (!url.startsWith(EPUB_HIGHLIGHT_PREFIX)) return null
	val payload = url.removePrefix(EPUB_HIGHLIGHT_PREFIX)
	val separator = payload.indexOf(':').takeIf { it > 0 } ?: return null
	val end = payload.substring(0, separator).toIntOrNull() ?: return null
	val text = runCatching {
		String(Base64.getUrlDecoder().decode(payload.substring(separator + 1)), Charsets.UTF_8)
	}.getOrNull() ?: return null
	return EpubHighlight(start, end, text).takeIf { it.start in 0 until it.end }
}

fun epubHighlightUrl(end: Int, text: String): String {
	val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(Charsets.UTF_8))
	return "$EPUB_HIGHLIGHT_PREFIX$end:$encoded"
}

private const val EPUB_HIGHLIGHT_PREFIX = "epub-highlight:"

/** Text marked on a page of a PDF; the page index is the bookmark's `page`, [bounds] are in page points. */
data class PdfHighlight(
	val bounds: List<RectF>,
	val text: String,
)

val Bookmark.pdfHighlight: PdfHighlight?
	get() = decodePdfHighlight(imageUrl)

/** The marked text of an EPUB or PDF highlight, null for a page bookmark. */
val Bookmark.highlightText: String?
	get() = epubHighlight?.text ?: pdfHighlight?.text

internal fun decodePdfHighlight(url: String): PdfHighlight? {
	if (!url.startsWith(PDF_HIGHLIGHT_PREFIX)) return null
	val parts = url.removePrefix(PDF_HIGHLIGHT_PREFIX).split(':')
	if (parts.size != 2) return null
	val bounds = parts[0].split(';').mapNotNull { rect ->
		val v = rect.split(',').mapNotNull(String::toFloatOrNull)
		if (v.size == 4) RectF(v[0], v[1], v[2], v[3]) else null
	}
	val text = runCatching {
		String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
	}.getOrNull() ?: return null
	return PdfHighlight(bounds, text)
}

fun pdfHighlightUrl(highlight: PdfHighlight): String {
	val bounds = highlight.bounds.joinToString(";") { r -> listOf(r.left, r.top, r.right, r.bottom).joinToString(",") { "%.1f".format(Locale.ROOT, it) } }
	val text = Base64.getUrlEncoder().withoutPadding().encodeToString(highlight.text.toByteArray(Charsets.UTF_8))
	return "$PDF_HIGHLIGHT_PREFIX$bounds:$text"
}

const val PDF_HIGHLIGHT_PREFIX = "pdf-highlight:"
