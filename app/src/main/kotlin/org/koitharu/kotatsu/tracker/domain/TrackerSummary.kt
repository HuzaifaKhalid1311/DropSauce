package org.koitharu.kotatsu.tracker.domain

data class TrackerSummary(
	val lastCheckTimeMillis: Long = 0L,
	val trackedCount: Int = 0,
	val failedCount: Int = 0,
)
