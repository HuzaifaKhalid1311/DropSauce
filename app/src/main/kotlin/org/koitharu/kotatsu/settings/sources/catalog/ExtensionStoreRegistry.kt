package org.koitharu.kotatsu.settings.sources.catalog

import org.koitharu.kotatsu.core.prefs.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExtensionStoreRegistry @Inject constructor(
	private val settings: AppSettings,
) {

	private val lock = Any()

	val state: ExtensionStoreRegistryState
		get() = settings.extensionStoreRegistryState

	fun ensureMigrated(systemPackages: Set<String>, sandboxPackages: Set<String>): Boolean {
		synchronized(lock) {
			if (settings.isExtensionStoreMigrationComplete) return false
			var migrated = false
			if (settings.extensionStoreRegistryState.stores.isEmpty()) {
				settings.extensionStoreRegistryState = migrateLegacyExtensionStores(
					activeUrl = settings.externalExtensionsRepoUrl,
					legacyOwners = settings.getExtensionRepoUrls(),
					systemPackages = systemPackages,
					sandboxPackages = sandboxPackages,
					repoInfos = settings.externalRepoInfos,
				)
				migrated = true
			}
			settings.isExtensionStoreMigrationComplete = true
			return migrated
		}
	}

	fun add(store: ExtensionStoreRecord): Result<Unit> = synchronized(lock) {
		state.add(store).map { updated ->
			settings.extensionStoreRegistryState = updated
		}
	}

	fun importStores(stores: Iterable<ExtensionStoreRecord>) = synchronized(lock) {
		var updated = state
		for (store in stores) {
			updated = updated.add(store).getOrElse { updated }
		}
		settings.extensionStoreRegistryState = updated
		settings.isExtensionStoreMigrationComplete = true
	}

	fun replace(store: ExtensionStoreRecord) = update { it.replace(store) }

	fun edit(storeId: String, replacement: ExtensionStoreRecord): Result<ExtensionStoreRecord> = synchronized(lock) {
		state.editStore(storeId, replacement).map { updated ->
			settings.extensionStoreRegistryState = updated
			updated.stores.first { it.id == storeId }
		}
	}

	fun disable(storeId: String) = update { it.disable(storeId).cleanupDisabledStores() }

	fun move(fromIndex: Int, toIndex: Int) = update { it.move(fromIndex, toIndex) }

	fun setOwner(mode: ExtensionInstallMode, packageName: String, storeId: String) =
		update { it.setOwner(mode, packageName, storeId) }

	fun removeOwner(mode: ExtensionInstallMode, packageName: String) =
		update { it.removeOwner(mode, packageName).cleanupDisabledStores() }

	fun reconcileOwnerships(systemPackages: Set<String>, sandboxPackages: Set<String>) =
		update { it.reconcileOwnerships(systemPackages, sandboxPackages) }

	fun findStore(storeId: String): ExtensionStoreRecord? = state.stores.firstOrNull { it.id == storeId }

	fun owner(
		mode: ExtensionInstallMode,
		packageName: String,
		signatures: Collection<String> = emptyList(),
	): ExtensionStoreRecord? {
		val snapshot = state
		snapshot.ownerId(mode, packageName)
			?.let { ownerId -> snapshot.stores.firstOrNull { it.id == ownerId } }
			?.let { return it }
		if (signatures.isEmpty()) return null
		val matches = snapshot.stores.filter { store ->
			store.fingerprint?.let { fingerprint ->
				signatures.any { it.equals(fingerprint, ignoreCase = true) }
			} == true
		}
		return matches.singleOrNull()?.also { setOwner(mode, packageName, it.id) }
	}

	private inline fun update(transform: (ExtensionStoreRegistryState) -> ExtensionStoreRegistryState) {
		synchronized(lock) {
			settings.extensionStoreRegistryState = transform(settings.extensionStoreRegistryState)
		}
	}
}
