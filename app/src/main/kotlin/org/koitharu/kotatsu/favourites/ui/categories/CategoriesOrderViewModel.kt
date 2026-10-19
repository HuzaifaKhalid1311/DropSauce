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
import org.koitharu.kotatsu.favourites.domain.model.CategoriesOrder
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@HiltViewModel
class CategoriesOrderViewModel @Inject constructor(
	private val settings: AppSettings,
	private val repository: FavouritesRepository,
) : BaseViewModel() {

	val order = MutableStateFlow(settings.favouriteCategoriesOrder)

	/** Same tap rule as the other sort sheets. Going back to random keeps the order the last sort left. */
	fun onTypeClick(type: CategoriesOrder.Type) {
		val current = order.value
		val isAscending = if (current.type == type) !current.isAscending else current.isAscending
		order.value = type.toOrder(isAscending)
		// App-wide scope so closing the sheet right after a tap can't drop the write. Writes run one at
		// a time and each applies the latest pick, so quick taps always end on it.
		processLifecycleScope.launch(Dispatchers.Default) {
			writeLock.withLock {
				val target = order.value
				val applied = settings.favouriteCategoriesOrder
				if (target == CategoriesOrder.RANDOM && applied != CategoriesOrder.RANDOM) {
					runCatchingCancellable {
						repository.saveCategoriesOrder(applied)
					}.onFailure { it.printStackTraceDebug() }
				}
				settings.favouriteCategoriesOrder = target
			}
		}
	}

	private companion object {

		val writeLock = Mutex()
	}
}
