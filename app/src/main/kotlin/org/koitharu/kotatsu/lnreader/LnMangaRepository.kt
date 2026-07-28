package org.koitharu.kotatsu.lnreader

import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.lnreader.js.JsException
import org.koitharu.kotatsu.lnreader.js.JsHost
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.lnreader.model.absoluteUrl
import org.koitharu.kotatsu.lnreader.model.toManga
import org.koitharu.kotatsu.lnreader.model.toMangaChapter
import org.koitharu.kotatsu.mihon.MihonFilterHost
import org.koitharu.kotatsu.mihon.MihonFilterMapper
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges an LNReader novel plugin onto [org.koitharu.kotatsu.core.parser.MangaRepository].
 *
 * A "page" here is a chapter of prose, not an image: [getPagesImpl] returns exactly one synthetic
 * [MangaPage] per chapter and the reader pulls the actual html through [getChapterHtml].
 */
@OptIn(InternalParsersApi::class)
class LnMangaRepository(
	override val source: LnMangaSource,
	cache: MemoryContentCache,
	private val jsHost: JsHost,
	private val pluginManager: LnPluginManager,
) : CachingMangaRepository(cache), MihonFilterHost {

	override val supportsDynamicFilters: Boolean
		get() = source.plugin.filters != null

	override suspend fun loadDefaultFilterList(): FilterList = LnFilterMapper.toFilterList(source.plugin.filters)

	// Copied from MihonMangaRepository rather than extracted: sharing it would mean editing that file,
	// and keeping novel paging independent of the Mihon adapter is worth eight duplicated lines.
	private class PaginationState {
		var currentPage = 1
		var lastOffset = -1

		@Volatile
		var hasMorePages = true
	}

	private val paginationStates = ConcurrentHashMap<String, PaginationState>()

	override val sortOrders: Set<SortOrder> =
		EnumSet.of(SortOrder.POPULARITY, SortOrder.UPDATED, SortOrder.RELEVANCE)

	override var defaultSortOrder: SortOrder = SortOrder.POPULARITY

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			// searchNovels(query, page) takes no filters, so the UI must not pretend they combine.
			isSearchWithFiltersSupported = false,
		)

	override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
		val state = paginationStates.getOrPut("$order|${filter?.query}|${filter?.tags}") { PaginationState() }
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
		if (offset > 0 && !state.hasMorePages) return emptyList()

		val query = filter?.query?.trim().orEmpty()
		val result = if (query.isNotEmpty()) {
			call("searchNovels", listOf(query, page))
		} else {
			val options = JSONObject().put("showLatestNovels", order == SortOrder.UPDATED)
			// The dynamic filter sheet hands its state back encoded in filter.tags, so decode it onto a
			// fresh default list and read the plugin's own value shape out of that.
			val working = LnFilterMapper.toFilterList(source.plugin.filters)
			if (filter != null && working.isNotEmpty()) {
				MihonFilterMapper.decode(working, filter)
				LnFilterMapper.toValuesJson(source.plugin.filters, working)?.let { options.put("filters", it) }
			}
			call("popularNovels", listOf(page, options))
		}
		val novels = (result as? JSONArray).objects()
		// LNReader plugins have no hasNextPage; an empty page is the only end-of-list signal.
		state.hasMorePages = novels.isNotEmpty()
		return novels.map { it.toManga(source) }
	}

	override suspend fun getDetailsImpl(manga: Manga): Manga {
		// Throwing rather than returning `manga` unchanged: the tracker treats a chapter list it did
		// not fetch as "nothing new", so a broken plugin would silently look like an up-to-date novel.
		val novel = call("parseNovel", listOf(manga.url)) as? JSONObject
			?: throw JsException("${source.pluginId}.parseNovel returned no novel for ${manga.url}")
		val chapters = novel.optJSONArray("chapters").objects().toMutableList()
		// parsePage exists only on plugins that paginate their chapter list. Sequential on purpose:
		// plugins rate-limit, and this runs behind the details spinner.
		if (source.plugin.hasParsePage) {
			val totalPages = novel.optInt("totalPages", 1).coerceAtMost(MAX_CHAPTER_PAGES)
			for (pageIndex in 2..totalPages) {
				val page = call("parsePage", listOf(manga.url, pageIndex.toString())) as? JSONObject ?: break
				val pageChapters = page.optJSONArray("chapters").objects()
				if (pageChapters.isEmpty()) break
				chapters += pageChapters
			}
		}
		// LNReader returns chapters oldest-first, which is already Kotatsu's order — do NOT apply
		// normalizeMihonChapterOrder here, the Mihon adapter two directories over does the opposite.
		val mapped = chapters
			.distinctBy { it.optString("path") }
			.mapIndexed { index, chapter -> chapter.toMangaChapter(source, index + 1) }
		return novel.toManga(source, mapped, details = true).copy(id = manga.id)
	}

	/**
	 * One synthetic page per chapter, mirroring `LocalMangaParser`'s epub handling: an empty page list
	 * makes `ReaderViewModel` abort the reader. The html is deliberately NOT fetched here.
	 */
	override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> = listOf(
		MangaPage(id = chapter.id, url = chapter.url, preview = null, source = source),
	)

	override suspend fun getPageUrl(page: MangaPage): String = page.url

	override suspend fun getChapterHtml(chapter: MangaChapter): String? =
		call("parseChapter", listOf(chapter.url)) as? String

	/**
	 * The download worker treats this as "the source fetches this itself", so handing it a synthetic
	 * response carrying the chapter html lets a novel download with no changes to the worker at all.
	 */
	override suspend fun getImageStream(pageUrl: String, page: MangaPage): Response? {
		val html = call("parseChapter", listOf(page.url)) as? String ?: return null
		return Response.Builder()
			.request(Request.Builder().url(source.absoluteUrl(page.url)).build())
			.protocol(Protocol.HTTP_1_1)
			.code(200)
			.message("OK")
			.body(html.toResponseBody("application/xhtml+xml".toMediaType()))
			.build()
	}

	/**
	 * `resolveUrl` is optional in the plugin contract, and chapter paths are already site-relative, so
	 * falling back to resolving the path keeps "open in browser" working on every plugin instead of
	 * only the few that implement it.
	 */
	override suspend fun getChapterUrl(chapter: MangaChapter): String? {
		if (source.plugin.hasResolveUrl) {
			pluginManager.ensureLoaded(source.pluginId)
			jsHost.resolveUrl(source.pluginId, chapter.url, false)
				.takeIf { it.isNotEmpty() }
				?.let { return it }
		}
		return source.absoluteUrl(chapter.url).takeIf { it.startsWith("http") }
	}

	/** Plugins expose no related-novels API. */
	override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> = emptyList()

	override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions()

	private suspend fun call(fn: String, args: List<Any?>): Any? = withContext(Dispatchers.IO) {
		pluginManager.ensureLoaded(source.pluginId)
		jsHost.call(source.pluginId, fn, args)
	}

	private companion object {

		/** ponytail: hard cap so a plugin reporting a nonsense totalPages cannot hang the details load. */
		const val MAX_CHAPTER_PAGES = 200
	}
}

private fun JSONArray?.objects(): List<JSONObject> {
	if (this == null) return emptyList()
	return (0 until length()).mapNotNull { optJSONObject(it) }
}
