package org.koitharu.kotatsu.history.ui

import androidx.collection.LongSet
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.withOverride
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.prefs.observeAsStateFlow
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.flattenLatest
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.history.data.HistorySectionDates
import org.koitharu.kotatsu.history.domain.HistoryListQuickFilter
import org.koitharu.kotatsu.history.domain.MarkAsReadUseCase
import org.koitharu.kotatsu.history.domain.model.MangaWithHistory
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.domain.QuickFilterListener
import org.koitharu.kotatsu.list.ui.MangaListViewModel
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.TIP_UI_SCALING
import org.koitharu.kotatsu.list.ui.model.toErrorState
import org.koitharu.kotatsu.list.ui.model.uiScalingTip
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.tracker.domain.TrackingRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val PAGE_SIZE = 16

@HiltViewModel
class HistoryListViewModel @Inject constructor(
	private val repository: HistoryRepository,
	private val settings: AppSettings,
	private val mangaListMapper: MangaListMapper,
	private val markAsReadUseCase: MarkAsReadUseCase,
	private val quickFilter: HistoryListQuickFilter,
	private val mihonExtensionManager: MihonExtensionManager,
	private val dataRepository: MangaDataRepository,
	trackingRepository: TrackingRepository,
	@LocalStorageChanges localStorageChanges: SharedFlow<LocalManga?>,
) : MangaListViewModel(settings, dataRepository, localStorageChanges), QuickFilterListener by quickFilter {

	private val sortOrder: StateFlow<ListSortOrder> = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.IO,
		key = AppSettings.KEY_HISTORY_ORDER,
		valueProducer = { historySortOrder },
	)

	override val listMode = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_LIST_MODE_HISTORY,
		valueProducer = { historyListMode },
	)

	private val isGroupingSettingEnabled = settings.observeAsFlow(
		key = AppSettings.KEY_HISTORY_GROUPING,
		valueProducer = { isHistoryGroupingEnabled },
	)

	private val limit = MutableStateFlow(PAGE_SIZE)
	private val isPaginationReady = AtomicBoolean(false)

	/** Entries of the folded last section that aren't loaded yet; the next page skips past them in one go. */
	@Volatile
	private var foldedTail = 0

	/** Latest loaded slice, so actions on a folded or still-diffing section still find every [Manga]. */
	@Volatile
	private var currentHistory: List<MangaWithHistory> = emptyList()

	/** Rows mapped so far; a filter, fold or count change reuses them instead of re-reading every badge. */
	private var mappedCache: MappedRows? = null
	private var countsCache: CachedCounts? = null

	private val collapsedSections = settings.observeAsFlow(
		key = AppSettings.KEY_HISTORY_COLLAPSED,
		valueProducer = { historyCollapsedSections },
	)

	/** Dates across the whole matching history (no page limit), paired with the filters that produced them. */
	private val sectionDates: Flow<Pair<Set<ListFilterOption>, List<HistorySectionDates>>?> = combine(
		isGroupingSettingEnabled,
		quickFilter.appliedOptions.combineWithSettings(),
	) { grouped, filters ->
		grouped to filters
	}.flatMapLatest { (grouped, filters) ->
		// Downloaded entries are matched against local storage, which the database can't count.
		if (!grouped || ListFilterOption.Downloaded in filters) {
			flowOf(null)
		} else {
			repository.observeSectionDates(filters).map { rows -> filters to rows }
		}
	}

	val isStatsEnabled = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_STATS_ENABLED,
		valueProducer = { isStatsEnabled },
	)

	override val content = combine(
		observeHistory(),
		isGroupingSettingEnabled,
		// Wrapped so a re-emit from a trigger (e.g. an edited cover, or the tracker finding new chapters)
		// is told apart and remaps the rows.
		combine(observeListModeWithTriggers(), trackingRepository.observeTracksChanged()) { mode, _ ->
			ModeEmission(mode)
		},
		settings.observeAsFlow(AppSettings.KEY_TIPS_CLOSED) { isTipEnabled(TIP_UI_SCALING) },
		combine(sectionDates, collapsedSections, ::Pair),
	) { slice, groupingSetting, mode, isScalingTipVisible, (dates, collapsed) ->
		val grouped = groupingSetting && slice.order.isGroupingSupported()
		val matchingDates = dates?.takeIf { it.first == slice.allFilters }?.second
		mapList(slice, grouped, mode, isScalingTipVisible, matchingDates, collapsed)
	}.onEach {
		isPaginationReady.set(true)
	}.distinctUntilChanged().catch { e ->
		emit(listOf(e.toErrorState(canRetry = false)))
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	override fun onRefresh() = Unit

	override fun onRetry() = Unit

	/** Shared with Favourites: dismissing it on either screen dismisses it on both. */
	fun dismissScalingTip() {
		settings.closeTip(TIP_UI_SCALING)
	}

	fun clearHistory(minDate: Instant?) {
		launchJob(Dispatchers.Default) {
			val stringRes = if (minDate == null) {
				repository.clear()
				R.string.history_cleared
			} else {
				repository.deleteAfter(minDate.toEpochMilli())
				R.string.removed_from_history
			}
			onActionDone.call(ReversibleAction(stringRes, null))
		}
	}

	fun removeNotFavorite() {
		launchJob(Dispatchers.Default) {
			repository.deleteNotFavorite()
			onActionDone.call(ReversibleAction(R.string.removed_from_history, null))
		}
	}

	fun removeFromHistory(ids: Set<Long>) {
		if (ids.isEmpty()) {
			return
		}
		launchJob(Dispatchers.Default) {
			val handle = repository.delete(ids)
			onActionDone.call(ReversibleAction(R.string.removed_from_history, handle))
		}
	}

	fun markAsRead(items: Set<Manga>, isRead: Boolean) {
		launchLoadingJob(Dispatchers.Default) {
			markAsReadUseCase(items, isRead)
		}
	}

	fun requestMoreItems() {
		if (limit.value != Int.MAX_VALUE && isPaginationReady.compareAndSet(true, false)) {
			limit.value = (limit.value.toLong() + PAGE_SIZE + foldedTail).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
		}
	}

	/** Loads the rest of a section that's only partly loaded (only the last one can be). */
	fun requestRestOf(section: HistorySection) {
		val missing = if (section.isCountExact) section.count - section.itemIds.size else PAGE_SIZE
		if (missing > 0 && limit.value != Int.MAX_VALUE) {
			limit.value = (limit.value.toLong() + missing).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
		}
	}

	fun getLoadedManga(ids: LongSet): Set<Manga> {
		val snapshot = currentHistory
		if (snapshot.isEmpty() || ids.isEmpty()) return emptySet()
		val result = LinkedHashSet<Manga>(ids.size)
		for (item in snapshot) {
			if (item.manga.id in ids) {
				result.add(item.manga)
			}
		}
		return result
	}

	fun setSectionCollapsed(key: String, isCollapsed: Boolean) {
		val current = settings.historyCollapsedSections
		if ((key in current) != isCollapsed) {
			settings.historyCollapsedSections = if (isCollapsed) current + key else current - key
		}
	}

	override fun requestAllItems() {
		limit.value = Int.MAX_VALUE
	}

	private fun observeHistory() = combine(
		sortOrder,
		quickFilter.appliedOptions,
		settings.observeAsFlow(AppSettings.KEY_CONTENT_FILTER) { isNsfwContentDisabled },
		limit,
		// Query again once extensions are loaded: entries read before that carry an unresolved source.
		mihonExtensionManager.isReady,
	) { order, userFilters, skipNsfw, limit, _ ->
		val allFilters = if (skipNsfw) userFilters + ListFilterOption.SFW else userFilters
		isPaginationReady.set(false)
		repository.observeAllWithHistory(order, allFilters, limit).map { list ->
			HistorySlice(list, order, userFilters, allFilters, limit)
		}
	}.flattenLatest()

	private suspend fun mapList(
		slice: HistorySlice,
		grouped: Boolean,
		mode: ModeEmission,
		isScalingTipVisible: Boolean,
		sectionDates: List<HistorySectionDates>?,
		collapsed: Set<String>,
	): List<ListModel> {
		val list = slice.list
		val filters = slice.userFilters
		currentHistory = list
		foldedTail = 0
		if (list.isEmpty()) {
			return if (filters.isEmpty()) {
				listOf(getEmptyState(hasFilters = false))
			} else {
				listOfNotNull(quickFilter.filterItem(filters), getEmptyState(hasFilters = true))
			}
		}
		val result = ArrayList<ListModel>(list.size + 8)
		if (isScalingTipVisible) {
			result += uiScalingTip
		}
		quickFilter.filterItem(filters)?.let(result::add)
		val cache = mappedCache?.takeIf { it.mode === mode } ?: MappedRows(mode)
		mappedCache = cache
		suspend fun row(item: MangaWithHistory): ListModel {
			cache.rows[item.manga.id]?.let { (mapped, model) -> if (mapped == item) return model }
			return mangaListMapper.toListModel(item.manga, mode.mode).also { cache.rows[item.manga.id] = item to it }
		}
		if (!grouped) {
			list.mapTo(result) { row(it) }
			return result
		}
		val overrides = dataRepository.getOverrides()
		val resolver = HistorySectionResolver(slice.order.type)
		val counts = sectionDates?.let { resolveCounts(it, resolver) }
		val keys = list.map { (_, h) -> resolver.resolve(h.createdAt.toEpochMilli(), h.updatedAt.toEpochMilli(), h.percent) }
		val hasMore = list.size >= slice.limit
		var start = 0
		while (start < list.size) {
			val key = keys[start]
			var end = start + 1
			while (end < list.size && keys[end] == key) end++
			val section = list.subList(start, end)
			val isLast = end == list.size
			val isCollapsed = key != null && key in collapsed
			if (key != null) {
				val total = counts?.get(key)
				result += HistorySection(
					key = key,
					count = maxOf(total ?: 0, section.size),
					isCountExact = total != null || !(isLast && hasMore),
					isCollapsed = isCollapsed,
					itemIds = section.map { it.manga.id },
					previews = section.take(3).map { it.manga.withOverride(overrides[it.manga.id]) },
				)
				if (isLast && isCollapsed && total != null) {
					foldedTail = (total - section.size).coerceAtLeast(0)
				}
			}
			if (!isCollapsed) {
				section.mapTo(result) { row(it) }
			}
			start = end
		}
		return result
	}

	private fun resolveCounts(rows: List<HistorySectionDates>, resolver: HistorySectionResolver): Map<String, Int> {
		countsCache?.takeIf {
			it.rows === rows && it.type == resolver.type && it.today == resolver.today && it.zone == resolver.zone
		}?.let { return it.counts }
		val counts = HashMap<String, Int>()
		for (row in rows) {
			val key = resolver.resolve(row.createdAt, row.updatedAt, row.percent) ?: continue
			counts[key] = (counts[key] ?: 0) + 1
		}
		countsCache = CachedCounts(rows, resolver.type, resolver.today, resolver.zone, counts)
		return counts
	}

	private fun getEmptyState(hasFilters: Boolean) = if (hasFilters) {
		EmptyState(
			icon = R.drawable.ic_empty_history,
			textPrimary = R.string.nothing_found,
			textSecondary = R.string.text_empty_holder_secondary_filtered,
			actionStringRes = R.string.reset_filter,
		)
	} else {
		EmptyState(
			icon = R.drawable.ic_empty_history,
			textPrimary = R.string.text_history_holder_primary,
			textSecondary = R.string.text_history_holder_secondary,
			actionStringRes = 0,
		)
	}
}

private data class HistorySlice(
	val list: List<MangaWithHistory>,
	val order: ListSortOrder,
	val userFilters: Set<ListFilterOption>,
	val allFilters: Set<ListFilterOption>,
	val limit: Int,
)

private class ModeEmission(val mode: ListMode)

/** Mapped rows per manga id, with the entry they were mapped from; a new mode emission starts over. */
private class MappedRows(val mode: ModeEmission) {
	val rows = HashMap<Long, Pair<MangaWithHistory, ListModel>>()
}

private class CachedCounts(
	val rows: List<HistorySectionDates>,
	val type: ListSortOrder.Type,
	val today: LocalDate,
	val zone: ZoneId,
	val counts: Map<String, Int>,
)
