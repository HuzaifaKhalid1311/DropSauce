package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.RefreshContext
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import rx.Observable
import tachiyomi.core.common.util.lang.awaitSingle

/**
 * Binary-compatible source contract. Keep this aligned with Mihon's source-api: extension APKs
 * invoke these members through the shared classloader.
 */
interface Source {
	val id: Long
	val name: String
	val lang: String get() = ""

	// Defaulted like Mihon/Tsundoku's own declaration: a source that never overrides it (novel
	// sources routinely don't) would otherwise blow up with AbstractMethodError on first browse.
	val supportsLatest: Boolean get() = false

	/**
	 * True for text sources — Tsundoku's novel extensions. Chapter content then comes from
	 * [fetchPageText] instead of a list of images. Kept as a property, not an `is NovelSource`
	 * check, because that is how extensions declare it and how it dispatches across classloaders.
	 */
	val isNovelSource: Boolean get() = false

	fun getFilterList(): FilterList = FilterList()
	suspend fun getPopularManga(page: Int): MangasPage
	suspend fun getLatestUpdates(page: Int): MangasPage
	suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage
	suspend fun getMangaUpdate(
		manga: SManga,
		chapters: List<SChapter>,
		fetchDetails: Boolean,
		fetchChapters: Boolean,
	): SMangaUpdate
	suspend fun getPageList(chapter: SChapter): List<Page>

	/** A novel chapter's body, as html or plain text. Only ever called on [isNovelSource] sources. */
	suspend fun fetchPageText(page: Page): String =
		throw UnsupportedOperationException("Not a novel source")

	// Legacy extensions-lib 1.5 API. Extensions compiled against lib 1.5 (the current Keiyoushi
	// baseline) override these two instead of getMangaUpdate — e.g. API-based sources such as
	// MangaDex or Comick, which never touch the request/parse helpers. The host must keep these
	// signatures dispatchable and route getMangaUpdate through them; calling fetchMangaDetails /
	// fetchChapterList directly would skip those overrides and hit the throwing parse defaults.
	@Suppress("DEPRECATION")
	suspend fun getMangaDetails(manga: SManga): SManga = fetchMangaDetails(manga).awaitSingle()

	@Suppress("DEPRECATION")
	suspend fun getChapterList(manga: SManga): List<SChapter> = fetchChapterList(manga).awaitSingle()

	/**
	 * Fork API (extensions-lib 1.6). Sources whose chapter list is paginated — most novel sources —
	 * override only this form, so the host calls it and lets the default bridge to the plain one for
	 * every extension that doesn't.
	 */
	suspend fun getChapterList(manga: SManga, context: RefreshContext): List<SChapter> =
		getChapterList(manga)

	@Deprecated("Use the combined suspend API instead", ReplaceWith("getMangaUpdate"))
	fun fetchMangaDetails(manga: SManga): Observable<SManga> = throw UnsupportedOperationException()

	@Deprecated("Use the combined suspend API instead", ReplaceWith("getMangaUpdate"))
	fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = throw UnsupportedOperationException()

	@Deprecated("Use the suspend API instead", ReplaceWith("getPageList"))
	fun fetchPageList(chapter: SChapter): Observable<List<Page>> = throw UnsupportedOperationException()
}
