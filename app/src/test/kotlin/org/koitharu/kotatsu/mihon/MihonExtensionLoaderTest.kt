package org.koitharu.kotatsu.mihon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MihonExtensionLoaderTest {

	@Test
	fun `normalizeSourceClassNames resolves relative class names`() {
		val result = MihonExtensionLoader.normalizeSourceClassNames(
			pkgName = "eu.kanade.tachiyomi.extension.zh.demo",
			sourceClassNames = ".DemoSource; foo.bar.AbsoluteSource, .Factory",
		)

		assertEquals(
			listOf(
				"eu.kanade.tachiyomi.extension.zh.demo.DemoSource",
				"foo.bar.AbsoluteSource",
				"eu.kanade.tachiyomi.extension.zh.demo.Factory",
			),
			result,
		)
	}

	@Test
	fun `parseNsfwFlag supports integer metadata`() {
		assertTrue(MihonExtensionLoader.parseNsfwFlag(1))
	}

	@Test
	fun `parseNsfwFlag returns false when metadata is absent`() {
		assertFalse(MihonExtensionLoader.parseNsfwFlag(null))
	}

	@Test
	fun `isSupportedLibVersion matches widened range`() {
		assertFalse(MihonExtensionLoader.isSupportedLibVersion(1.1))
		assertTrue(MihonExtensionLoader.isSupportedLibVersion(1.2))
		assertTrue(MihonExtensionLoader.isSupportedLibVersion(1.4))
		assertTrue(MihonExtensionLoader.isSupportedLibVersion(1.5))
		assertTrue(MihonExtensionLoader.isSupportedLibVersion(1.9))
		assertFalse(MihonExtensionLoader.isSupportedLibVersion(2.0))
	}
}
