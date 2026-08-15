package org.koitharu.kotatsu.stats.ui

import android.content.res.Resources
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.details.data.ReadingTime
import org.koitharu.kotatsu.stats.domain.StatsBucketUnit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * "3 h 12 m" / "45 m" — the compact form used everywhere on the statistics screen. Anything that
 * was read for less than a minute reports "<1 m" rather than "0 m", so a bar that is visibly there
 * never claims nothing happened.
 */
fun formatDurationShort(resources: Resources, millis: Long): String {
	val minutes = TimeUnit.MILLISECONDS.toMinutes(millis).toInt()
	if (minutes <= 0) {
		return if (millis > 0L) {
			resources.getString(R.string.stats_under_minute)
		} else {
			resources.getString(R.string.minutes_short, 0)
		}
	}
	return ReadingTime(
		minutes = minutes % 60,
		hours = minutes / 60,
		isContinue = false,
	).formatShort(resources) ?: resources.getString(R.string.stats_under_minute)
}

/**
 * Axis tick under a bar. Kept as short as it can be — a 30-bar chart has room for a couple of
 * characters at most, so only every n-th bar gets a label (see [labelStride]).
 */
fun formatBucketTick(startAt: Long, unit: StatsBucketUnit, zone: ZoneId): String {
	val time = Instant.ofEpochMilli(startAt).atZone(zone)
	val pattern = when (unit) {
		StatsBucketUnit.HOUR -> "H"
		StatsBucketUnit.DAY -> "d"
		StatsBucketUnit.WEEK -> "d/M"
		StatsBucketUnit.MONTH -> "LLL"
	}
	return time.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

/** Full, human label for the bar the user just tapped. */
fun formatBucketTitle(startAt: Long, unit: StatsBucketUnit, zone: ZoneId): String {
	val time = Instant.ofEpochMilli(startAt).atZone(zone)
	val pattern = when (unit) {
		StatsBucketUnit.HOUR -> "EEE, HH:mm"
		StatsBucketUnit.DAY -> "EEE, d MMM"
		StatsBucketUnit.WEEK -> "d MMM"
		StatsBucketUnit.MONTH -> "LLLL yyyy"
	}
	return time.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
		.ifEmpty { time.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }
}

/** Localised medium date ("12 Aug 2026") for the "since …" line on a title row. */
fun formatDate(millis: Long, zone: ZoneId): String = Instant.ofEpochMilli(millis)
	.atZone(zone)
	.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))

/** Every n-th bar gets an axis label, so ticks never collide however wide the chart is. */
fun labelStride(count: Int): Int = when {
	count <= 8 -> 1
	count <= 16 -> 2
	count <= 24 -> 4
	else -> 6
}
