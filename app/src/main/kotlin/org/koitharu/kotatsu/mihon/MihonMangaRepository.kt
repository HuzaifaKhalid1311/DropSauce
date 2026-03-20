package org.koitharu.kotatsu.mihon

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.mihon.model.toManga
import org.koitharu.kotatsu.mihon.model.toMangaChapter
import org.koitharu.kotatsu.mihon.model.toMangaPage
import org.koitharu.kotatsu.mihon.model.toSChapter
import org.koitharu.kotatsu.mihon.model.toSManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.EnumSet

class MihonMangaRepository(
	override val source: MihonMangaSource,
	cache: MemoryContentCache,
) : CachingMangaRepository(cache) {

	private val mihonSource = source.catalogueSource
	private var lastOffset = -1
	private var currentPage = 1

	override val sortOrders: Set<SortOrder> = buildSet {
		add(SortOrder.POPULARITY)
		if (source.supportsLatest) add(SortOrder.UPDATED)
		add(SortOrder.RELEVANCE)
	}.let { EnumSet.copyOf(it) }

	override var defaultSortOrder: SortOrder = SortOrder.POPULARITY

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(isSearchSupported = true)

	override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> = withContext(Dispatchers.IO) {
		if (offset == 0) {
			currentPage = 1
		} else if (offset > lastOffset) {
			currentPage += 1
		}
		lastOffset = offset
		val page = currentPage
		val query = filter?.query?.trim().orEmpty()
		val mangasPage = when {
			query.isNotEmpty() -> mihonSource.getSearchManga(page, query, FilterList())
			order == SortOrder.UPDATED && source.supportsLatest -> mihonSource.getLatestUpdates(page)
			else -> mihonSource.getPopularManga(page)
		}
		mangasPage.mangas.map { it.toManga(source) }
	}

	override suspend fun getDetailsImpl(manga: Manga): Manga = withContext(Dispatchers.IO) {
		val details = mihonSource.getMangaDetails(manga.toSManga())
		val chapters = mihonSource.getChapterList(details).map { it.toMangaChapter(source) }
		details.toManga(source, chapters).copy(id = manga.id)
	}

	override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> = withContext(Dispatchers.IO) {
		val pages = mihonSource.getPageList(chapter.toSChapter())
		pages.mapIndexed { index, page ->
			val mapped = page.toMangaPage(source, chapter.url)
			if (page.imageUrl.isNullOrBlank() && page.url.isNotBlank()) {
				mapped.copy(
					url = "mihon://resolve?page_url=${URLEncoder.encode(page.url, "UTF-8")}&index=$index",
				)
			} else {
				mapped
			}
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String = withContext(Dispatchers.IO) {
		val httpSource = mihonSource as? HttpSource ?: return@withContext page.url
		if (!page.url.startsWith("mihon://resolve")) {
			return@withContext page.url
		}
		val encoded = page.url.substringAfter("page_url=", "").substringBefore('&')
		val resolved = URLDecoder.decode(encoded, "UTF-8")
		httpSource.getImageUrl(eu.kanade.tachiyomi.source.model.Page(0, resolved))
	}

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> = emptyList()
}
