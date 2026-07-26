package org.koitharu.kotatsu.settings.sources.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtensionCatalogPagesTest {

	@Test
	fun `updates stay first and stores retain their configured order`() {
		val stores = listOf(
			store("second", "Second"),
			store("first", "First"),
		)

		assertEquals(
			listOf(
				ExtensionCatalogPage.Updates,
				ExtensionCatalogPage.Store("second", "Second"),
				ExtensionCatalogPage.Store("first", "First"),
			),
			buildExtensionCatalogPages(stores, includeUnknown = false),
		)
	}

	@Test
	fun `unknown page is appended only when required`() {
		val stores = listOf(store("known", "Known"))

		assertEquals(
			ExtensionCatalogPage.Unknown,
			buildExtensionCatalogPages(stores, includeUnknown = true).last(),
		)
		assertEquals(2, buildExtensionCatalogPages(stores, includeUnknown = false).size)
	}

	@Test
	fun `updates page can be hidden without changing store order`() {
		val stores = listOf(store("second", "Second"), store("first", "First"))

		assertEquals(
			listOf(
				ExtensionCatalogPage.Store("second", "Second"),
				ExtensionCatalogPage.Store("first", "First"),
			),
			buildExtensionCatalogPages(stores, includeUnknown = false, includeUpdates = false),
		)
	}

	@Test
	fun `recommendations only come from the current store catalog`() {
		val references = collectRecommendedExtensionRefs(
			externalSourceNames = listOf("MIHON_42:Known", "MIHON_99:Other"),
			installedSourceIds = emptySet(),
			installedPackages = emptySet(),
			repoSourceIndex = mapOf(42L to ("known.extension" to "Known")),
		)

		assertEquals(listOf(RecommendedExtensionRef("known.extension", "Known")), references)
	}

	@Test
	fun `adapter updates are deferred while recycler view is computing layout`() {
		var updated = false
		var deferredUpdate: (() -> Unit)? = null

		dispatchRecyclerAdapterUpdate(
			isComputingLayout = true,
			post = { deferredUpdate = it },
			update = { updated = true },
		)

		assertEquals(false, updated)
		deferredUpdate?.invoke()
		assertEquals(true, updated)
	}

	private fun store(id: String, name: String) = ExtensionStoreRecord(
		id = id,
		indexUrl = "https://example.com/$id/index.min.json",
		name = name,
	)
}
