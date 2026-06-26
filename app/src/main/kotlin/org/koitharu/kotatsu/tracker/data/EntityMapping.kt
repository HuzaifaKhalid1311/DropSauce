package org.koitharu.kotatsu.tracker.data

import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.db.entity.toMangaTags
import org.koitharu.kotatsu.tracker.domain.model.TrackingLogItem
import java.time.Instant

fun TrackLogWithManga.toTrackingLogItem(): TrackingLogItem {
	val chaptersList = trackLog.chapters.split('\n').filterNot { x -> x.isEmpty() }
	// Guard: a zero or corrupt createdAt must not sink the entry to 1970; show it as today
	val createdAt = if (trackLog.createdAt > 0L) Instant.ofEpochMilli(trackLog.createdAt) else Instant.now()
	return TrackingLogItem(
		id = trackLog.id,
		chapters = chaptersList,
		manga = manga.toManga(tags.toMangaTags(), null),
		createdAt = createdAt,
		isNew = trackLog.isUnread,
	)
}
