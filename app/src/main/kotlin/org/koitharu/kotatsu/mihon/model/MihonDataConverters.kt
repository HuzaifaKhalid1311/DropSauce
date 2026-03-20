package org.koitharu.kotatsu.mihon.model

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag

fun SManga.toManga(
	source: MihonMangaSource,
	chapters: List<MangaChapter> = emptyList(),
): Manga {
	val httpSource = source.catalogueSource as? HttpSource
	val resolvedUrl = httpSource?.getMangaUrl(this).orEmpty().ifBlank { resolveUrl(httpSource?.baseUrl, url) ?: url }
	val resolvedCover = resolveUrl(httpSource?.baseUrl, thumbnail_url)
	return Manga(
		id = stableId(source.name, "manga", url),
		title = title,
		altTitles = emptySet(),
		url = url,
		publicUrl = resolvedUrl,
		rating = -1f,
		contentRating = if (source.isNsfw) ContentRating.ADULT else null,
		coverUrl = resolvedCover,
		tags = getGenres().orEmpty().mapTo(LinkedHashSet()) { genre ->
			MangaTag(key = genre.lowercase(), title = genre, source = source)
		},
		state = when (status) {
			SManga.ONGOING -> MangaState.ONGOING
			SManga.COMPLETED, SManga.PUBLISHING_FINISHED -> MangaState.FINISHED
			SManga.CANCELLED -> MangaState.ABANDONED
			SManga.ON_HIATUS -> MangaState.PAUSED
			SManga.LICENSED -> MangaState.RESTRICTED
			else -> null
		},
		authors = buildSet {
			author?.takeIf { it.isNotBlank() }?.let(::add)
			artist?.takeIf { it.isNotBlank() && it != author }?.let(::add)
		},
		largeCoverUrl = resolvedCover,
		description = description,
		chapters = chapters,
		source = source,
	)
}

fun Manga.toSManga(): SManga = SManga.create().apply {
	url = this@toSManga.url
	title = this@toSManga.title
	author = this@toSManga.authors.firstOrNull()
	artist = this@toSManga.authors.drop(1).firstOrNull()
	description = this@toSManga.description
	genre = this@toSManga.tags.joinToString(", ") { it.title }
	status = when (this@toSManga.state) {
		MangaState.ONGOING -> SManga.ONGOING
		MangaState.FINISHED -> SManga.COMPLETED
		MangaState.ABANDONED -> SManga.CANCELLED
		MangaState.PAUSED -> SManga.ON_HIATUS
		MangaState.RESTRICTED -> SManga.LICENSED
		else -> SManga.UNKNOWN
	}
	thumbnail_url = this@toSManga.coverUrl
	initialized = true
}

fun SChapter.toMangaChapter(source: MihonMangaSource): MangaChapter = MangaChapter(
	id = stableId(source.name, "chapter", url),
	title = name.takeIf { it.isNotBlank() },
	number = chapter_number.takeIf { it >= 0f } ?: 0f,
	volume = 0,
	url = url,
	scanlator = scanlator,
	uploadDate = date_upload,
	branch = scanlator,
	source = source,
)

fun MangaChapter.toSChapter(): SChapter = SChapter.create().apply {
	url = this@toSChapter.url
	name = this@toSChapter.title ?: "Chapter ${this@toSChapter.number}"
	chapter_number = this@toSChapter.number
	date_upload = this@toSChapter.uploadDate
	scanlator = this@toSChapter.scanlator
}

fun Page.toMangaPage(source: MihonMangaSource, chapterUrl: String): MangaPage = MangaPage(
	id = stableId(source.name, "page", "$chapterUrl|$index"),
	url = imageUrl ?: url,
	preview = imageUrl,
	source = source,
)

private fun stableId(sourceName: String, type: String, value: String): Long {
	return "$sourceName|$type|$value".hashCode().toLong() and Long.MAX_VALUE
}

private fun resolveUrl(baseUrl: String?, value: String?): String? {
	if (value.isNullOrBlank()) return null
	if (value.startsWith("http://") || value.startsWith("https://")) return value
	if (value.startsWith("//")) return "https:$value"
	return baseUrl?.trimEnd('/')?.plus("/")?.plus(value.trimStart('/')) ?: value
}
