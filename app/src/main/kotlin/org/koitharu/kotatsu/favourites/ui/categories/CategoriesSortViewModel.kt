package org.koitharu.kotatsu.favourites.ui.categories

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@HiltViewModel
class CategoriesSortViewModel @Inject constructor(
	private val settings: AppSettings,
	private val repository: FavouritesRepository,
) : BaseViewModel() {

	val types: List<ListSortOrder.Type> = ListSortOrder.FAVORITES

	val sortOrder = MutableStateFlow(settings.defaultCategorySortOrder)

	/**
	 * Same tap rule as the favourites sort sheet. The pick becomes the default for new categories and
	 * overwrites every existing category's own sort; the "All favourites" tab is left alone.
	 */
	fun onTypeClick(type: ListSortOrder.Type) {
		val current = sortOrder.value
		val isAscending = if (current.type == type) !current.isAscending else current.isAscending
		val value = type.toSortOrder(isAscending)
		sortOrder.value = value
		settings.defaultCategorySortOrder = value
		// App-wide scope so closing the sheet right after a tap can't drop the write. Writes run one at
		// a time and each re-reads the setting, so quick taps always end on the latest pick.
		processLifecycleScope.launch(Dispatchers.Default) {
			writeLock.withLock {
				runCatchingCancellable {
					repository.setAllCategoriesOrder(settings.defaultCategorySortOrder)
				}.onFailure { it.printStackTraceDebug() }
			}
		}
	}

	private companion object {

		val writeLock = Mutex()
	}
}
