package org.koitharu.kotatsu.favourites.ui.categories.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.ui.categories.edit.FavouritesCategoryEditActivity.Companion.NO_ID
import org.koitharu.kotatsu.list.domain.ListSortOrder
import javax.inject.Inject

@HiltViewModel
class FavouritesCategoryEditViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val repository: FavouritesRepository,
	private val settings: AppSettings,
) : BaseViewModel() {

	private val categoryId = savedStateHandle[AppRouter.KEY_ID] ?: NO_ID

	/** Sort a brand-new category starts with, picked in the categories manager. */
	val defaultSortOrder: ListSortOrder = settings.defaultCategorySortOrder

	val onSaved = MutableEventFlow<Unit>()
	val category = MutableStateFlow<FavouriteCategory?>(null)

	val isTrackerEnabled = flow {
		emit(settings.isTrackerEnabled && AppSettings.TRACK_FAVOURITES in settings.trackSources)
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	init {
		launchLoadingJob(Dispatchers.Default) {
			category.value = if (categoryId != NO_ID) {
				repository.getCategory(categoryId)
			} else {
				null
			}
		}
	}

	fun save(
		title: String,
		sortOrder: ListSortOrder,
		isTrackerEnabled: Boolean,
		isNewChaptersDownloadEnabled: Boolean,
		isVisibleOnShelf: Boolean,
	) {
		launchLoadingJob(Dispatchers.Default) {
			check(title.isNotEmpty())
			if (categoryId == NO_ID) {
				repository.createCategory(
					title = title,
					sortOrder = sortOrder,
					isTrackerEnabled = isTrackerEnabled,
					isNewChaptersDownloadEnabled = isNewChaptersDownloadEnabled,
					isVisibleOnShelf = isVisibleOnShelf,
				)
			} else {
				repository.updateCategory(
					id = categoryId,
					title = title,
					sortOrder = sortOrder,
					isTrackerEnabled = isTrackerEnabled,
					isNewChaptersDownloadEnabled = isNewChaptersDownloadEnabled,
					isVisibleOnShelf = isVisibleOnShelf,
				)
			}
			onSaved.call(Unit)
		}
	}
}
