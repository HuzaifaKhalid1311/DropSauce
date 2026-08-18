package org.koitharu.kotatsu.stats.domain

import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import java.util.concurrent.TimeUnit

enum class StatsPeriod(
	@StringRes val titleResId: Int,
	val days: Int,
) {

	// Declaration order is the order the filter chips appear in: widest first, so the default
	// selection sits at the start of the row.
	ALL(R.string.all_time, Int.MAX_VALUE),
	YEAR(R.string.year, 365),
	MONTHS_3(R.string.three_months, 90),
	MONTH(R.string.month, 30),
	WEEK(R.string.week, 7),
	DAY(R.string.day, 1);

	fun startDate(): Long = if (this == ALL) {
		0L
	} else {
		System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
	}
}
