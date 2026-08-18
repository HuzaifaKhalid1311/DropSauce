@file:Suppress("DEPRECATION")

package org.koitharu.kotatsu.mihon

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.exceptions.InteractiveActionRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.mihon.model.toManga
import org.koitharu.kotatsu.mihon.model.toMangaChapter
import org.koitharu.kotatsu.mihon.model.toMangaPage
import org.koitharu.kotatsu.mihon.model.toSChapter
import org.koitharu.kotatsu.mihon.model.toSManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.SortOrder
import tachiyomi.domain.chapter.service.ChapterRecognition
import java.util.EnumSet

@OptIn(InternalParsersApi::class)
class MihonMangaRepository(
	override val source: MihonMangaSource,
	cache: MemoryContentCache,
	context: Context,
) : CachingMangaRepository(cache), MihonFilterHost {

	private val sourceSettings = SourceSettings(context, source)
	private val sourceMetadata = MihonSourceMetadataStore(context)

	companion object {
		private const val TAG = "MihonMangaRepository"
		private const val MIHON_URL_SCHEME = "mihon"
		private const val MIHON_RESOLVE_HOST = "resolve"
		private const val MIHON_IMAGE_HOST = "image"
		private const val QUERY_PAGE_URL = "page_url"
		private const val QUERY_IMAGE_URL = "image_url"
		private const val QUERY_INDEX = "index"
	}

	val mihonSource = source.catalogueSource

	// Keyed by (order, query/tags) so a search fired while a plain browse listing is still paging
	// doesn't corrupt the browse listing's page counter, and vice versa — each distinct listing gets
	// its own page/offset tracking instead of sharing one mutable counter on the repository instance.
	// ponytail: entries are never evicted; bounded in practice by the handful of distinct
	// query/filter combos a session touches per source, add an LRU cap if that stops holding.
	private class PaginationState {
		var currentPage = 1
		var lastOffset = -1
		@Volatile var hasMorePages = true
	}

	private val paginationStates = java.util.concurrent.ConcurrentHashMap<String, PaginationState>()

	private fun paginationKey(order: SortOrder?, filter: MangaListFilter?): String =
		"$order|${filter?.query}|${filter?.tags}|${filter?.tagsExclude}"

	override val sortOrders: Set<SortOrder> = buildSet {
		add(SortOrder.POPULARITY)
		if (source.supportsLatest) add(SortOrder.UPDATED)
		add(SortOrder.RELEVANCE)
	}.let { EnumSet.copyOf(it) }

	override var defaultSortOrder: SortOrder
		// Only the two in-app listings can be the browse default; anything else stored by an older
		// build (e.g. RELEVANCE, which needs a query) falls back to Popular like Mihon's default.
		get() = sourceSettings.defaultSortOrder?.takeIf { it == SortOrder.POPULARITY || it == SortOrder.UPDATED }
			?: SortOrder.POPULARITY
		set(value) {
			sourceSettings.defaultSortOrder = value
		}

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> = withContext(Dispatchers.IO) {
		val state = paginationStates.getOrPut(paginationKey(order, filter)) { PaginationState() }
		val page = synchronized(state) {
			if (offset == 0) {
				state.currentPage = 1
				state.lastOffset = 0
				state.hasMorePages = true
			} else if (offset > state.lastOffset) {
				state.lastOffset = offset
				state.currentPage += 1
			}
			state.currentPage
		}

		// Source told us on the last page that there are no more results — skip the request.
		if (offset > 0 && !state.hasMorePages) return@withContext emptyList()

		val query = filter?.query?.trim().orEmpty()

		val hasFilters = filter?.let {
			it.query?.isNotBlank() == true || it.tags.isNotEmpty() || it.tagsExclude.isNotEmpty()
		} ?: false

		val mangasPage = try {
			when {
				// Mihon's three listings: a search/filter request, or one of the two dedicated
				// endpoints. A chosen server-side sort is encoded as a filter tag, so it lands in the
				// first branch and the endpoints only serve an otherwise-untouched browse.
				hasFilters -> mihonSource.getSearchManga(page, query, filter.toMihonFilterList())
				order == SortOrder.UPDATED && source.supportsLatest -> mihonSource.getLatestUpdates(page)
				else -> mihonSource.getPopularManga(page)
			}
		} catch (e: Exception) {
			throw translateExtensionException(e)
		}

		// Remember whether the source has more pages for the next getList() call with this key.
		state.hasMorePages = mangasPage.hasNextPage

		val httpSource = mihonSource as? HttpSource
		mangasPage.mangas.map { sManga ->
			// New-API extensions stash the manga id in memo and require it back in getMangaUpdate.
			// Kotatsu's Manga model can't carry it, so sidecar it now — details restores it.
			sourceMetadata.saveMemo(source.sourceId, sManga.url, sManga)
			sManga.toManga(
				source = source,
				publicUrl = httpSource?.getMangaUrl(sManga).orEmpty(),
			)
		}
	}

	override suspend fun getDetailsImpl(manga: Manga): Manga = withContext(Dispatchers.IO) {
		try {
			getDetailsInner(manga)
		} catch (e: HttpException) {
			// A 404/410 usually means the stored url is stale — e.g. AsuraScans rotates the random
			// hash suffix on its slugs, so a url from a (Kotatsu) backup no longer resolves even
			// though the manga is still on the site. Surface it as NotFoundException so
			// DetailsLoadUseCase's RecoverMangaUseCase re-resolves by title and repairs the url,
			// keeping the same manga id (favourites/history/bookmarks stay attached).
			if (e.code == 404 || e.code == 410) {
				throw NotFoundException("HTTP ${e.code}", manga.publicUrl.ifBlank { manga.url })
			}
			throw e
		}
	}

	private suspend fun getDetailsInner(manga: Manga): Manga {
		val sManga = manga.toSourceManga()
		val existingChapters = manga.chapters.orEmpty().map { it.toSourceChapter() }
		// Route through the extension's combined API exactly like Mihon. Modern sources can override
		// this directly; CatalogueSource's legacy default concurrently bridges both RxJava calls.
		// Swallowing a detail failure and returning stale data makes broken sources appear healthy,
		// so errors now propagate through the normal resolver instead.
		// Do not add an app-level retry around the extension call. Mihon invokes this once and lets
		// the source's client/interceptors decide whether retrying is safe; replaying here can repeat
		// stateful requests and adds a fixed delay to ordinary failures.
		val update = try {
			mihonSource.getMangaUpdate(
				manga = sManga,
				chapters = existingChapters,
				fetchDetails = true,
				fetchChapters = true,
			)
		} catch (e: Exception) {
			throw translateExtensionException(e)
		}
		val details = update.manga
		// Many extensions never set url on the details SManga (Mihon's copyFrom skips it),
		// so reading the lateinit property directly would crash the whole details load.
		val detailsUrl = try { details.url } catch (_: UninitializedPropertyAccessException) { sManga.url }
		val rawChapters = update.chapters.onEach { chapter ->
			chapter.url = normalizeChapterUrl(chapter.url, sManga.url, detailsUrl)
		}

		// Deduplicate by URL — some sources accidentally return the same chapter twice.
		val uniqueChapters = rawChapters.distinctBy { it.url }

		val httpSource = mihonSource as? HttpSource

		// Let the extension normalize chapter data (title, scanlator, chapter number, etc.)
		// before we process them.  HttpSource.prepareNewChapter() is a no-op by default but
		// many extensions override it.
		if (httpSource != null) {
			uniqueChapters.forEach { sChapter -> httpSource.prepareNewChapter(sChapter, sManga) }
		}

		Log.d(TAG, "rawChapters count: ${rawChapters.size} (unique: ${uniqueChapters.size}), source: ${source.name}")

		// New-API extensions carry the chapter id in SChapter.memo (getPageList throws "Refresh
		// Chapter List" without it). Kotatsu's chapter model drops it, so sidecar it by chapter url.
		sourceMetadata.saveChapterMemos(
			source.sourceId,
			uniqueChapters.filter { it.memo.isNotEmpty() }.associate { it.url to it.memo },
		)

		val mangaTitle = try { sManga.title } catch (_: UninitializedPropertyAccessException) { "" }

		// Mihon convention: getChapterList() returns chapters newest-first, while Kotatsu's chapter
		// model and update tracker expect oldest-first (the newest chapter is last). Reverse the
		// complete source list without sorting it, which preserves the source's canonical order for
		// missing/duplicate numbers, bonus chapters, and interleaved scanlator variants.
		// For the number itself,
		// derive it from the chapter name via ChapterRecognition when the extension left it unset
		// (chapter_number < 0), instead of inventing a sequential index.
		val chapters = normalizeMihonChapterOrder(uniqueChapters)
			.map { sChapter ->
				val number = ChapterRecognition.parseChapterNumber(
					mangaTitle = mangaTitle,
					chapterName = sChapter.name,
					chapterNumber = sChapter.chapter_number.toDouble(),
				).toFloat()
				sChapter.toMangaChapter(source, number)
			}

		// Fallback for missing details fields
		details.url = sManga.url
		// Mihon persists these source-owned fields with the manga. Kotatsu's public Manga model
		// cannot represent them, so retain them in the private compatibility sidecar.
		sourceMetadata.save(source.sourceId, details.url, details)

		val detailsTitle = try { details.title } catch (_: UninitializedPropertyAccessException) { "" }
		if (detailsTitle.isBlank()) {
			details.title = sManga.title
		}

		val detailsThumb = try { details.thumbnail_url } catch (_: UninitializedPropertyAccessException) { null }
		val searchThumb = try { sManga.thumbnail_url } catch (_: UninitializedPropertyAccessException) { null }

		if (detailsThumb.isNullOrBlank() || detailsThumb == details.url || detailsThumb == sManga.url) {
			if (!searchThumb.isNullOrBlank()) {
				details.thumbnail_url = searchThumb
			}
		}

		val publicUrl = httpSource?.getMangaUrl(details).orEmpty()

		return details.toManga(
			source = source,
			chapters = chapters,
			publicUrl = publicUrl,
		).copy(id = manga.id)
	}

	override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> = withContext(Dispatchers.IO) {
		// A novel chapter is one "page" of prose, fetched later by getChapterHtml. Same shape as the
		// LNReader repository so the text reader treats both kinds of novel identically, and no
		// network call happens here.
		if (source.isNovel) {
			return@withContext listOf(
				MangaPage(id = chapter.id, url = chapter.url, preview = null, source = source),
			)
		}
		val sChapter = chapter.toSourceChapter()
		// Match Mihon and delegate retry policy to the source's own OkHttp client.
		val rawPages = try {
			mihonSource.getPageList(sChapter)
		} catch (e: Exception) {
			throw translateExtensionException(e)
		}
		// Some extensions (compiled from Java or with internal bugs) return a List<Page> that
		// contains null elements at runtime despite the non-null Kotlin type. Filtering here
		// prevents the "Attempt to invoke virtual method on a null object reference" NPE.
		@Suppress("UNCHECKED_CAST")
		val pages = (rawPages as List<Page?>).filterNotNull()
		pages.mapIndexed { index, page ->
			val mapped = page.toMangaPage(source, chapter.url)
			when {
				page.imageUrl.isNullOrBlank() && page.url.isNotBlank() -> mapped.copy(
					url = buildMihonPageUrl(
						host = MIHON_RESOLVE_HOST,
						pageUrl = page.url,
						imageUrl = null,
						index = index,
					),
				)

				!page.imageUrl.isNullOrBlank() && page.url.isNotBlank() && page.url != page.imageUrl -> mapped.copy(
					url = buildMihonPageUrl(
						host = MIHON_IMAGE_HOST,
						pageUrl = page.url,
						imageUrl = page.imageUrl,
						index = index,
					),
				)

				else -> mapped
			}
		}
	}

	/** A novel chapter's body. Null for a manga source, which is what the text reader checks. */
	override suspend fun getChapterHtml(chapter: MangaChapter): String? {
		if (!source.isNovel) return null
		return withContext(Dispatchers.IO) { fetchChapterText(chapter.url) }
	}

	private suspend fun fetchChapterText(chapterUrl: String): String = try {
		mihonSource.fetchPageText(Page(index = 0, url = chapterUrl))
	} catch (e: Exception) {
		throw translateExtensionException(e)
	}

	override suspend fun getPageUrl(page: MangaPage): String = withContext(Dispatchers.IO) {
		if (source.isNovel) return@withContext page.url
		val httpSource = mihonSource as? HttpSource ?: return@withContext page.url
		val ref = page.url.toMihonPageRef() ?: return@withContext page.url
		when (ref.host) {
			MIHON_RESOLVE_HOST -> httpSource.getImageUrl(Page(ref.index, ref.pageUrl))
			MIHON_IMAGE_HOST -> ref.imageUrl ?: page.url
			else -> page.url
		}
	}

	override suspend fun getChapterUrl(chapter: MangaChapter): String? = withContext(Dispatchers.IO) {
		val httpSource = mihonSource as? HttpSource ?: return@withContext null
		runCatching { httpSource.getChapterUrl(chapter.toSourceChapter()) }.getOrNull()?.takeIf { it.isNotBlank() }
	}

	override suspend fun getImageRequestHeaders(imageUrl: String, page: MangaPage): Headers? {
		val httpSource = (mihonSource as? HttpSource) ?: return null
		return runCatching { httpSource.getImageHeaders(page.toMihonPage(imageUrl)) }.getOrNull()
	}

	/**
	 * Delegates image fetching to the extension's [HttpSource.getImage].
	 * Extensions may override [getImage] to decrypt or un-scramble images before returning
	 * them — bypassing this would deliver encrypted bytes to the decoder, causing failures
	 * like "unsupported image format: image/jpeg".
	 */
	override suspend fun getImageStream(pageUrl: String, page: MangaPage): Response? =
		withContext(Dispatchers.IO) {
			// The download worker treats this as "the source fetches this itself", so handing it a
			// synthetic response carrying the chapter html downloads a novel with no changes to the
			// worker — same trick as the LNReader repository.
			if (source.isNovel) {
				val html = fetchChapterText(page.url)
				return@withContext Response.Builder()
					.request(Request.Builder().url(novelRequestUrl(page.url)).build())
					.protocol(Protocol.HTTP_1_1)
					.code(200)
					.message("OK")
					.body(html.toResponseBody("application/xhtml+xml".toMediaType()))
					.build()
			}
			val httpSource = mihonSource as? HttpSource ?: return@withContext null
			httpSource.getImage(page.toMihonPage(pageUrl))
		}

	/** OkHttp requires an absolute url on the synthetic response above; chapter paths are relative. */
	private fun novelRequestUrl(chapterUrl: String): String = when {
		chapterUrl.startsWith("http://") || chapterUrl.startsWith("https://") -> chapterUrl
		else -> (mihonSource as? HttpSource)?.baseUrl?.trimEnd('/')?.plus("/" + chapterUrl.trimStart('/'))
			?: "https://localhost/" + chapterUrl.trimStart('/')
	}

	/**
	 * Fetches a cover/thumbnail through the extension's own client + headers — identical to Mihon's
	 * MangaCoverFetcher and to [org.koitharu.kotatsu.core.image.MihonImageFetcher]. Lets non-Coil
	 * paths (e.g. the download worker saving a cover) avoid the app's shared client, which some
	 * sources 403.
	 */
	override suspend fun getCoverStream(url: String): Response? = withContext(Dispatchers.IO) {
		val httpSource = mihonSource as? HttpSource ?: return@withContext null
		val request = Request.Builder()
			.url(url)
			.headers(httpSource.headers)
			.build()
		httpSource.client.newCall(request).awaitSuccess()
	}

	private fun MangaPage.toMihonPage(imageUrl: String): Page {
		val ref = url.toMihonPageRef()
		val pageUrl = ref?.pageUrl ?: imageUrl
		val index = ref?.index ?: 0
		return Page(
			index = index,
			url = pageUrl,
			imageUrl = imageUrl,
		)
	}

	private fun buildMihonPageUrl(
		host: String,
		pageUrl: String,
		imageUrl: String?,
		index: Int,
	): String = android.net.Uri.Builder()
		.scheme(MIHON_URL_SCHEME)
		.authority(host)
		.appendQueryParameter(QUERY_PAGE_URL, pageUrl)
		.apply {
			if (!imageUrl.isNullOrBlank()) {
				appendQueryParameter(QUERY_IMAGE_URL, imageUrl)
			}
			appendQueryParameter(QUERY_INDEX, index.toString())
		}
		.build()
		.toString()

	private fun String.toMihonPageRef(): MihonPageRef? {
		val uri = runCatching { toUri() }.getOrNull() ?: return null
		if (uri.scheme != MIHON_URL_SCHEME) return null
		val pageUrl = uri.getQueryParameter(QUERY_PAGE_URL) ?: return null
		return MihonPageRef(
			host = uri.host.orEmpty(),
			pageUrl = pageUrl,
			imageUrl = uri.getQueryParameter(QUERY_IMAGE_URL),
			index = uri.getQueryParameter(QUERY_INDEX)?.toIntOrNull() ?: 0,
		)
	}

	private data class MihonPageRef(
		val host: String,
		val pageUrl: String,
		val imageUrl: String?,
		val index: Int,
	)

	/**
	 * Translates extension-thrown exceptions that signal a user action is needed (e.g. a cookie
	 * gate) into [InteractiveActionRequiredException] so the UI can offer to open the WebView.
	 *
	 * Pattern matched: messages like "mhub_cookie not found", "access_token not found", etc.
	 * Unrecognised exceptions are returned unchanged.
	 */
	private fun translateExtensionException(e: Exception): Exception {
		val msg = e.message ?: return e
		val httpSource = mihonSource as? HttpSource ?: return e
		val msgLower = msg.lowercase()
		// The vendored CloudflareInterceptor already tried a silent WebView bypass; when it gives
		// up it throws IOException("Cloudflare bypass failed"). Map it onto the app's CloudFlare
		// pipeline so TrackWorker/CaptchaHandler can post the "captcha required" notification and
		// offer the resolver WebView (the solved cf_clearance cookie is shared with the extension
		// through the system CookieManager).
		if ("cloudflare bypass failed" in msgLower) {
			return CloudFlareProtectedException(
				url = httpSource.baseUrl,
				source = source,
				headers = httpSource.headers,
			)
		}
		// Cloudflare (or another WAF) returned an HTML challenge/block page instead of JSON.
		// The kotlinx.serialization JsonDecodingException embeds the raw input in its message.
		if ("<!doctype html>" in msgLower || "json input: <" in msgLower) {
			// successCookieName = null → BrowserActivity.finish() always returns RESULT_OK,
			// so we don't need Cloudflare's specific cookie name. The embedded WebView shares
			// the system CookieManager, so any cookie it receives is visible to OkHttp on retry.
			return InteractiveActionRequiredException(
				source = source,
				url = httpSource.baseUrl,
				successCookieUrl = null,
				successCookieName = null,
			)
		}
		// Cookie-gated sources throw plain Exception("mhub_access cookie not found") or
		// Exception("mhub_cookie not found"). The `(?:cookie\s+)?` makes the word "cookie"
		// optional so both forms are caught.
		if ("not found" in msgLower && "cookie" in msgLower) {
			val cookieName = Regex("""([\w_]+)\s+(?:cookie\s+)?not\s+found""", RegexOption.IGNORE_CASE)
				.find(msg)?.groupValues?.get(1)
			return InteractiveActionRequiredException(
				source = source,
				url = httpSource.baseUrl,
				successCookieUrl = httpSource.baseUrl,
				successCookieName = cookieName,
			)
		}
		return e
	}

	// Filters are rendered dynamically from the source's own FilterList (see MihonFilterHost /
	// MihonFilterSheetFragment), so the structured Kotatsu options stay empty — nothing is flattened
	// into the genres list anymore.
	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

	override val supportsDynamicFilters: Boolean
		get() = true

	override val inAppListingsExclusive: Boolean
		get() = true

	override suspend fun loadDefaultFilterList(): FilterList = withContext(Dispatchers.IO) {
		try {
			mihonSource.getFilterList()
		} catch (e: Exception) {
			FilterList()
		}
	}

	private fun MangaListFilter.toMihonFilterList(): FilterList {
		val mihonFilters = try {
			mihonSource.getFilterList()
		} catch (e: Exception) {
			return FilterList()
		}
		MihonFilterMapper.decode(mihonFilters, this)
		return mihonFilters
	}

	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> = withContext(Dispatchers.IO) {
		val httpSource = mihonSource as? HttpSource
		if (mihonSource.disableRelatedMangas) return@withContext emptyList()
		if (mihonSource.supportsRelatedMangas) {
			val extensionRelated = runCatching {
				mihonSource.fetchRelatedMangaList(seed.toSourceManga())
			}.getOrDefault(emptyList())
			if (extensionRelated.isNotEmpty()) {
				return@withContext extensionRelated
					.distinctBy { it.url }
					.map { item ->
						sourceMetadata.saveMemo(source.sourceId, item.url, item)
						item.toManga(
							source = source,
							publicUrl = httpSource?.getMangaUrl(item).orEmpty(),
						)
					}
			}
		}
		if (mihonSource.disableRelatedMangasBySearch) return@withContext emptyList()
		val tags = seed.tags
		val page = if (tags.isNotEmpty()) {
			val query = tags.take(3).joinToString(" ") { it.title }
			var result = runCatching { mihonSource.getSearchManga(1, query, FilterList()) }
				.getOrElse { MangasPage(emptyList(), false) }
			if (result.mangas.isEmpty() && tags.size > 1) {
				result = runCatching { mihonSource.getSearchManga(1, tags.first().title, FilterList()) }
					.getOrElse { MangasPage(emptyList(), false) }
			}
			result
		} else {
			runCatching { mihonSource.getPopularManga(1) }
				.getOrElse { MangasPage(emptyList(), false) }
		}

		val seedTags = tags.map { it.title.lowercase() }.toSet()
		page.mangas
			.filter { it.url != seed.url }
			.map { sManga ->
				sourceMetadata.saveMemo(source.sourceId, sManga.url, sManga)
				sManga.toManga(
					source = source,
					publicUrl = httpSource?.getMangaUrl(sManga).orEmpty(),
				)
			}
			.sortedByDescending { manga ->
				manga.tags.count { it.title.lowercase() in seedTags }
			}
			.take(10)
	}

	private fun Manga.toSourceManga() = toSManga().also {
		sourceMetadata.restore(this@MihonMangaRepository.source.sourceId, url, it)
	}

	private fun MangaChapter.toSourceChapter() = toSChapter().also { sChapter ->
		sourceMetadata.restoreChapterMemo(this@MihonMangaRepository.source.sourceId, url)?.let { sChapter.memo = it }
	}
}

/**
 * Mihon sources return newest-first; DropSauce/Kotatsu consumers use oldest-first.
 *
 * Keep this as a reversal rather than a chapter-number sort: source order is authoritative and
 * scanlator variants commonly share the same recognized chapter number.
 */
internal fun <T> normalizeMihonChapterOrder(chapters: List<T>): List<T> = chapters.asReversed()

internal fun normalizeChapterUrl(chapterUrl: String, oldMangaUrl: String, newMangaUrl: String): String {
	if (oldMangaUrl.isBlank() || newMangaUrl.isBlank() || oldMangaUrl == newMangaUrl) return chapterUrl
	val oldPrefix = oldMangaUrl.trimEnd('/')
	return if (chapterUrl.startsWith("$oldPrefix/")) {
		newMangaUrl.trimEnd('/') + chapterUrl.removePrefix(oldPrefix)
	} else {
		chapterUrl
	}
}
