package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.util.awaitSingle
import rx.Observable

interface CatalogueSource : Source {
	override val lang: String

	val supportsLatest: Boolean

	@Suppress("DEPRECATION")
	suspend fun getPopularManga(page: Int): MangasPage {
		return fetchPopularManga(page).awaitSingle()
	}

	@Suppress("DEPRECATION")
	suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
		return fetchSearchManga(page, query, filters).awaitSingle()
	}

	@Suppress("DEPRECATION")
	suspend fun getLatestUpdates(page: Int): MangasPage {
		return fetchLatestUpdates(page).awaitSingle()
	}

	fun getFilterList(): FilterList

	@Deprecated("Use the non-RxJava API instead", ReplaceWith("getPopularManga"))
	fun fetchPopularManga(page: Int): Observable<MangasPage> =
		throw IllegalStateException("Not used")

	@Deprecated("Use the non-RxJava API instead", ReplaceWith("getSearchManga"))
	fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
		throw IllegalStateException("Not used")

	@Deprecated("Use the non-RxJava API instead", ReplaceWith("getLatestUpdates"))
	fun fetchLatestUpdates(page: Int): Observable<MangasPage> =
		throw IllegalStateException("Not used")
}
