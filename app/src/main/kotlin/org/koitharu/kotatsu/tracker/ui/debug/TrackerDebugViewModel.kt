package org.koitharu.kotatsu.tracker.ui.debug

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.toInstantOrNull
import org.koitharu.kotatsu.tracker.data.TrackWithManga
import org.koitharu.kotatsu.tracker.domain.SmartUpdateHelper
import javax.inject.Inject

@HiltViewModel
class TrackerDebugViewModel @Inject constructor(
	private val db: MangaDatabase,
	private val settings: AppSettings,
) : BaseViewModel() {

	val content = db.getTracksDao().observeAll()
		.map { it.toUiList() }
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	private suspend fun List<TrackWithManga>.toUiList(): List<TrackDebugItem> = map {
		val manga = it.manga.toManga(emptySet(), null)
		val skipReason = SmartUpdateHelper.getSkipReason(
			manga = manga,
			newChapters = it.track.newChapters,
			settings = settings,
			database = db,
		)
		TrackDebugItem(
			manga = manga,
			lastChapterId = it.track.lastChapterId,
			newChapters = it.track.newChapters,
			lastCheckTime = it.track.lastCheckTime.toInstantOrNull(),
			lastChapterDate = it.track.lastChapterDate.toInstantOrNull(),
			lastResult = it.track.lastResult,
			lastError = it.track.lastError,
			skipReason = skipReason,
		)
	}
}
