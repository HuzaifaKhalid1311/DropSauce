package org.koitharu.kotatsu.stats.domain

import java.util.concurrent.TimeUnit

/** Granularity of a single bar in the activity chart, picked from the selected [StatsPeriod]. */
enum class StatsBucketUnit {
	HOUR, DAY, WEEK, MONTH
}

/** One bar of the activity chart: everything read between [startAt] and the next bucket. */
data class StatsBucket(
	val startAt: Long,
	val duration: Long,
)

/**
 * Everything the statistics screen shows, computed in one pass so the headline numbers, the
 * activity chart and the per-title breakdown can never disagree with each other.
 *
 * Streak numbers are intentionally all-time: a streak that reset itself whenever you switch the
 * period filter would be meaningless.
 */
data class ReadingStats(
	val period: StatsPeriod = StatsPeriod.ALL,
	val records: List<StatsRecord> = emptyList(),
	/** The titles rolled up into the trailing "other" record, biggest first. */
	val otherRecords: List<StatsRecord> = emptyList(),
	val buckets: List<StatsBucket> = emptyList(),
	val bucketUnit: StatsBucketUnit = StatsBucketUnit.DAY,
	val totalDuration: Long = 0L,
	val chapterDuration: Long = 0L,
	val chapters: Int = 0,
	val pages: Int = 0,
	val activeDays: Int = 0,
	val currentStreak: Int = 0,
	val longestStreak: Int = 0,
) {

	val isEmpty: Boolean
		get() = records.isEmpty() && totalDuration == 0L

	/** Average time spent per chapter, in minutes. `0` when nothing with chapters was read. */
	val minutesPerChapter: Double
		get() = if (chapters > 0 && chapterDuration > 0L) {
			chapterDuration.toDouble() / chapters / TimeUnit.MINUTES.toMillis(1)
		} else {
			0.0
		}

	/** Average time per day that actually had reading on it — a fairer number than /period. */
	val averagePerActiveDay: Long
		get() = if (activeDays > 0) totalDuration / activeDays else 0L
}
