package org.koitharu.kotatsu.history.ui

import android.content.Context
import android.text.format.DateUtils
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.Manga
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A History date (or progress) section header. [itemIds] are the loaded entries under it. */
data class HistorySection(
	val key: String,
	val count: Int,
	val isCountExact: Boolean,
	val isCollapsed: Boolean,
	val itemIds: List<Long>,
	val previews: List<Manga>,
) : ListModel {

	val isFullyLoaded: Boolean
		get() = isCountExact && itemIds.size >= count

	fun getTitle(context: Context): String = when {
		key.startsWith(MONTH_PREFIX) -> {
			val month = YearMonth.parse(key.removePrefix(MONTH_PREFIX))
			val millis = month.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
			val yearFlag = if (month.year == LocalDate.now().year) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR
			DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY or yearFlag)
		}

		else -> context.getString(
			when (key) {
				KEY_TODAY -> R.string.today
				KEY_YESTERDAY -> R.string.yesterday
				KEY_WEEK -> R.string.stats_range_week
				KEY_MONTH -> R.string.this_month
				KEY_COMPLETED -> R.string.status_completed
				KEY_PLANNED -> R.string.status_planned
				KEY_READING -> R.string.status_reading
				else -> R.string.unknown
			},
		)
	}

	override fun areItemsTheSame(other: ListModel) = other is HistorySection && other.key == key

	// Non-null, so the same header is rebound in place and its count/fold animations can play.
	override fun getChangePayload(previousState: ListModel): Any = ListModelDiffCallback.PAYLOAD_ANYTHING_CHANGED
}

/**
 * Today, Yesterday, Last 7 days, This month, then one section per month; or progress buckets.
 * The key is stable across days and languages, so a folded section stays folded.
 */
class HistorySectionResolver(
	val type: ListSortOrder.Type,
	val today: LocalDate = LocalDate.now(),
	val zone: ZoneId = ZoneId.systemDefault(),
) {

	fun resolve(createdAt: Long, updatedAt: Long, percent: Float): String? = when (type) {
		ListSortOrder.Type.LAST_READ -> byDate(updatedAt)
		ListSortOrder.Type.DATE_ADDED -> byDate(createdAt)
		ListSortOrder.Type.PROGRESS -> when {
			ReadingProgress.isCompleted(percent) -> KEY_COMPLETED
			percent in 0f..0.01f -> KEY_PLANNED
			percent in 0f..1f -> KEY_READING
			else -> KEY_UNKNOWN
		}

		else -> null
	}

	private fun byDate(millis: Long): String {
		if (millis <= 0L) return KEY_UNKNOWN
		val date = java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
		val days = ChronoUnit.DAYS.between(date, today)
		return when {
			days <= 0L -> KEY_TODAY // a clock set back puts entries in the future: they are still the newest
			days == 1L -> KEY_YESTERDAY
			days < 7L -> KEY_WEEK
			date.year == today.year && date.month == today.month -> KEY_MONTH
			else -> MONTH_PREFIX + YearMonth.from(date)
		}
	}
}

private const val KEY_TODAY = "today"
private const val KEY_YESTERDAY = "yesterday"
private const val KEY_WEEK = "week"
private const val KEY_MONTH = "month"
private const val KEY_COMPLETED = "completed"
private const val KEY_PLANNED = "planned"
private const val KEY_READING = "reading"
private const val KEY_UNKNOWN = "unknown"
private const val MONTH_PREFIX = "m:"
