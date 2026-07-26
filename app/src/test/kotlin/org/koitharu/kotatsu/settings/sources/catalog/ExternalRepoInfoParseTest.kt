package org.koitharu.kotatsu.settings.sources.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalRepoInfoParseTest {

	private val URL = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"

	@Test
	fun `parses name shortName and fingerprint from repo json`() {
		val body = """
			{"meta":{"name":"Keiyoushi","shortName":"KY","website":"https://keiyoushi.github.io","signingKeyFingerprint":"9add655a"}}
		""".trimIndent()
		val info = parseRepoInfo(URL, body)!!
		assertEquals("Keiyoushi", info.name)
		assertEquals("KY", info.shortName)
		assertEquals("9add655a", info.fingerprint)
		assertEquals("KY", info.displayName)
		assertEquals("https://keiyoushi.github.io", info.website)
	}

	@Test
	fun `displayName falls back to name when shortName missing`() {
		val body = """{"meta":{"name":"MyRepo","signingKeyFingerprint":"abc123"}}"""
		val info = parseRepoInfo(URL, body)!!
		assertEquals("MyRepo", info.displayName)
	}

	@Test
	fun `returns null when fingerprint missing`() {
		assertNull(parseRepoInfo(URL, """{"meta":{"name":"MyRepo"}}"""))
	}

	@Test
	fun `returns null for a legacy index array body`() {
		assertNull(parseRepoInfo(URL, """[{"name":"Some Ext","pkg":"x"}]"""))
	}
}
