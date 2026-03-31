package org.haziffe.dropsauce.tracker.domain.model

import org.haziffe.dropsauce.parsers.model.Manga
import java.time.Instant

data class TrackingLogItem(
	val id: Long,
	val manga: Manga,
	val chapters: List<String>,
	val createdAt: Instant,
	val isNew: Boolean,
)
