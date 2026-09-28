@file:Suppress("DEPRECATION")

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
import org.koitharu.kotatsu.parsers.model.RATING_UNKNOWN

// ============ SManga <-> Manga ============

fun SManga.toManga(
	source: MihonMangaSource,
	chapters: List<MangaChapter> = emptyList(),
	publicUrl: String = "",
): Manga {
	val httpSource = source.catalogueSource as? HttpSource
	val baseUrl = httpSource?.baseUrl ?: ""

	// Safely access lateinit properties
	val safeUrl = try { url } catch (_: UninitializedPropertyAccessException) { "" }
	val safeThumbnail = try { thumbnail_url } catch (_: UninitializedPropertyAccessException) { null }
	val safeTitle = try { title } catch (_: UninitializedPropertyAccessException) { "Unknown" }
	val safeGenres: List<String>? = getGenres()?.takeIf { it.isNotEmpty() }
	val safeAuthor = try { author } catch (_: UninitializedPropertyAccessException) { null }
	val safeArtist = try { artist } catch (_: UninitializedPropertyAccessException) { null }
	val safeDescription = try { description } catch (_: UninitializedPropertyAccessException) { null }
	val safeStatus = try { status } catch (_: UninitializedPropertyAccessException) { SManga.UNKNOWN }

	val resolvedCover = resolveUrl(baseUrl, safeThumbnail)
	val resolvedUrl = if (publicUrl.isNotBlank()) {
		publicUrl
	} else {
		httpSource?.getMangaUrl(this).orEmpty().ifBlank { resolveUrl(baseUrl, safeUrl) ?: safeUrl }
	}

	// An extension's NSFW flag describes its catalogue, not every manga it contains.
	// Only title-level metadata should determine the manga's content rating.
	val adultGenres = setOf("adult", "hentai", "18+", "nsfw", "mature", "ecchi")
	val isContentNsfw = safeGenres?.any { it.trim().lowercase() in adultGenres } == true

	return Manga(
		id = stableId(source.name, "manga", safeUrl),
		title = safeTitle,
		altTitles = emptySet(),
		url = safeUrl,
		publicUrl = resolvedUrl,
		rating = RATING_UNKNOWN,
		contentRating = if (isContentNsfw) ContentRating.ADULT else null,
		coverUrl = resolvedCover,
		largeCoverUrl = resolvedCover,
		tags = safeGenres.orEmpty().mapTo(LinkedHashSet()) { genre ->
			MangaTag(key = genre.lowercase(), title = genre, source = source)
		},
		state = when (safeStatus) {
			SManga.ONGOING -> MangaState.ONGOING
			SManga.COMPLETED, SManga.PUBLISHING_FINISHED -> MangaState.FINISHED
			SManga.CANCELLED -> MangaState.ABANDONED
			SManga.ON_HIATUS -> MangaState.PAUSED
			SManga.LICENSED -> MangaState.RESTRICTED
			else -> null
		},
		authors = buildSet {
			safeAuthor?.takeIf { it.isNotBlank() }?.let(::add)
			safeArtist?.takeIf { it.isNotBlank() && it != safeAuthor }?.let(::add)
		},
		description = safeDescription,
		chapters = chapters,
		source = source,
	)
}

fun Manga.toSManga(): SManga {
	return SManga.create().apply {
		// Mihon persists and returns the source-owned URL unchanged. Re-normalizing it here breaks
		// sources that intentionally use absolute URLs, non-HTTP schemes, or opaque identifiers.
		this.url = this@toSManga.url
		this.title = this@toSManga.title
		this.author = this@toSManga.authors.firstOrNull()
		this.artist = this@toSManga.authors.drop(1).firstOrNull()
		this.description = this@toSManga.description
		this.genre = this@toSManga.tags.joinToString(", ") { it.title }
		this.status = when (this@toSManga.state) {
			MangaState.ONGOING -> SManga.ONGOING
			MangaState.FINISHED -> SManga.COMPLETED
			MangaState.ABANDONED -> SManga.CANCELLED
			MangaState.PAUSED -> SManga.ON_HIATUS
			MangaState.RESTRICTED -> SManga.LICENSED
			else -> SManga.UNKNOWN
		}
		this.thumbnail_url = this@toSManga.coverUrl
		// Mihon only marks a source model initialized after details have actually been persisted.
		// Search results commonly have neither description nor chapters and must still fetch details.
		this.initialized = this@toSManga.description != null || this@toSManga.chapters != null
	}
}

// ============ SChapter <-> MangaChapter ============

fun SChapter.toMangaChapter(source: MihonMangaSource, overrideNumber: Float? = null): MangaChapter {
	val finalNumber = overrideNumber ?: (if (chapter_number >= 0f) chapter_number else 0f)
	val normalizedScanlator = scanlator?.trim()?.takeIf { it.isNotEmpty() }
	return MangaChapter(
		id = mihonChapterId(source.name, url),
		title = name.takeIf { it.isNotBlank() },
		number = finalNumber,
		volume = 0,
		url = url,
		scanlator = normalizedScanlator,
		uploadDate = date_upload,
		// Kotatsu groups alternate chapter streams by branch; Mihon's equivalent is scanlator.
		branch = normalizedScanlator,
		source = source,
	)
}

fun MangaChapter.toSChapter(): SChapter = SChapter.create().apply {
	url = this@toSChapter.url
	name = this@toSChapter.title ?: "Chapter ${this@toSChapter.number}"
	chapter_number = this@toSChapter.number
	date_upload = this@toSChapter.uploadDate
	scanlator = this@toSChapter.scanlator ?: this@toSChapter.branch
}

// ============ Page <-> MangaPage ============

fun Page.toMangaPage(source: MihonMangaSource, chapterUrl: String): MangaPage = MangaPage(
	id = stableId(source.name, "page", "$chapterUrl|$index"),
	url = imageUrl ?: url,
	// Mihon pages have no thumbnail: imageUrl is the full page. Using it as the preview made the
	// reader download every page twice at once (a full-size Coil "preview" racing the real load,
	// without the extension's image headers or getImage() unscrambling).
	preview = null,
	source = source,
)

// ============ Internal Helpers ============

private fun stableId(sourceName: String, type: String, value: String): Long {
	return "$sourceName|$type|$value".hashCode().toLong() and Long.MAX_VALUE
}

/**
 * The deterministic id this app assigns to a Mihon manga — a pure function of the source name
 * (`MIHON_<catalogueSourceId>`) and the manga url. Lets the Kotatsu migration compute the canonical
 * id offline (no network) so a swapped manga matches what the source would return when browsed.
 */
fun mihonMangaId(sourceName: String, url: String): Long = stableId(sourceName, "manga", url)

/** The canonical chapter identity used by the live Mihon adapter and backup restoration. */
fun mihonChapterId(sourceName: String, url: String): Long = stableId(sourceName, "chapter", url)

private fun resolveUrl(baseUrl: String?, value: String?): String? {
	if (value.isNullOrBlank()) return null
	if (value.startsWith("http://") || value.startsWith("https://")) return value
	if (value.startsWith("//")) return "https:$value"
	return baseUrl?.trimEnd('/')?.plus("/")?.plus(value.trimStart('/')) ?: value
}
