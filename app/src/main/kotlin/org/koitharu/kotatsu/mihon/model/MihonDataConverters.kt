@file:Suppress("DEPRECATION")

package org.koitharu.kotatsu.mihon.model

import android.util.Log
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

private const val TAG = "MihonDataConverters"

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
	val safeGenres: List<String>? = genres.takeIf { it.isNotEmpty() }
	val safeAuthor = try { author } catch (_: UninitializedPropertyAccessException) { null }
	val safeArtist = try { artist } catch (_: UninitializedPropertyAccessException) { null }
	val safeDescription = try { description } catch (_: UninitializedPropertyAccessException) { null }
	val safeStatus = try { status } catch (_: UninitializedPropertyAccessException) { SManga.UNKNOWN }
	// extensions-lib 1.5+ metadata. Wrapped defensively in case an extension's SManga impl throws.
	val safeAltTitles = runCatching { altTitles }.getOrNull().orEmpty()
	val safeContentRating = runCatching { contentRating }.getOrNull()
	val safeScore = runCatching { score }.getOrNull()

	val resolvedCover = resolveUrl(baseUrl, safeThumbnail)
	val resolvedUrl = if (publicUrl.isNotBlank()) {
		publicUrl
	} else {
		httpSource?.getMangaUrl(this).orEmpty().ifBlank { resolveUrl(baseUrl, safeUrl) ?: safeUrl }
	}

	// Determine NSFW status from source flag or genres
	val adultGenres = setOf("adult", "hentai", "18+", "nsfw", "mature", "ecchi")
	val isContentNsfw = source.isNsfw || safeGenres?.any { it.lowercase() in adultGenres } == true

	// Prefer the source's explicit content rating (extensions-lib 1.5+) over the genre heuristic,
	// matching Mihon. Only fall back to the heuristic when the source reports SAFE/unset.
	val resolvedContentRating = when (safeContentRating) {
		SManga.ContentRating.ADULT -> ContentRating.ADULT
		SManga.ContentRating.SUGGESTIVE -> ContentRating.SUGGESTIVE
		else -> if (isContentNsfw) ContentRating.ADULT else null
	}

	return Manga(
		id = stableId(source.name, "manga", safeUrl),
		title = safeTitle,
		altTitles = safeAltTitles.mapNotNullTo(LinkedHashSet()) { it.trim().takeIf(String::isNotEmpty) },
		url = safeUrl,
		publicUrl = resolvedUrl,
		rating = safeScore?.takeIf { it in 0..100 }?.let { it / 100f } ?: RATING_UNKNOWN,
		contentRating = resolvedContentRating,
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
	// Get baseUrl from source if available
	val baseUrl = (source as? MihonMangaSource)?.let { mihonSource ->
		(mihonSource.catalogueSource as? HttpSource)?.baseUrl ?: ""
	} ?: ""

	var cleanUrl = url

	// Check if URL has duplicate protocol/baseUrl (e.g., "https://domain.comhttps//domain.com/path")
	val httpIndex = cleanUrl.indexOf("http", startIndex = 1)
	if (httpIndex > 0) {
		cleanUrl = cleanUrl.substring(httpIndex)
		Log.w(TAG, "Detected duplicate baseUrl, extracting: '$url' -> '$cleanUrl'")
	}

	// Fix malformed protocols (https// -> https://)
	cleanUrl = cleanUrl.replace(Regex("^(https?)/+"), "$1://")

	// If URL is absolute and starts with baseUrl, strip it to avoid duplicates in HttpSource
	if (baseUrl.isNotBlank()) {
		val baseHost = baseUrl.trimEnd('/')
		if (cleanUrl.startsWith(baseHost)) {
			val stripped = cleanUrl.substring(baseHost.length)
			if (stripped.startsWith("/") || stripped.isEmpty()) {
				cleanUrl = stripped
			}
		}
	}

	return SManga.create().apply {
		this.url = cleanUrl
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
		this.initialized = true
	}
}

// ============ SChapter <-> MangaChapter ============

fun SChapter.toMangaChapter(source: MihonMangaSource, overrideNumber: Float? = null): MangaChapter {
	val finalNumber = overrideNumber ?: (if (chapter_number >= 0f) chapter_number else 0f)
	return MangaChapter(
		id = stableId(source.name, "chapter", url),
		title = name.takeIf { it.isNotBlank() },
		number = finalNumber,
		volume = 0,
		url = url,
		scanlator = scanlator,
		uploadDate = date_upload,
		branch = null,
		source = source,
	)
}

fun MangaChapter.toSChapter(): SChapter = SChapter.create().apply {
	url = this@toSChapter.url
	name = this@toSChapter.title ?: "Chapter ${this@toSChapter.number}"
	chapter_number = this@toSChapter.number
	date_upload = this@toSChapter.uploadDate
	scanlator = this@toSChapter.scanlator
}

// ============ Page <-> MangaPage ============

fun Page.toMangaPage(source: MihonMangaSource, chapterUrl: String): MangaPage = MangaPage(
	id = stableId(source.name, "page", "$chapterUrl|$index"),
	url = imageUrl ?: url,
	// preview is loaded directly as an image (Coil/SSIV), so only keep it when it is an absolute
	// http(s) url. Some extensions (e.g. MangaDex) return a relative imageUrl like "/data/..." whose
	// host lives inside the extension — using it as a preview would fail as a bogus local file path.
	preview = imageUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
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

private fun resolveUrl(baseUrl: String?, value: String?): String? {
	if (value.isNullOrBlank()) return null
	if (value.startsWith("http://") || value.startsWith("https://")) return value
	if (value.startsWith("//")) return "https:$value"
	return baseUrl?.trimEnd('/')?.plus("/")?.plus(value.trimStart('/')) ?: value
}
