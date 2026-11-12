package org.koitharu.kotatsu.settings.tracker

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import javax.inject.Inject

@HiltViewModel
class TrackerSettingsViewModel @Inject constructor(
	private val favouritesRepository: FavouritesRepository,
	private val settings: AppSettings,
) : BaseViewModel() {

	val categories = favouritesRepository.observeCategories()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	init {
		migrateLegacyDownloadStrategy()
	}

	fun setTrackingCategories(ids: Set<Long>) {
		launchJob(Dispatchers.Default) {
			for (category in categories.value) {
				val isEnabled = category.id in ids
				if (category.isTrackingEnabled != isEnabled) {
					favouritesRepository.updateCategoryTracking(category.id, isEnabled)
				}
			}
		}
	}

	fun setNewChaptersDownloadCategories(ids: Set<Long>) {
		launchJob(Dispatchers.Default) {
			favouritesRepository.setNewChaptersDownloadCategories(ids)
		}
	}

	private fun migrateLegacyDownloadStrategy() {
		if (settings.consumeLegacyTrackerDownloadStrategy()) {
			launchJob(Dispatchers.Default) {
				favouritesRepository.enableNewChaptersDownloadForTrackedCategories()
			}
		}
	}
}
