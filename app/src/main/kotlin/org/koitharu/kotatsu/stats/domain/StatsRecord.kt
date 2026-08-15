package org.koitharu.kotatsu.stats.domain

import org.koitharu.kotatsu.parsers.model.Manga

/**
 * Everything the statistics screen knows about one title in the selected period. A record with a
 * null [manga] is the "other titles" bucket — see [ReadingStats.otherRecords] for what it hides.
 */
data class StatsRecord(
	val manga: Manga?,
	val duration: Long,
	val pages: Int = 0,
	val chapters: Int = 0,
	val daysRead: Int = 0,
	/** First session in the selected period, so it matches the rest of the numbers on screen. */
	val firstReadAt: Long = 0L,
)
