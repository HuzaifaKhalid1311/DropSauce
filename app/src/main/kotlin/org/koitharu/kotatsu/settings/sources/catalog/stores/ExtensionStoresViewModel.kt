package org.koitharu.kotatsu.settings.sources.catalog.stores

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreManager
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreRecord
import javax.inject.Inject

@HiltViewModel
class ExtensionStoresViewModel @Inject constructor(
	private val manager: ExtensionStoreManager,
) : BaseViewModel() {

	val stores = manager.states

	init {
		launchJob(Dispatchers.IO) {
			manager.initialize()
		}
	}

	suspend fun addStore(indexUrl: String): Result<ExtensionStoreRecord> =
		manager.validateAndAdd(indexUrl)

	suspend fun editStore(storeId: String, indexUrl: String): Result<ExtensionStoreRecord> =
		manager.editStore(storeId, indexUrl)

	fun removeStore(storeId: String) = manager.removeStore(storeId)

	suspend fun retry() = manager.refresh(forceRefresh = true)

	fun moveStore(fromIndex: Int, toIndex: Int) = manager.moveStore(fromIndex, toIndex)
}
