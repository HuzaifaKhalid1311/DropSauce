package org.koitharu.kotatsu.settings.sources.catalog

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.mihon.MihonExtensionLoader
import org.koitharu.kotatsu.mihon.model.MihonExtensionInfo
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

enum class StoreHealth {
	CHECKING,
	AVAILABLE,
	UNAVAILABLE,
}

data class ExtensionStoreState(
	val store: ExtensionStoreRecord,
	val health: StoreHealth,
	val catalog: List<ExternalExtensionRepoEntry> = emptyList(),
	val error: Throwable? = null,
)

@Singleton
class ExtensionStoreManager @Inject constructor(
	@ApplicationContext private val context: Context,
	private val registry: ExtensionStoreRegistry,
	private val repository: ExternalExtensionRepoRepository,
	private val extensionLoader: MihonExtensionLoader,
) {

	private val mutex = Mutex()
	private val mutableStates = MutableStateFlow<List<ExtensionStoreState>>(emptyList())
	private var initialized = false
	val states: StateFlow<List<ExtensionStoreState>> = mutableStates.asStateFlow()

	suspend fun initialize(forceRefresh: Boolean = false) = mutex.withLock {
		withContext(Dispatchers.IO) {
			val migrationPerformed = ensureMigrated()
			if (!initialized || forceRefresh) {
				refreshLocked(shouldForceStoreRefresh(forceRefresh, migrationPerformed))
				initialized = true
			}
		}
	}

	suspend fun refresh(forceRefresh: Boolean = true) = mutex.withLock {
		withContext(Dispatchers.IO) {
			val migrationPerformed = ensureMigrated()
			refreshLocked(shouldForceStoreRefresh(forceRefresh, migrationPerformed))
			initialized = true
		}
	}

	suspend fun validateAndAdd(indexUrl: String): Result<ExtensionStoreRecord> = mutex.withLock {
		withContext(Dispatchers.IO) {
			runCatching {
				val validated = repository.validateStore(indexUrl)
				val added = validated.store.copy(id = stableExtensionStoreId(validated.store.indexUrl))
				registry.add(added).getOrThrow()
				publishState(ExtensionStoreState(added, StoreHealth.AVAILABLE, validated.catalog))
				added
			}
		}
	}

	suspend fun editStore(storeId: String, indexUrl: String): Result<ExtensionStoreRecord> = mutex.withLock {
		withContext(Dispatchers.IO) {
			runCatching {
				val current = registry.findStore(storeId) ?: error("Store not found")
				val validated = repository.validateStore(indexUrl)
				val replacement = registry.edit(current.id, validated.store).getOrThrow()
				publishState(ExtensionStoreState(replacement, StoreHealth.AVAILABLE, validated.catalog))
				replacement
			}
		}
	}

	fun removeStore(storeId: String) {
		registry.removeStore(storeId)
		syncRecords()
	}

	fun moveStore(fromIndex: Int, toIndex: Int) {
		registry.move(fromIndex, toIndex)
		syncRecords()
	}

	fun stores(): List<ExtensionStoreRecord> = registry.state.stores

	fun containsStoreUrl(indexUrl: String): Boolean = registry.containsStoreUrl(indexUrl)

	fun state(storeId: String): ExtensionStoreState? = states.value.firstOrNull { it.store.id == storeId }

	fun owner(
		mode: ExtensionInstallMode,
		extension: MihonExtensionInfo,
	): ExtensionStoreRecord? = registry.owner(mode, extension.pkgName, extension.signatures)

	fun owner(mode: ExtensionInstallMode, packageName: String): ExtensionStoreRecord? =
		registry.owner(mode, packageName)

	fun setOwner(mode: ExtensionInstallMode, packageName: String, storeId: String) =
		registry.setOwner(mode, packageName, storeId)

	fun removeOwner(mode: ExtensionInstallMode, packageName: String) {
		registry.removeOwner(mode, packageName)
		syncRecords()
	}

	private fun ensureMigrated(): Boolean {
		val systemPackages = extensionLoader.getInstalledExtensions(context, privateMode = false)
			.mapTo(HashSet()) { it.pkgName }
		val sandboxPackages = extensionLoader.getInstalledExtensions(context, privateMode = true)
			.mapTo(HashSet()) { it.pkgName }
		val migrated = registry.ensureMigrated(systemPackages, sandboxPackages)
		registry.reconcileOwnerships(systemPackages, sandboxPackages)
		return migrated
	}

	private suspend fun refreshLocked(forceRefresh: Boolean) {
		val previousById = mutableStates.value.associateBy { it.store.id }
		mutableStates.value = registry.state.stores.map { store ->
			previousById[store.id]?.copy(store = store, health = StoreHealth.CHECKING, error = null)
				?: ExtensionStoreState(store, StoreHealth.CHECKING)
		}
		mutableStates.value = registry.state.stores.map { store ->
			val previous = previousById[store.id]
			val fresh = runCatching { repository.validateStore(store.indexUrl, forceRefresh) }
			val fallbackPrevious = if (fresh.isFailure) {
				val cached = runCatching { repository.getCachedExtensions(store.indexUrl) }.getOrNull()
				if (cached != null) ExtensionStoreState(store, StoreHealth.AVAILABLE, cached) else previous
			} else {
				previous
			}
			fresh.fold(
				onSuccess = { validated ->
					val refreshedStore = validated.store.copy(id = store.id)
					registry.replace(refreshedStore)
					ExtensionStoreState(refreshedStore, StoreHealth.AVAILABLE, validated.catalog)
				},
				onFailure = { error ->
					storeStateAfterRefresh(store, fallbackPrevious, Result.failure(error))
				},
			)
		}
	}

	private fun publishState(state: ExtensionStoreState) {
		val byId = mutableStates.value.associateByTo(LinkedHashMap()) { it.store.id }
		byId[state.store.id] = state
		val order = registry.state.stores.map { it.id }
		mutableStates.value = order.mapNotNull(byId::get)
	}

	private fun syncRecords() {
		val previous = mutableStates.value.associateBy { it.store.id }
		mutableStates.value = registry.state.stores.map { record ->
			previous[record.id]?.copy(
				store = record,
				health = previous[record.id]?.health ?: StoreHealth.CHECKING,
				catalog = previous[record.id]?.catalog.orEmpty(),
			) ?: ExtensionStoreState(
				store = record,
				health = StoreHealth.CHECKING,
			)
		}
	}
}

fun storeStateAfterRefresh(
	store: ExtensionStoreRecord,
	previous: ExtensionStoreState?,
	result: Result<List<ExternalExtensionRepoEntry>>,
): ExtensionStoreState = when {
	result.isSuccess -> ExtensionStoreState(store, StoreHealth.AVAILABLE, result.getOrThrow())
	else -> ExtensionStoreState(
		store = store,
		health = StoreHealth.UNAVAILABLE,
		catalog = previous?.catalog.orEmpty(),
		error = result.exceptionOrNull(),
	)
}

fun shouldForceStoreRefresh(forceRefresh: Boolean, migrationPerformed: Boolean): Boolean =
	forceRefresh || migrationPerformed

fun extensionStoreDisplayLabels(stores: List<ExtensionStoreRecord>): Map<String, String> {
	val duplicateNames = stores.groupingBy { it.displayName.lowercase() }.eachCount()
	return stores.associate { store ->
		val label = if (duplicateNames.getValue(store.displayName.lowercase()) > 1) {
			val host = runCatching { URI(store.indexUrl).host }.getOrNull()
			host?.let { "${store.displayName} · $it" } ?: store.displayName
		} else {
			store.displayName
		}
		store.id to label
	}
}
