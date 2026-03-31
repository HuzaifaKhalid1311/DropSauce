package org.haziffe.dropsauce.tracker.ui.debug

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.haziffe.dropsauce.core.db.MangaDatabase
import org.haziffe.dropsauce.core.db.entity.toManga
import org.haziffe.dropsauce.core.ui.BaseViewModel
import org.haziffe.dropsauce.core.util.ext.toInstantOrNull
import org.haziffe.dropsauce.tracker.data.TrackWithManga
import javax.inject.Inject

@HiltViewModel
class TrackerDebugViewModel @Inject constructor(
	db: MangaDatabase
) : BaseViewModel() {

	val content = db.getTracksDao().observeAll()
		.map { it.toUiList() }
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	private fun List<TrackWithManga>.toUiList(): List<TrackDebugItem> = map {
		TrackDebugItem(
			manga = it.manga.toManga(emptySet(), null),
			lastChapterId = it.track.lastChapterId,
			newChapters = it.track.newChapters,
			lastCheckTime = it.track.lastCheckTime.toInstantOrNull(),
			lastChapterDate = it.track.lastChapterDate.toInstantOrNull(),
			lastResult = it.track.lastResult,
			lastError = it.track.lastError,
		)
	}
}
