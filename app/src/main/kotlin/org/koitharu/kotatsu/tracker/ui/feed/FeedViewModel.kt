package org.koitharu.kotatsu.tracker.ui.feed

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.toChipModel
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.model.DateTimeAgo
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.groupByDateBucket
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.domain.QuickFilterListener
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.QuickFilter
import org.koitharu.kotatsu.list.ui.model.TipModel
import org.koitharu.kotatsu.list.ui.model.toErrorState
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.tracker.domain.TrackingRepository
import org.koitharu.kotatsu.tracker.domain.UpdatesListQuickFilter
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedSection
import org.koitharu.kotatsu.tracker.work.TrackWorker
import org.koitharu.kotatsu.tracker.work.TrackerWorkState
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val PAGE_SIZE = 20

data object FeedUpdatedBar : ListModel {
	override fun areItemsTheSame(other: ListModel): Boolean = other === FeedUpdatedBar
}

@HiltViewModel
class FeedViewModel @Inject constructor(
	private val settings: AppSettings,
	private val repository: TrackingRepository,
	private val scheduler: TrackWorker.Scheduler,
	private val mangaListMapper: MangaListMapper,
	private val quickFilter: UpdatesListQuickFilter,
	private val historyRepository: HistoryRepository,
	mihonExtensionManager: MihonExtensionManager,
) : BaseViewModel(), QuickFilterListener by quickFilter {

	init {
		quickFilter.isStateFilterEnabled = false
		if (settings.isFeedUnreadOnly) {
			quickFilter.setFilterOption(ListFilterOption.Unread, true)
		}
		quickFilter.appliedOptions
			.onEach { settings.isFeedUnreadOnly = ListFilterOption.Unread in it }
			.launchIn(viewModelScope)
	}

	private val limit = MutableStateFlow(PAGE_SIZE)
	private val isReady = AtomicBoolean(false)
	private val expandedIds = MutableStateFlow<Set<Long>>(emptySet())
	private val pendingRemovedIds = MutableStateFlow<Set<Long>>(emptySet())
	private val _undoEpoch = MutableStateFlow(0)
	val undoEpoch: StateFlow<Int> = _undoEpoch.asStateFlow()

	@Volatile
	private var hasMoreItems = false

	@Volatile
	private var latestMappedItems: List<FeedItem> = emptyList()

	val workState = scheduler.observeWorkState()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, TrackerWorkState.IDLE)

	val isRunning = workState
		.map { it.isRunning }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	val unreadCount = repository.observeUnreadUpdatesCount()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, 0)

	val totalCount = repository.observeLogsCount()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, 0)

	val lastCheckTimeMillis = repository.observeLastCheckTime()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, 0L)

	val onActionDone = MutableEventFlow<ReversibleAction>()

	val isSwipeGesturesEnabled = settings.observeAsFlow(AppSettings.KEY_FEED_SWIPE_GESTURES) {
		isFeedSwipeGesturesEnabled
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, settings.isFeedSwipeGesturesEnabled)

	private val baseSlice: Flow<FeedBaseSlice> = combine(
		combine(limit, quickFilter.appliedOptions.combineWithSettings(), ::Pair)
			.flatMapLatest { (currentLimit, queryFilters) ->
				repository.observeTrackingLog(currentLimit, queryFilters).map { rows ->
					currentLimit to rows
				}
			},
		combine(
			settings.observeAsFlow(AppSettings.KEY_TIPS_CLOSED) { isTipEnabled(TIP_GESTURES) },
			isSwipeGesturesEnabled,
		) { tip, swipe -> tip && swipe },
		historyRepository.observeAll(),
		mihonExtensionManager.isReady,
	) { (currentLimit, rawList), isTipVisible, _, _ ->
		val filters = quickFilter.appliedOptions.value
		val isUnreadOnly = ListFilterOption.Unread in filters
		val mapped = rawList
			.map { it.createdAt to mangaListMapper.toFeedItem(it) }
			.filter { !isUnreadOnly || it.second.isNew }
		val hasMore = rawList.size >= currentLimit
		hasMoreItems = hasMore
		latestMappedItems = mapped.map { it.second }
		// If an entire SQL page was filtered out in memory while more rows exist, pull the next page automatically.
		if (mapped.isEmpty() && hasMore && currentLimit < Int.MAX_VALUE) {
			isReady.set(false)
			limit.value = (currentLimit.toLong() + PAGE_SIZE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
		} else {
			isReady.set(hasMore)
		}
		FeedBaseSlice(
			rawCount = rawList.size,
			hasMore = hasMore,
			isTipVisible = isTipVisible,
			filters = filters,
			quickFilterChips = quickFilter.filterItem(filters)?.items.orEmpty(),
			items = mapped,
		)
	}

	val content = combine(
		baseSlice,
		expandedIds,
		unreadCount,
		pendingRemovedIds,
	) { slice, expanded, unread, removedIds ->
		buildFeedList(slice, expanded, unread, removedIds)
	}.distinctUntilChanged().catch { e ->
		emit(listOf(e.toErrorState(canRetry = false)))
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	init {
		launchJob(Dispatchers.Default) {
			repository.gc()
		}
	}

	fun clearFeed(clearCounters: Boolean) {
		launchLoadingJob(Dispatchers.Default) {
			repository.clearLogs()
			pendingRemovedIds.value = emptySet()
			if (clearCounters) {
				repository.clearCounters()
			}
			onActionDone.call(ReversibleAction(R.string.updates_feed_cleared, null))
		}
	}

	fun clearReadEntries() {
		val optimisticIds = latestMappedItems.filterNot { it.isNew }.mapTo(HashSet()) { it.id }
		if (optimisticIds.isNotEmpty()) {
			pendingRemovedIds.update { it + optimisticIds }
		}
		launchJob(Dispatchers.Default) {
			val rawHandle = repository.clearReadLogs()
			if (rawHandle == null) {
				if (optimisticIds.isNotEmpty()) {
					pendingRemovedIds.update { it - optimisticIds }
				}
				return@launchJob
			}
			onActionDone.call(ReversibleAction(R.string.updates_feed_cleared, wrapUndo(rawHandle, optimisticIds)))
		}
	}

	fun clearFilters() {
		val active = quickFilter.appliedOptions.value
		for (option in active) {
			quickFilter.setFilterOption(option, false)
		}
	}

	fun requestMoreItems() {
		if (hasMoreItems && isReady.compareAndSet(true, false)) {
			limit.value = (limit.value.toLong() + PAGE_SIZE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
		}
	}

	fun requestAllItems() {
		if (hasMoreItems && limit.value != Int.MAX_VALUE) {
			isReady.set(false)
			limit.value = Int.MAX_VALUE
		}
	}

	val canLoadMore: Boolean
		get() = hasMoreItems

	fun allLoadedFeedIds(): List<Long> {
		val removed = pendingRemovedIds.value
		return latestMappedItems.mapNotNull { item -> item.id.takeUnless { it in removed } }
	}

	fun update() {
		scheduler.startNow()
	}

	fun stopUpdate() {
		launchJob(Dispatchers.Default) {
			scheduler.stopNow()
		}
	}

	fun markAsRead(item: FeedItem) {
		launchJob(Dispatchers.Default) {
			val handle = wrapUndo(repository.markLogsRead(item.manga.id))
			onActionDone.call(ReversibleAction(R.string.marked_as_read, handle))
		}
	}

	fun markAsRead(ids: Set<Long>) {
		launchJob(Dispatchers.Default) {
			val rawHandle = repository.markLogsReadByIds(ids) ?: return@launchJob
			onActionDone.call(ReversibleAction(R.string.marked_as_read, wrapUndo(rawHandle)))
		}
	}

	fun markAllAsRead() {
		launchJob(Dispatchers.Default) {
			val rawHandle = repository.markAllLogsRead() ?: return@launchJob
			onActionDone.call(ReversibleAction(R.string.marked_as_read, wrapUndo(rawHandle)))
		}
	}

	fun remove(ids: Set<Long>) {
		if (ids.isEmpty()) return
		pendingRemovedIds.update { it + ids }
		launchJob(Dispatchers.Default) {
			val handles = ids.mapNotNull { repository.removeLog(it) }
			if (handles.isEmpty()) {
				pendingRemovedIds.update { it - ids }
				return@launchJob
			}
			val combined = ReversibleHandle { handles.forEach { it.reverse() } }
			onActionDone.call(ReversibleAction(R.string.feed_entry_removed, wrapUndo(combined, ids)))
		}
	}

	fun remove(item: FeedItem) {
		val idSet = setOf(item.id)
		pendingRemovedIds.update { it + item.id }
		launchJob(Dispatchers.Default) {
			val rawHandle = repository.removeLog(item.id)
			if (rawHandle == null) {
				pendingRemovedIds.update { it - item.id }
				_undoEpoch.update { it + 1 }
				return@launchJob
			}
			onActionDone.call(ReversibleAction(R.string.feed_entry_removed, wrapUndo(rawHandle, idSet)))
		}
	}

	private fun wrapUndo(
		delegate: ReversibleHandle,
		restoredIds: Set<Long> = emptySet(),
	) = ReversibleHandle {
		delegate.reverse()
		if (restoredIds.isNotEmpty()) {
			pendingRemovedIds.update { it - restoredIds }
		}
		_undoEpoch.update { it + 1 }
	}

	fun dismissGesturesTip() {
		settings.closeTip(TIP_GESTURES)
	}

	fun toggleExpanded(item: FeedItem) {
		expandedIds.update { current ->
			if (item.id in current) current - item.id else current + item.id
		}
	}

	fun collapseAll() {
		expandedIds.update { if (it.isEmpty()) it else emptySet() }
	}

	private fun buildFeedList(
		slice: FeedBaseSlice,
		expanded: Set<Long>,
		unread: Int,
		removedIds: Set<Long>,
	): List<ListModel> {
		val visibleItems = if (removedIds.isEmpty()) {
			slice.items
		} else {
			slice.items.filterNot { it.second.id in removedIds }
		}
		val result = ArrayList<ListModel>((visibleItems.size * 1.35).toInt().coerceAtLeast(4))
		if (visibleItems.isNotEmpty() && slice.isTipVisible) {
			result += gesturesTip
		}
		val unreadChip = ListFilterOption.Unread
			.toChipModel(isChecked = ListFilterOption.Unread in slice.filters)
			.copy(counter = unread)
		result += QuickFilter(listOf(unreadChip) + slice.quickFilterChips)
		result += FeedUpdatedBar

		if (visibleItems.isEmpty()) {
			val hasFilters = slice.filters.isNotEmpty()
			result += EmptyState(
				icon = R.drawable.ic_empty_feed,
				textPrimary = R.string.text_feed_holder_primary,
				textSecondary = if (hasFilters) R.string.text_empty_holder_secondary_filtered else R.string.text_feed_holder,
				actionStringRes = if (hasFilters) R.string.show_all else R.string.update,
			)
			return result
		}

		val zone = ZoneId.systemDefault()
		val bucketed = visibleItems.groupByDateBucket(instantOf = { it.first })
		val usedKeys = HashSet<String>(bucketed.size)
		bucketed.forEachIndexed { bucketIndex, (date, pairs) ->
			val baseKey = resolveSectionKey(date, pairs.first().first, zone)
			val key = if (usedKeys.add(baseKey)) baseKey else "$baseKey#$bucketIndex"
			result += FeedSection(key = key, date = date)
			val lastIndex = pairs.lastIndex
			pairs.forEachIndexed { index, (_, feedItem) ->
				val position = when {
					pairs.size == 1 -> FeedItem.GroupPosition.SINGLE
					index == 0 -> FeedItem.GroupPosition.FIRST
					index == lastIndex -> FeedItem.GroupPosition.LAST
					else -> FeedItem.GroupPosition.MIDDLE
				}
				result += feedItem.copy(
					groupPosition = position,
					isExpanded = feedItem.id in expanded,
				)
			}
		}
		return result
	}

	private fun resolveSectionKey(date: DateTimeAgo?, sampleInstant: Instant?, zone: ZoneId): String = when (date) {
		null -> KEY_UNKNOWN
		DateTimeAgo.JustNow -> KEY_JUST_NOW
		else -> if (sampleInstant != null) {
			"d:" + LocalDateTime.ofInstant(sampleInstant, zone).toLocalDate()
		} else {
			date.toString()
		}
	}

	private fun Flow<Set<ListFilterOption>>.combineWithSettings(): Flow<Set<ListFilterOption>> = combine(
		settings.observeAsFlow(AppSettings.KEY_CONTENT_FILTER) { isNsfwContentDisabled },
	) { filters, skipNsfw ->
		if (skipNsfw) {
			filters + ListFilterOption.SFW
		} else {
			filters
		}
	}

	private data class FeedBaseSlice(
		val rawCount: Int,
		val hasMore: Boolean,
		val isTipVisible: Boolean,
		val filters: Set<ListFilterOption>,
		val quickFilterChips: List<ChipsView.ChipModel>,
		val items: List<Pair<Instant?, FeedItem>>,
	)

	private companion object {

		const val TIP_GESTURES = "feed_gestures"
		const val KEY_UNKNOWN = "unknown"
		const val KEY_JUST_NOW = "just_now"

		val gesturesTip = TipModel(
			key = TIP_GESTURES,
			title = R.string.feed_gestures_tip_title,
			text = R.string.feed_gestures_tip,
			icon = R.drawable.ic_gesture_horizontal,
			primaryButtonText = 0,
			secondaryButtonText = 0,
			isClosable = true,
		)
	}
}
