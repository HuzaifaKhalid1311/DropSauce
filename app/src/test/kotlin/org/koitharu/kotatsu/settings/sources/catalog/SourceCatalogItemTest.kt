package org.haziffe.dropsauce.settings.sources.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceCatalogItemTest {

	@Test
	fun `extension item identity includes action`() {
		val installed = SourceCatalogItem.Extension(
			packageName = "eu.kanade.tachiyomi.extension.en.demo",
			title = "Demo",
			subtitle = "English • 1.0.0",
			action = SourceCatalogItem.Extension.Action.UNINSTALL,
		)
		val available = installed.copy(action = SourceCatalogItem.Extension.Action.INSTALL)

		assertFalse(installed.areItemsTheSame(available))
		assertTrue(installed.areItemsTheSame(installed.copy()))
	}
}
