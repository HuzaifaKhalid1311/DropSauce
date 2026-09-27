package org.koitharu.kotatsu.sync.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionInstallMode
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreOwnership
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreRecord
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreRegistryState
import org.koitharu.kotatsu.sync.data.model.RPref

class SyncedStoresTest {

	private fun store(id: String, url: String = "https://$id.example/index.min.json") =
		ExtensionStoreRecord(id = id, indexUrl = url, name = id)

	/** What another device publishes for [state]. */
	private fun published(state: ExtensionStoreRegistryState) =
		SyncedStores.values(state).map { (key, value) -> RPref(SyncedStores.FILE, key, value, v = 1) }

	private fun apply(local: ExtensionStoreRegistryState, records: List<RPref>) =
		SyncedStores.apply(local, records) { _, _ -> true }.first

	@Test
	fun remoteStoresAreAddedInTheirOrder() {
		val local = ExtensionStoreRegistryState(listOf(store("c")))
		val remote = ExtensionStoreRegistryState(listOf(store("b"), store("a"), store("c")))
		assertEquals(listOf("b", "a", "c"), apply(local, published(remote)).stores.map { it.id })
	}

	@Test
	fun roundTripIsStable() {
		val state = ExtensionStoreRegistryState(listOf(store("a"), store("b")))
		assertEquals(SyncedStores.values(state), SyncedStores.values(apply(state, published(state))))
	}

	@Test
	fun removalDropsStoreAndItsOwnerships() {
		val local = ExtensionStoreRegistryState(
			stores = listOf(store("a"), store("b")),
			ownerships = listOf(ExtensionStoreOwnership(ExtensionInstallMode.SYSTEM, "pkg", "a")),
		)
		val result = apply(local, listOf(RPref(SyncedStores.FILE, "a", null, v = 5)))
		assertEquals(listOf("b"), result.stores.map { it.id })
		assertEquals(emptyList<ExtensionStoreOwnership>(), result.ownerships)
	}

	@Test
	fun sameUrlUnderAnotherIdIsNotDuplicated() {
		val local = ExtensionStoreRegistryState(listOf(store("a", url = "https://repo.example/index.min.json")))
		val remote = ExtensionStoreRegistryState(listOf(store("z", url = "https://REPO.example/index.min.json/")))
		assertEquals(listOf("a"), apply(local, published(remote)).stores.map { it.id })
	}

	@Test
	fun changedSinceDetectionIsSkipped() {
		val local = ExtensionStoreRegistryState(listOf(store("a")))
		val remote = ExtensionStoreRegistryState(listOf(store("a").copy(name = "renamed")))
		val (result, applied) = SyncedStores.apply(local, published(remote)) { key, _ -> key != "a" }
		assertEquals("a", result.stores.single().name)
		assertEquals(listOf(SyncedStores.ORDER), applied.map { it.prefKey })
	}
}
