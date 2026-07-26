package org.koitharu.kotatsu.settings.sources.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionStoreRegistryTest {

	@Test
	fun `duplicate normalized urls and fingerprints are rejected`() {
		val first = store(id = "one", url = "https://example.com/repo/index.min.json", fingerprint = "abc")
		val state = ExtensionStoreRegistryState(stores = listOf(first))

		assertTrue(
			state.add(store(id = "two", url = "https://example.com/repo/", fingerprint = "def")).isFailure,
		)
		assertTrue(
			state.add(store(id = "three", url = "https://other.example/repo", fingerprint = "ABC")).isFailure,
		)
	}

	@Test
	fun `system and sandbox ownership stay independent for the same package`() {
		val state = ExtensionStoreRegistryState(stores = listOf(store("one"), store("two")))
			.setOwner(ExtensionInstallMode.SYSTEM, PACKAGE, "one")
			.setOwner(ExtensionInstallMode.SANDBOX, PACKAGE, "two")

		assertEquals("one", state.ownerId(ExtensionInstallMode.SYSTEM, PACKAGE))
		assertEquals("two", state.ownerId(ExtensionInstallMode.SANDBOX, PACKAGE))
	}

	@Test
	fun `removing a store also removes its ownerships`() {
		val owned = ExtensionStoreRegistryState(stores = listOf(store("one")))
			.setOwner(ExtensionInstallMode.SYSTEM, PACKAGE, "one")
			.setOwner(ExtensionInstallMode.SANDBOX, PACKAGE, "one")

		val removed = owned.removeStore("one")

		assertTrue(removed.stores.isEmpty())
		assertNull(removed.ownerId(ExtensionInstallMode.SYSTEM, PACKAGE))
		assertNull(removed.ownerId(ExtensionInstallMode.SANDBOX, PACKAGE))
	}

	@Test
	fun `legacy disabled stores and their ownerships are discarded`() {
		val legacy = ExtensionStoreRegistryState(stores = listOf(store("one").copy(enabled = false)))
			.setOwner(ExtensionInstallMode.SYSTEM, PACKAGE, "one")

		val cleaned = legacy.cleanupDisabledStores()

		assertTrue(cleaned.stores.isEmpty())
		assertTrue(cleaned.ownerships.isEmpty())
	}

	@Test
	fun `moving stores changes their persisted order`() {
		val state = ExtensionStoreRegistryState(stores = listOf(store("one"), store("two"), store("three")))
			.move(fromIndex = 2, toIndex = 0)

		assertEquals(listOf("three", "one", "two"), state.stores.map { it.id })
	}

	@Test
	fun `editing changes the existing store without creating another record`() {
		val state = ExtensionStoreRegistryState(stores = listOf(store("one")))
			.setOwner(ExtensionInstallMode.SYSTEM, PACKAGE, "one")

		val edited = state.editStore(
			"one",
			store("different-id", url = "https://new.example/repo", fingerprint = "new-key"),
		).getOrThrow()

		assertEquals(1, edited.stores.size)
		assertEquals("one", edited.stores.single().id)
		assertEquals(normalizeExtensionStoreUrl("https://new.example/repo"), edited.stores.single().indexUrl)
		assertEquals("one", edited.ownerId(ExtensionInstallMode.SYSTEM, PACKAGE))
	}

	@Test
	fun `editing cannot duplicate another configured store`() {
		val state = ExtensionStoreRegistryState(stores = listOf(store("one"), store("two")))

		assertTrue(state.editStore("one", store("replacement", url = store("two").indexUrl)).isFailure)
	}

	@Test
	fun `removing an owner does not affect the other installation mode`() {
		val state = ExtensionStoreRegistryState(stores = listOf(store("one"), store("two")))
			.setOwner(ExtensionInstallMode.SYSTEM, PACKAGE, "one")
			.setOwner(ExtensionInstallMode.SANDBOX, PACKAGE, "two")
			.removeOwner(ExtensionInstallMode.SYSTEM, PACKAGE)

		assertNull(state.ownerId(ExtensionInstallMode.SYSTEM, PACKAGE))
		assertEquals("two", state.ownerId(ExtensionInstallMode.SANDBOX, PACKAGE))
	}

	@Test
	fun `reconciling installed packages removes stale ownership and its disabled store`() {
		val state = ExtensionStoreRegistryState(stores = listOf(store("one").copy(enabled = false)))
			.setOwner(ExtensionInstallMode.SYSTEM, PACKAGE, "one")

		val reconciled = state.reconcileOwnerships(systemPackages = emptySet(), sandboxPackages = emptySet())

		assertTrue(reconciled.ownerships.isEmpty())
		assertTrue(reconciled.stores.isEmpty())
	}

	@Test
	fun `legacy single store and package urls migrate without guessing missing packages`() {
		val activeUrl = "https://active.example/repo/index.min.json"
		val historicalUrl = "https://old.example/repo/index.min.json"

		val state = migrateLegacyExtensionStores(
			activeUrl = activeUrl,
			legacyOwners = mapOf(
				PACKAGE to activeUrl,
				"old.extension" to historicalUrl,
				"missing.extension" to historicalUrl,
			),
			systemPackages = setOf(PACKAGE, "old.extension"),
			sandboxPackages = setOf(PACKAGE),
			repoInfos = emptyList(),
		)

		assertEquals(1, state.stores.size)
		assertEquals(normalizeExtensionStoreUrl(activeUrl), state.stores.single().indexUrl)
		assertEquals(
			state.stores.first { it.indexUrl == normalizeExtensionStoreUrl(activeUrl) }.id,
			state.ownerId(ExtensionInstallMode.SYSTEM, PACKAGE),
		)
		assertEquals(state.stores.single().id, state.ownerId(ExtensionInstallMode.SANDBOX, PACKAGE))
		assertNull(state.ownerId(ExtensionInstallMode.SYSTEM, "old.extension"))
		assertNull(state.ownerId(ExtensionInstallMode.SYSTEM, "missing.extension"))
	}

	private fun store(
		id: String,
		url: String = "https://$id.example/repo/index.min.json",
		fingerprint: String? = id,
	) = ExtensionStoreRecord(
		id = id,
		indexUrl = normalizeExtensionStoreUrl(url),
		name = id,
		fingerprint = fingerprint,
	)

	private companion object {
		const val PACKAGE = "eu.kanade.tachiyomi.extension.en.mangafire"
	}
}
