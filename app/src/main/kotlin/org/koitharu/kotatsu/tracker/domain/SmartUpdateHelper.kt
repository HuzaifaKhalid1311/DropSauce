package org.koitharu.kotatsu.tracker.domain

import android.util.Log
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.tracker.domain.model.MangaTracking

object SmartUpdateHelper {

	suspend fun shouldSkip(
		track: MangaTracking,
		settings: AppSettings,
		database: MangaDatabase,
	): Boolean = getSkipReason(track.manga, track.newChapters, settings, database) != null

	suspend fun getSkipReason(
		manga: Manga,
		newChapters: Int,
		settings: AppSettings,
		database: MangaDatabase,
	): String? {
		val mangaId = manga.id

		// 1. Skip Completed
		if (settings.isTrackerSkipCompleted && manga.state == MangaState.FINISHED) {
			Log.d("SmartUpdateHelper", "Skipping [${manga.title}]: Completed")
			return "Skipped: Completed"
		}

		val historyDao = database.getHistoryDao()
		val history = historyDao.find(mangaId)

		// 2. Skip Unstarted
		if (settings.isTrackerSkipUnstarted && (history == null || history.deletedAt != 0L)) {
			Log.d("SmartUpdateHelper", "Skipping [${manga.title}]: Unstarted")
			return "Skipped: Unstarted"
		}

		// 3. Skip Unread (Skip entries with unread chapters)
		if (settings.isTrackerSkipUnread) {
			val hasUnread = (history != null && history.percent < 1f) || newChapters > 0
			if (hasUnread) {
				Log.d("SmartUpdateHelper", "Skipping [${manga.title}]: Unread chapters exist")
				return "Skipped: Unread chapters"
			}
		}

		Log.d("SmartUpdateHelper", "Checking updates for [${manga.title}]")
		return null
	}
}
