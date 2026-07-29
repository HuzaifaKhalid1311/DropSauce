package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.awaitSingle
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.injectLazy
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

abstract class HttpSource : CatalogueSource {
	protected val network: NetworkHelper by injectLazy()

	abstract val baseUrl: String

	/** Returns the home URL of this source, shown in deep-link handlers (extensions-lib 1.5+). */
	open fun getHomeUrl(): String = baseUrl

	open val versionId = 1

	override val id by lazy { generateId(name, lang, versionId) }

	val headers: Headers by lazy { headersBuilder().build() }

	open val client: OkHttpClient
		get() = network.client

	protected fun generateId(name: String, lang: String, versionId: Int): Long {
		val key = "${name.lowercase()}/$lang/$versionId"
		val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
		return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
	}

	protected open fun headersBuilder() = Headers.Builder().apply {
		add("User-Agent", network.defaultUserAgentProvider())
	}

	override fun toString() = "$name (${lang.uppercase()})"

	@Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getPopularManga"))
	override fun fetchPopularManga(page: Int): Observable<MangasPage> {
		return client.newCall(popularMangaRequest(page)).asObservableSuccess().map(::popularMangaParse)
	}

	// Mihon keeps every legacy helper open with a throwing default. Making these abstract changes
	// superclass verification for suspend-only extension classes compiled against source-api 1.6.
	protected open fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException()
	protected open fun popularMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
	protected open fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
		throw UnsupportedOperationException()
	protected open fun searchMangaParse(response: Response): MangasPage = throw UnsupportedOperationException()
	protected open fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
	protected open fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException()
	protected open fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException()
	protected open fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()
	protected open fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()
	protected open fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

	@Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getSearchManga"))
	override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
		return client.newCall(searchMangaRequest(page, query, filters))
			.asObservableSuccess()
			.map(::searchMangaParse)
	}

	@Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getLatestUpdates"))
	override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
		return client.newCall(latestUpdatesRequest(page)).asObservableSuccess().map(::latestUpdatesParse)
	}

	@Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getMangaDetails"))
	override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
		return client.newCall(mangaDetailsRequest(manga)).asObservableSuccess().map {
			mangaDetailsParse(it).apply { initialized = true }
		}
	}

	open fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

	override val supportsRelatedMangas: Boolean get() = true

	override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> =
		withContext(Dispatchers.IO) {
			client.newCall(relatedMangaListRequest(manga)).execute().use(::relatedMangaListParse)
		}

	protected open fun relatedMangaListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

	protected open fun relatedMangaListParse(response: Response): List<SManga> =
		popularMangaParse(response).mangas

	@Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getChapterList"))
	override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
		return client.newCall(chapterListRequest(manga)).asObservableSuccess().map(::chapterListParse)
	}

	protected open fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

	/**
	 * A novel chapter is one page whose content is fetched later by `fetchPageText`, so there is
	 * nothing to request here. Mirrors Tsundoku: without this, a novel source that doesn't override
	 * getPageList would fall through to the html page parser and fetch the chapter twice.
	 */
	override suspend fun getPageList(chapter: SChapter): List<Page> {
		if (isNovelSource) {
			return listOf(Page(0, chapter.url))
		}
		return super<CatalogueSource>.getPageList(chapter)
	}

	@Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getPageList"))
	override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
		return client.newCall(pageListRequest(chapter)).asObservableSuccess().map(::pageListParse)
	}

	protected open fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

	/**
	 * Returns the request for getting the source image.
	 * Requires [page.imageUrl] to be non-null (set via [getImageUrl] first).
	 */
	protected open fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers)

	/**
	 * Returns the OkHttp headers that should be used when fetching the image at [imageUrl].
	 * Delegates to [imageRequest] so any extension override (e.g. adding Referer) is honoured.
	 *
	 * @since extensions-lib 1.5
	 */
	fun getImageHeaders(imageUrl: String): Headers =
		getImageHeaders(Page(index = 0, url = imageUrl, imageUrl = imageUrl))

	fun getImageHeaders(page: Page): Headers = imageRequest(page).headers

	/**
	 * Returns the response of the source image.
	 * Typically does not need to be overridden.
	 *
	 * @since extensions-lib 1.5
	 */
	open suspend fun getImage(page: Page): Response {
		return client.newCachelessCallWithProgress(imageRequest(page), page)
			.awaitSuccess()
	}

	/**
	 * Returns the absolute url of the source image.
	 * Override this if the image URL must be resolved by a network request.
	 *
	 * @since extensions-lib 1.5
	 */
	@Suppress("DEPRECATION")
	open suspend fun getImageUrl(page: Page): String {
		return fetchImageUrl(page).awaitSingle()
	}

	@Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getImageUrl"))
	open fun fetchImageUrl(page: Page): Observable<String> {
		return client.newCall(imageUrlRequest(page))
			.asObservableSuccess()
			.map { imageUrlParse(it) }
	}

	/**
	 * Keiyoushi extensions-lib 1.4 still exposes the Rx image response API. Retain it alongside
	 * Mihon's suspend getImage() so old and new extensions both dispatch through imageRequest().
	 */
	fun fetchImage(page: Page): Observable<Response> =
		client.newCall(imageRequest(page)).asObservable()

	protected open fun imageUrlRequest(page: Page): Request = GET(page.url, headers)

	open fun getPageHeaders(page: Page): Headers = headers

	fun SChapter.setUrlWithoutDomain(url: String) {
		this.url = getUrlWithoutDomain(url)
	}

	fun SManga.setUrlWithoutDomain(url: String) {
		this.url = getUrlWithoutDomain(url)
	}

	private fun getUrlWithoutDomain(orig: String): String {
		return try {
			val uri = URI(orig.replace(" ", "%20"))
			buildString {
				append(uri.path)
				uri.query?.let {
					append('?')
					append(it)
				}
				uri.fragment?.let {
					append('#')
					append(it)
				}
			}
		} catch (_: URISyntaxException) {
			orig
		}
	}

	open fun getMangaUrl(manga: SManga): String = mangaDetailsRequest(manga).url.toString()
	open fun getChapterUrl(chapter: SChapter): String = pageListRequest(chapter).url.toString()
	open fun prepareNewChapter(chapter: SChapter, manga: SManga) = Unit

	override fun getFilterList(): FilterList = FilterList()
}
