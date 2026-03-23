package org.koitharu.kotatsu.mihon

import android.util.Log
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.EnumSet

class MihonMangaRepository(
	override val source: MihonMangaSource,
	cache: MemoryContentCache,
) : CachingMangaRepository(cache) {

	companion object {
		private const val TAG = "MihonMangaRepository"
	}

	val mihonSource = source.catalogueSource
	private var lastOffset = -1
	private var currentPage = 1

	override val sortOrders: Set<SortOrder> = buildSet {
		add(SortOrder.POPULARITY)
		if (source.supportsLatest) add(SortOrder.UPDATED)
		add(SortOrder.RELEVANCE)
	}.let { EnumSet.copyOf(it) }

	override var defaultSortOrder: SortOrder = SortOrder.POPULARITY

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> = withContext(Dispatchers.IO) {
		if (offset == 0) {
			currentPage = 1
		} else if (offset > lastOffset) {
			currentPage += 1
		}
		lastOffset = offset
		val page = currentPage
		val query = filter?.query?.trim().orEmpty()

		val hasFilters = filter?.let {
			it.query?.isNotBlank() == true || it.tags.isNotEmpty() || it.tagsExclude.isNotEmpty()
		} ?: false

		val mangasPage = when {
			hasFilters -> {
				mihonSource.getSearchManga(page, query, filter?.toMihonFilterList() ?: FilterList())
			}
			order == SortOrder.UPDATED && source.supportsLatest -> mihonSource.getLatestUpdates(page)
			else -> mihonSource.getPopularManga(page)
		}

		val httpSource = mihonSource as? HttpSource
		mangasPage.mangas.map { sManga ->
			sManga.toManga(
				source = source,
				publicUrl = httpSource?.getMangaUrl(sManga).orEmpty(),
			)
		}
	}

	override suspend fun getDetailsImpl(manga: Manga): Manga = withContext(Dispatchers.IO) {
		val sManga = manga.toSManga()

		val details = try {
			mihonSource.getMangaDetails(sManga)
		} catch (e: Exception) {
			if (e is IOException || e.cause is IOException) {
				delay(500)
				mihonSource.getMangaDetails(sManga)
			} else {
				throw e
			}
		}

		val rawChapters = try {
			mihonSource.getChapterList(sManga)
		} catch (e: Exception) {
			if (e is IOException || e.cause is IOException) {
				delay(500)
				mihonSource.getChapterList(sManga)
			} else {
				throw e
			}
		}

		Log.d(TAG, "rawChapters count: ${rawChapters.size}, source: ${source.name}")

		// Reverse raw chapters (assuming newest-first from source) and assign virtual numbers
		val chapters = rawChapters.asReversed()
			.mapIndexed { index, sChapter ->
				val chapterNumber = if (sChapter.chapter_number > 0) {
					sChapter.chapter_number
				} else {
					(index + 1).toFloat()
				}
				sChapter.toMangaChapter(source, chapterNumber)
			}
			.sortedBy { it.number }

		// Fallback for missing details fields
		details.url = sManga.url

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

		val httpSource = mihonSource as? HttpSource
		val publicUrl = httpSource?.getMangaUrl(details).orEmpty()

		details.toManga(
			source = source,
			chapters = chapters,
			publicUrl = publicUrl,
		).copy(id = manga.id)
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

	override suspend fun getFilterOptions(): MangaListFilterOptions {
		val mihonFilters = try {
			mihonSource.getFilterList()
		} catch (e: Exception) {
			FilterList()
		}
		return MihonFilterMapper.mapOptions(mihonFilters, source)
	}

	private fun MangaListFilter.toMihonFilterList(): FilterList {
		val mihonFilters = try {
			mihonSource.getFilterList()
		} catch (e: Exception) {
			return FilterList()
		}
		MihonFilterMapper.updateMihonFilters(mihonFilters, this)
		return mihonFilters
	}

	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> = emptyList()
}
