package org.haziffe.dropsauce.favourites.ui.categories.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.haziffe.dropsauce.core.model.FavouriteCategory
import org.haziffe.dropsauce.core.nav.AppRouter
import org.haziffe.dropsauce.core.prefs.AppSettings
import org.haziffe.dropsauce.core.ui.BaseViewModel
import org.haziffe.dropsauce.core.util.ext.MutableEventFlow
import org.haziffe.dropsauce.core.util.ext.call
import org.haziffe.dropsauce.favourites.domain.FavouritesRepository
import org.haziffe.dropsauce.favourites.ui.categories.edit.FavouritesCategoryEditActivity.Companion.NO_ID
import org.haziffe.dropsauce.list.domain.ListSortOrder
import javax.inject.Inject

@HiltViewModel
class FavouritesCategoryEditViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val repository: FavouritesRepository,
	private val settings: AppSettings,
) : BaseViewModel() {

	private val categoryId = savedStateHandle[AppRouter.KEY_ID] ?: NO_ID

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
		isVisibleOnShelf: Boolean,
	) {
		launchLoadingJob(Dispatchers.Default) {
			check(title.isNotEmpty())
			if (categoryId == NO_ID) {
				repository.createCategory(title, sortOrder, isTrackerEnabled, isVisibleOnShelf)
			} else {
				repository.updateCategory(categoryId, title, sortOrder, isTrackerEnabled, isVisibleOnShelf)
			}
			onSaved.call(Unit)
		}
	}
}
