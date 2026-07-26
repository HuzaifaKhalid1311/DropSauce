package org.koitharu.kotatsu.settings.sources.catalog

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.list.ui.model.ButtonFooter

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
				ExtensionCatalogPage.NoSource,
				ExtensionCatalogPage.Store("second", "Second"),
				ExtensionCatalogPage.Store("first", "First"),
			),
			buildExtensionCatalogPages(stores, includeNoSource = true),
		)
	}

	@Test
	fun `no source page is appended only when required`() {
		val stores = listOf(store("known", "Known"))

		assertEquals(
			ExtensionCatalogPage.NoSource,
			buildExtensionCatalogPages(stores, includeNoSource = true)[1],
		)
		assertEquals(2, buildExtensionCatalogPages(stores, includeNoSource = false).size)
	}

	@Test
	fun `fresh install without stores or extensions has no tabs`() {
		assertEquals(
			listOf(ExtensionCatalogPage.Empty),
			buildExtensionCatalogPages(emptyList(), includeNoSource = false, includeUpdates = false),
		)
	}

	@Test
	fun `installed extensions without stores show only no source`() {
		assertEquals(
			listOf(ExtensionCatalogPage.NoSource),
			buildExtensionCatalogPages(emptyList(), includeNoSource = true, includeUpdates = false),
		)
	}

	@Test
	fun `updates page ends with update all when updates exist`() {
		val update = SourceCatalogItem.Extension(
			packageName = "example.extension",
			title = "Example",
			subtitle = "English",
			action = SourceCatalogItem.Extension.Action.UPDATE,
		)

		assertEquals(
			listOf(update, ButtonFooter(R.string.update_all)),
			buildUpdatesPageItems(listOf(update)),
		)
	}

	@Test
	fun `updates page can be hidden without changing store order`() {
		val stores = listOf(store("second", "Second"), store("first", "First"))

		assertEquals(
			listOf(
				ExtensionCatalogPage.Store("second", "Second"),
				ExtensionCatalogPage.Store("first", "First"),
			),
			buildExtensionCatalogPages(stores, includeNoSource = false, includeUpdates = false),
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
