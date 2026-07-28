package org.koitharu.kotatsu.lnreader.model

import org.json.JSONObject
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN

/**
 * Maps LNReader's plugin JSON (`NovelItem` / `SourceNovel` / `ChapterItem`) onto Kotatsu models.
 *
 * [stableId] is deliberately a copy of the Mihon one rather than an import: a future change to how
 * Mihon ids are generated must not silently re-key every novel in the library.
 */
private fun stableId(sourceName: String, type: String, value: String): Long =
	"$sourceName|$type|$value".hashCode().toLong() and Long.MAX_VALUE

fun lnMangaId(sourceName: String, url: String): Long = stableId(sourceName, "manga", url)

fun lnChapterId(sourceName: String, url: String): Long = stableId(sourceName, "chapter", url)

/**
 * @param details true when this came from `parseNovel`, so an empty chapter list means "this novel
 *   has none" rather than "a list view did not include them".
 */
fun JSONObject.toManga(
	source: LnMangaSource,
	chapters: List<MangaChapter> = emptyList(),
	details: Boolean = false,
): Manga {
	val path = optString("path")
	val cover = optString("cover").takeIf { it.isNotEmpty() }?.let { source.absoluteUrl(it) }
	val genres = optString("genres")
		.split(',')
		.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
	return Manga(
		id = lnMangaId(source.name, path),
		title = optString("name").ifEmpty { path },
		altTitles = emptySet(),
		url = path,
		publicUrl = source.absoluteUrl(path),
		rating = RATING_UNKNOWN,
		contentRating = null,
		coverUrl = cover,
		largeCoverUrl = cover,
		tags = genres.mapTo(LinkedHashSet()) { genre ->
			MangaTag(key = genre.lowercase(), title = genre, source = source)
		},
		state = when (optString("status")) {
			"Ongoing" -> MangaState.ONGOING
			"Completed", "Publishing Finished" -> MangaState.FINISHED
			"Cancelled" -> MangaState.ABANDONED
			"On Hiatus" -> MangaState.PAUSED
			"Licensed" -> MangaState.RESTRICTED
			else -> null
		},
		authors = setOfNotNull(optString("author").takeIf { it.isNotEmpty() }),
		description = optString("summary").takeIf { it.isNotEmpty() },
		// Kotatsu treats a null chapter list as "details not loaded yet".
		chapters = if (details) chapters else chapters.takeIf { it.isNotEmpty() },
		source = source,
	)
}

/**
 * @param fallbackNumber 1-based position in the chapter list, used when the plugin left
 *   `chapterNumber` unset. LNReader returns chapters oldest-first, which is already the order
 *   Kotatsu expects — do NOT reverse this list the way the Mihon adapter does.
 */
fun JSONObject.toMangaChapter(source: LnMangaSource, fallbackNumber: Int): MangaChapter {
	val path = optString("path")
	return MangaChapter(
		id = lnChapterId(source.name, path),
		title = optString("name").takeIf { it.isNotEmpty() },
		number = optDouble("chapterNumber", Double.NaN)
			.takeIf { !it.isNaN() && it > 0 }
			?.toFloat()
			?: fallbackNumber.toFloat(),
		volume = 0,
		url = path,
		scanlator = null,
		// releaseTime is a free-form plugin string ("3 days ago", "2026-01-02", "Jan 2, 2026"), so
		// there is nothing reliable to parse. 0 means "unknown" to every consumer.
		uploadDate = 0L,
		branch = null,
		source = source,
	)
}

/** Resolves a plugin-relative path against the plugin's own site. */
fun LnMangaSource.absoluteUrl(value: String): String = when {
	value.startsWith("http://") || value.startsWith("https://") -> value
	value.startsWith("//") -> "https:$value"
	else -> plugin.site.trimEnd('/') + "/" + value.trimStart('/')
}
