package org.koitharu.kotatsu.stats.ui.sheet

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.model.DateTimeAgo
import org.koitharu.kotatsu.core.util.ext.calculateTimeAgo
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.stats.data.StatsRepository
import org.koitharu.kotatsu.stats.domain.StatsBucket
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class MangaStatsViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val repository: StatsRepository,
) : BaseViewModel() {

	val manga = savedStateHandle.require<ParcelableManga>(AppRouter.KEY_MANGA).manga

	/** Pages read per day, one bucket per day, capped to the window the chart can actually show. */
	val buckets = MutableStateFlow<List<StatsBucket>>(emptyList())
	val startDate = MutableStateFlow<DateTimeAgo?>(null)
	val totalPagesRead = MutableStateFlow(0)
	val daysRead = MutableStateFlow(0)

	init {
		launchLoadingJob(Dispatchers.Default) {
			val zone = ZoneId.systemDefault()
			val timeline = repository.getMangaTimeline(manga.id)
			if (timeline.isEmpty()) {
				startDate.value = null
				buckets.value = emptyList()
				daysRead.value = 0
				return@launchLoadingJob
			}
			val perDay = HashMap<LocalDate, Int>()
			for ((at, pages) in timeline) {
				val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
				perDay[day] = (perDay[day] ?: 0) + pages
			}
			daysRead.value = perDay.count { it.value > 0 }
			val today = LocalDate.now(zone)
			// A years-long history would be an unreadable forest of hairlines, so the chart shows the
			// most recent window while the totals underneath stay complete.
			val from = maxOf(perDay.keys.min(), today.minusDays(CHART_DAYS - 1L))
			buckets.value = generateSequence(from) { it.plusDays(1) }
				.takeWhile { !it.isAfter(today) }
				.map { day ->
					StatsBucket(
						startAt = day.atStartOfDay(zone).toInstant().toEpochMilli(),
						duration = (perDay[day] ?: 0).toLong(),
					)
				}.toList()
			startDate.value = calculateTimeAgo(Instant.ofEpochMilli(timeline.firstKey()))
		}
		launchLoadingJob(Dispatchers.Default) {
			totalPagesRead.value = repository.getTotalPagesRead(manga.id)
		}
	}

	private companion object {

		const val CHART_DAYS = 90
	}
}
