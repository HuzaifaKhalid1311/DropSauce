package org.koitharu.kotatsu.settings.sources.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionStoreManagerTest {

	@Test
	fun `failed refresh keeps the previous catalog and marks store unavailable`() {
		val store = record("one", "Store")
		val cached = listOf(entry("cached.extension"))
		val previous = ExtensionStoreState(store, StoreHealth.AVAILABLE, cached)

		val next = storeStateAfterRefresh(store, previous, Result.failure(IllegalStateException("offline")))

		assertEquals(StoreHealth.UNAVAILABLE, next.health)
		assertEquals(cached, next.catalog)
		assertTrue(next.error is IllegalStateException)
	}

	@Test
	fun `removed store keeps no available catalog`() {
		val removed = record("one", "Store").copy(enabled = false)
		val previous = ExtensionStoreState(removed.copy(enabled = true), StoreHealth.AVAILABLE, listOf(entry("cached.extension")))

		val next = storeStateAfterRefresh(removed, previous, Result.success(listOf(entry("new.extension"))))

		assertEquals(StoreHealth.REMOVED, next.health)
		assertTrue(next.catalog.isEmpty())
	}

	@Test
	fun `first load after legacy migration bypasses the stale http cache`() {
		assertTrue(shouldForceStoreRefresh(forceRefresh = false, migrationPerformed = true))
	}

	@Test
	fun `store update is routed into its own tab only when the updates tab is hidden`() {
		val local = org.koitharu.kotatsu.mihon.model.MihonExtensionInfo(
			pkgName = "example.extension",
			appName = "Example",
			versionCode = 1,
			versionName = "1.4.1",
			libVersion = 1.4,
			lang = "en",
			isNsfw = false,
			sourceClassName = "Example",
			apkPath = "/example.apk",
		)
		val update = entry("example.extension").copy(versionCode = 2)

		assertEquals(update, findStoreTabUpdate(local, listOf(update), showUpdatesTab = false))
		assertEquals(null, findStoreTabUpdate(local, listOf(update), showUpdatesTab = true))
	}

	@Test
	fun `colliding original names are disambiguated only in labels`() {
		val stores = listOf(
			record("one", "Community", "https://one.example/repo"),
			record("two", "Community", "https://two.example/repo"),
			record("three", "Unique", "https://three.example/repo"),
		)

		val labels = extensionStoreDisplayLabels(stores)

		assertEquals("Community · one.example", labels.getValue("one"))
		assertEquals("Community · two.example", labels.getValue("two"))
		assertEquals("Unique", labels.getValue("three"))
		assertEquals("Community", stores.first().name)
	}

	private fun record(id: String, name: String, url: String = "https://$id.example/repo") =
		ExtensionStoreRecord(id, normalizeExtensionStoreUrl(url), name)

	private fun entry(pkg: String) = ExternalExtensionRepoEntry(
		name = pkg,
		packageName = pkg,
		apkName = "$pkg.apk",
		versionCode = 1,
		versionName = "1.4.1",
	)
}
