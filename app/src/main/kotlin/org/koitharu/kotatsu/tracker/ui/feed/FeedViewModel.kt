package org.koitharu.kotatsu.tracker.ui.feed

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toMangaChapters
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.groupByDateBucket
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.domain.QuickFilterListener
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.TipModel
import org.koitharu.kotatsu.list.ui.model.toErrorState
import org.koitharu.kotatsu.tracker.domain.TrackingRepository
import org.koitharu.kotatsu.tracker.domain.UpdatesListQuickFilter
import org.koitharu.kotatsu.tracker.domain.model.TrackingLogItem
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem
import org.koitharu.kotatsu.tracker.work.TrackWorker
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val PAGE_SIZE = 20

@HiltViewModel
class FeedViewModel @Inject constructor(
	private val settings: AppSettings,
	private val repository: TrackingRepository,
	private val scheduler: TrackWorker.Scheduler,
	private val mangaListMapper: MangaListMapper,
	private val quickFilter: UpdatesListQuickFilter,
	private val historyRepository: HistoryRepository,
	private val db: MangaDatabase,
) : BaseViewModel(), QuickFilterListener by quickFilter {

	init {
		quickFilter.isStateFilterEnabled = false
	}

	private val limit = MutableStateFlow(PAGE_SIZE)
	private val isReady = AtomicBoolean(false)
	private val expandedIds = MutableStateFlow<Set<Long>>(emptySet())

	val isRunning = scheduler.observeIsRunning()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

	val onActionDone = MutableEventFlow<ReversibleAction>()

	val isSwipeGesturesEnabled = settings.observeAsFlow(AppSettings.KEY_FEED_SWIPE_GESTURES) {
		isFeedSwipeGesturesEnabled
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, settings.isFeedSwipeGesturesEnabled)

	@Suppress("USELESS_CAST")
	val content = combine(
		combine(limit, quickFilter.appliedOptions.combineWithSettings(), ::Pair)
			.flatMapLatest { repository.observeTrackingLog(it.first, it.second) },
		combine(
			settings.observeAsFlow(AppSettings.KEY_TIPS_CLOSED) { isTipEnabled(TIP_GESTURES) },
			isSwipeGesturesEnabled,
		) { tip, swipe -> tip && swipe },
		expandedIds,
		historyRepository.observeAll(),
	) { list, isTipVisible, expanded, _ ->
		// Read here rather than combined in: the query above already re-runs on every filter change, and
		// a second input would render the chips one frame ahead of their results.
		val filters = quickFilter.appliedOptions.value
		val result = ArrayList<ListModel>((list.size * 1.4).toInt().coerceAtLeast(3))
		if (list.isNotEmpty() && isTipVisible) {
			result += gesturesTip
		}
		quickFilter.filterItem(filters)?.let(result::add)
		if (list.isEmpty()) {
			result += EmptyState(
				icon = R.drawable.ic_empty_feed,
				textPrimary = R.string.text_feed_holder_primary,
				textSecondary = R.string.text_feed_holder,
				actionStringRes = 0,
			)
		} else {
			isReady.set(true)
			list.mapListTo(result, expanded)
		}
		result as List<ListModel>
	}.catch { e ->
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
			if (clearCounters) {
				repository.clearCounters()
			}
			onActionDone.call(ReversibleAction(R.string.updates_feed_cleared, null))
		}
	}

	fun requestMoreItems() {
		if (isReady.compareAndSet(true, false)) {
			limit.value += PAGE_SIZE
		}
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
			val handle = markAsReadImpl(item)
			onActionDone.call(ReversibleAction(R.string.marked_as_read, handle))
		}
	}

	fun markAsRead(ids: Set<Long>) {
		launchJob(Dispatchers.Default) {
			val items = content.value.filterIsInstance<FeedItem>().filter { it.id in ids }
			if (items.isEmpty()) return@launchJob
			val handles = items.map { markAsReadImpl(it) }
			onActionDone.call(
				ReversibleAction(R.string.marked_as_read, ReversibleHandle { handles.forEach { it.reverse() } }),
			)
		}
	}

	fun remove(ids: Set<Long>) {
		launchJob(Dispatchers.Default) {
			val handles = ids.mapNotNull { repository.removeLog(it) }
			if (handles.isEmpty()) return@launchJob
			onActionDone.call(
				ReversibleAction(R.string.feed_entry_removed, ReversibleHandle { handles.forEach { it.reverse() } }),
			)
		}
	}

	private suspend fun markAsReadImpl(item: FeedItem): ReversibleHandle {
		// Snapshot undo state before anything mutates: the history jump below re-computes the
		// new-chapters counter to zero, so markLogsRead must capture it first.
		val priorHistory = db.getHistoryDao().find(item.manga.id)
		val logsHandle = repository.markLogsRead(item.manga.id)

		val allChapters = db.getChaptersDao().findAll(item.manga.id)
		val latestFeedChapterEntity = item.chapters
			.mapNotNull { ch -> allChapters.find { it.chapterId == ch.id } }
			.maxByOrNull { it.index }

		if (latestFeedChapterEntity != null) {
			val chapterIndex = allChapters.indexOfFirst { it.chapterId == latestFeedChapterEntity.chapterId }
			val percent = (chapterIndex + 1) / allChapters.size.toFloat()

			val mangaChapters = allChapters.toMangaChapters()
			val mangaWithChapters = item.manga.copy(chapters = mangaChapters)

			historyRepository.addOrUpdate(
				manga = mangaWithChapters,
				chapterId = latestFeedChapterEntity.chapterId,
				page = 0,
				scroll = 0,
				percent = percent,
				force = true,
			)
		}

		return ReversibleHandle {
			// Restore the reading position first: the feed dot derives per-chapter "new" state
			// from history, so restoring the unread flags alone leaves the row looking read.
			if (priorHistory != null) {
				db.getHistoryDao().upsert(priorHistory)
			} else {
				db.getHistoryDao().delete(item.manga.id)
			}
			logsHandle.reverse()
		}
	}

	fun remove(item: FeedItem) {
		launchJob(Dispatchers.Default) {
			val handle = repository.removeLog(item.id) ?: return@launchJob
			onActionDone.call(ReversibleAction(R.string.feed_entry_removed, handle))
		}
	}

	fun dismissGesturesTip() {
		settings.closeTip(TIP_GESTURES)
	}

	fun toggleExpanded(item: FeedItem) {
		expandedIds.update { current ->
			if (item.id in current) current - item.id else current + item.id
		}
	}

	// Collapses any expanded "N new chapters" entries back to their summary form, called when the
	// feed screen is left so the expansion never survives a return visit.
	fun collapseAll() {
		expandedIds.update { if (it.isEmpty()) it else emptySet() }
	}

	private suspend fun List<TrackingLogItem>.mapListTo(destination: MutableList<ListModel>, expandedIds: Set<Long>) {
		val feedItems = map { mangaListMapper.toFeedItem(it) }
		val bucketedItems = zip(feedItems).groupByDateBucket(instantOf = { it.first.createdAt })
		for ((date, items) in bucketedItems) {
			destination += if (date != null) {
				ListHeader(date)
			} else {
				ListHeader(R.string.unknown)
			}
			val lastIndex = items.lastIndex
			items.forEachIndexed { index, (_, feedItem) ->
				val position = when {
					items.size == 1 -> FeedItem.GroupPosition.SINGLE
					index == 0 -> FeedItem.GroupPosition.FIRST
					index == lastIndex -> FeedItem.GroupPosition.LAST
					else -> FeedItem.GroupPosition.MIDDLE
				}
				destination += feedItem.copy(groupPosition = position, isExpanded = feedItem.id in expandedIds)
			}
		}
	}

	private fun Flow<Set<ListFilterOption>>.combineWithSettings(): Flow<Set<ListFilterOption>> = combine(
		settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled },
	) { filters, skipNsfw ->
		if (skipNsfw) {
			filters + ListFilterOption.SFW
		} else {
			filters
		}
	}

	private companion object {

		const val TIP_GESTURES = "feed_gestures"

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
