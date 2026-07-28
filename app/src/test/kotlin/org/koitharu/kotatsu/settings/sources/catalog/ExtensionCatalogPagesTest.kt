package org.koitharu.kotatsu.settings.sources.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.list.ui.model.ButtonFooter
import org.koitharu.kotatsu.list.ui.model.ListHeader

class ExtensionCatalogPagesTest {

	@Test
	fun `available stays first and stores retain their configured order`() {
		val stores = listOf(
			store("second", "Second"),
			store("first", "First"),
		)

		assertEquals(
			listOf(
				ExtensionCatalogPage.Available,
				ExtensionCatalogPage.Store("second", "Second"),
				ExtensionCatalogPage.Store("first", "First"),
			),
			buildExtensionCatalogPages(stores),
		)
	}

	@Test
	fun `installed page always exists without stores or extensions`() {
		assertEquals(
			listOf(ExtensionCatalogPage.Available),
			buildExtensionCatalogPages(emptyList()),
		)
	}

	@Test
	fun `available page shows updates before every installed extension`() {
		val update = SourceCatalogItem.Extension(
			packageName = "example.extension",
			title = "Example",
			subtitle = "English",
			action = SourceCatalogItem.Extension.Action.UPDATE,
		)
		val installed = update.copy(action = SourceCatalogItem.Extension.Action.UNINSTALL)

		assertEquals(
			listOf(
				ListHeader(R.string.updates_available),
				update,
				ButtonFooter(R.string.update_all),
				ListHeader(R.string.installed),
				installed,
			),
			buildAvailablePageItems(listOf(update), listOf(installed), isPrivateMode = false),
		)
	}

	@Test
	fun `no store warning appears before installed extensions`() {
		val installed = SourceCatalogItem.Extension(
			packageName = "example.extension",
			title = "Example",
			subtitle = "English",
			action = SourceCatalogItem.Extension.Action.UNINSTALL,
		)

		assertEquals(
			listOf(
				SourceCatalogItem.Hint(
					R.drawable.ic_empty_feed,
					R.string.no_extension_store_found,
					R.string.no_extension_store_found_summary,
				),
				ListHeader(R.string.installed),
				installed,
			),
			buildAvailablePageItems(
				updates = emptyList(),
				installed = listOf(installed),
				isPrivateMode = false,
				hasStores = false,
			),
		)
	}

	@Test
	fun `divider appears only before the first store tab`() {
		assertFalse(shouldShowStoreTabDivider(0, ExtensionCatalogPage.Available))
		assertTrue(shouldShowStoreTabDivider(1, ExtensionCatalogPage.Store("first", "First")))
		assertFalse(shouldShowStoreTabDivider(2, ExtensionCatalogPage.Store("second", "Second")))
	}

	@Test
	fun `store tabs exclude extensions already installed from that store`() {
		assertFalse(isStoreInstallCandidate(isInstalled = true, ownerStoreId = "current", currentStoreId = "current"))
		assertTrue(isStoreInstallCandidate(isInstalled = true, ownerStoreId = "other", currentStoreId = "current"))
		assertTrue(isStoreInstallCandidate(isInstalled = false, ownerStoreId = null, currentStoreId = "current"))
	}

	@Test
	fun `updates remain visible while a store refresh is checking`() {
		assertTrue(canUseStoreCatalogForUpdates(StoreHealth.AVAILABLE))
		assertTrue(canUseStoreCatalogForUpdates(StoreHealth.CHECKING))
		assertFalse(canUseStoreCatalogForUpdates(StoreHealth.UNAVAILABLE))
	}

	@Test
	fun `catalog filters are hidden for the completely empty state`() {
		assertTrue(shouldShowCatalogFilters(listOf(ExtensionCatalogPage.Available)))
	}

	@Test
	fun `search ignores spacing and punctuation on both sides`() {
		assertTrue(matchesExtensionQuery("manga fire", "MangaFire"))
		assertTrue(matchesExtensionQuery("mangafire", "Manga Fire"))
		assertTrue(matchesExtensionQuery("mangafire", null, "eu.kanade.extension.manga-fire"))
		assertTrue(matchesExtensionQuery("  ", "Anything"))
		assertFalse(matchesExtensionQuery("mangadex", "MangaFire"))
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
