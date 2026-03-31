package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import org.haziffe.dropsauce.core.model.MangaSource
import org.haziffe.dropsauce.core.model.MangaSource

abstract class HttpSource : CatalogueSource {
	protected val network: NetworkHelper by injectLazy()

	abstract val baseUrl: String

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
		return client.newCall(tagRequest(popularMangaRequest(page))).asObservableSuccess().map(::popularMangaParse)
	}

	protected abstract fun popularMangaRequest(page: Int): Request
	protected abstract fun popularMangaParse(response: Response): MangasPage
	protected abstract fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request
	protected abstract fun searchMangaParse(response: Response): MangasPage
	protected abstract fun latestUpdatesRequest(page: Int): Request
	protected abstract fun latestUpdatesParse(response: Response): MangasPage
	protected abstract fun mangaDetailsParse(response: Response): SManga
	protected abstract fun chapterListParse(response: Response): List<SChapter>
	protected abstract fun pageListParse(response: Response): List<Page>
	protected abstract fun imageUrlParse(response: Response): String

	@Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getSearchManga"))
	override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
		return Observable.defer {
			try {
				client.newCall(tagRequest(searchMangaRequest(page, query, filters))).asObservableSuccess()
			} catch (e: NoClassDefFoundError) {
				throw RuntimeException(e)
			}
		}.map(::searchMangaParse)
	}

	@Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getLatestUpdates"))
	override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
		return client.newCall(tagRequest(latestUpdatesRequest(page))).asObservableSuccess().map(::latestUpdatesParse)
	}

	@Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getMangaDetails"))
	override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
		return client.newCall(tagRequest(mangaDetailsRequest(manga))).asObservableSuccess().map {
			mangaDetailsParse(it).apply { initialized = true }
		}
	}

	open fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

	@Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getChapterList"))
	override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
		return client.newCall(tagRequest(chapterListRequest(manga))).asObservableSuccess().map(::chapterListParse)
	}

	open fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

	@Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getPageList"))
	override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
		return client.newCall(tagRequest(pageListRequest(chapter))).asObservableSuccess().map(::pageListParse)
	}

	open fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

	open fun imageRequest(page: Page): Request = GET(page.imageUrl ?: getImageUrl(page), headers)

	open fun getImageUrl(page: Page): String {
		val imageUrl = page.imageUrl
		if (imageUrl != null) return imageUrl
		return client.newCall(tagRequest(imageUrlRequest(page))).execute().use(::imageUrlParse)
	}

	open fun imageUrlRequest(page: Page): Request = GET(baseUrl + page.url, headers)

	private fun tagRequest(request: Request): Request {
		if (request.tag(org.haziffe.dropsauce.parsers.model.MangaSource::class.java) != null) {
			return request
		}
		return request.newBuilder()
			.tag(
				org.haziffe.dropsauce.parsers.model.MangaSource::class.java,
				MangaSource("MIHON_$id")
			)
			.build()
	}

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
