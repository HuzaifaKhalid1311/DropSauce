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
	fun `isSupportedLibVersion matches Mihon ABI range`() {
		assertFalse(MihonExtensionLoader.isSupportedLibVersion(1.2))
		assertTrue(MihonExtensionLoader.isSupportedLibVersion(1.4))
		assertTrue(MihonExtensionLoader.isSupportedLibVersion(1.5))
		assertTrue(MihonExtensionLoader.isSupportedLibVersion(1.6))
		assertFalse(MihonExtensionLoader.isSupportedLibVersion(1.9))
		assertFalse(MihonExtensionLoader.isSupportedLibVersion(2.0))
	}

	@Test
	fun `parseLibVersion ignores manifest Float and uses the version name`() {
		// Manifest decimals arrive as Float; widening 1.4f gives 1.3999…, which would fail the range check.
		val libVersion = MihonExtensionLoader.parseLibVersion(1.4f, "1.4.12")
		assertEquals(1.4, libVersion!!, 0.0)
		assertTrue(MihonExtensionLoader.isSupportedLibVersion(libVersion))
		assertEquals(1.5, MihonExtensionLoader.parseLibVersion(null, "1.5.3")!!, 0.0)
		assertEquals(1.6, MihonExtensionLoader.parseLibVersion(1.6, "1.4.2")!!, 0.0)
	}
}
