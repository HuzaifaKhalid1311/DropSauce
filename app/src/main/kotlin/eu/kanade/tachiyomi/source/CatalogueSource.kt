package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.RefreshContext
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import rx.Observable
import tachiyomi.core.common.util.lang.awaitSingle

interface CatalogueSource : Source {
	override val lang: String
	// Komikku-compatible members are part of Keiyoushi's compile-time stubs. Keep them as a
	// harmless superset even though upstream Mihon does not consume them directly.
	val supportsRelatedMangas: Boolean get() = false
	val disableRelatedMangasBySearch: Boolean get() = false
	val disableRelatedMangas: Boolean get() = false
	suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> =
		throw UnsupportedOperationException("Unsupported!")

	@Suppress("DEPRECATION")
	override suspend fun getPopularManga(page: Int): MangasPage = fetchPopularManga(page).awaitSingle()

	@Suppress("DEPRECATION")
	override suspend fun getLatestUpdates(page: Int): MangasPage = fetchLatestUpdates(page).awaitSingle()

	@Suppress("DEPRECATION")
	override suspend fun getSearchManga(
		page: Int,
		query: String,
		filters: FilterList,
	): MangasPage = fetchSearchManga(page, query, filters).awaitSingle()

	@Suppress("DEPRECATION")
	override suspend fun getMangaUpdate(
		manga: SManga,
		chapters: List<SChapter>,
		fetchDetails: Boolean,
		fetchChapters: Boolean,
	): SMangaUpdate = supervisorScope {
		// Mihon fetches details and chapters concurrently; this is both ABI behavior and a hot-path
		// performance requirement for legacy Rx-only extensions.
		// Route through the lib-1.5 suspend API (whose defaults bridge to the Rx calls) so
		// extensions overriding getMangaDetails / getChapterList are honoured too.
		val asyncManga = if (fetchDetails) async { getMangaDetails(manga) } else null
		// Through the RefreshContext form: sources with paginated chapter lists override only that
		// one, and its default delegates to getChapterList(manga) for everything else.
		val asyncChapters = if (fetchChapters) {
			async {
				getChapterList(
					manga,
					RefreshContext(
						mangaId = 0L,
						existingChapters = chapters,
						lastFetchTime = 0L,
						forceRefresh = chapters.isEmpty(),
					),
				)
			}
		} else {
			null
		}
		SMangaUpdate(asyncManga?.await() ?: manga, asyncChapters?.await() ?: chapters)
	}

	@Suppress("DEPRECATION")
	override suspend fun getPageList(chapter: SChapter): List<Page> = fetchPageList(chapter).awaitSingle()

	@Deprecated("Use the suspend API instead", ReplaceWith("getPopularManga"))
	fun fetchPopularManga(page: Int): Observable<MangasPage> = throw UnsupportedOperationException()

	@Deprecated("Use the suspend API instead", ReplaceWith("getSearchManga"))
	fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
		throw UnsupportedOperationException()

	@Deprecated("Use the suspend API instead", ReplaceWith("getLatestUpdates"))
	fun fetchLatestUpdates(page: Int): Observable<MangasPage> = throw UnsupportedOperationException()
}
