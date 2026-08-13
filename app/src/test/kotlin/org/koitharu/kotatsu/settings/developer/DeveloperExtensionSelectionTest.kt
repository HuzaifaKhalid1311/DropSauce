package org.koitharu.kotatsu.settings.developer

import eu.kanade.tachiyomi.network.HttpException
import java.io.IOException
import java.net.UnknownHostException
import kotlin.random.Random
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.settings.sources.catalog.extensionDisplayName

class DeveloperExtensionSelectionTest {

	@Test
	fun `selects one source from every extension package`() {
		val candidates = listOf(
			ExtensionTestCandidate("one", "One", listOf("en", "ja")),
			ExtensionTestCandidate("two", "Two", listOf("fr", "es")),
		)

		val selected = selectOneSourcePerExtension(candidates, Random(4))

		assertEquals(listOf("one", "two"), selected.map { it.packageName })
		assertEquals(2, selected.mapNotNull { it.source }.size)
	}

	@Test
	fun `retains extension package with no catalogue source`() {
		val selected = selectOneSourcePerExtension(
			listOf(ExtensionTestCandidate<String>("empty", "Empty", emptyList())),
			Random(0),
		)

		assertEquals(1, selected.size)
		assertNull(selected.single().source)
	}

	@Test
	fun `removes tachiyomi prefix from extension display name`() {
		assertEquals("MangaDex", extensionDisplayName("Tachiyomi: MangaDex"))
		assertEquals("MangaDex", extensionDisplayName("MangaDex"))
	}

	@Test
	fun `uses a stable word from the selected manga title for search`() {
		assertEquals("Hero", searchQueryForTitle("The Hero's Return (Official)"))
		assertEquals("One", searchQueryForTitle("One Piece"))
	}

	@Test
	fun `network and service availability failures are blocked`() {
		assertTrue(isBlockedTestFailure(UnknownHostException("offline")))
		assertTrue(isBlockedTestFailure(IOException("wrapped", UnknownHostException("offline"))))
		assertTrue(isBlockedTestFailure(HttpException(403)))
		assertTrue(isBlockedTestFailure(HttpException(429)))
		assertTrue(isBlockedTestFailure(HttpException(503)))
		assertFalse(isBlockedTestFailure(HttpException(404)))
		assertFalse(isBlockedTestFailure(IllegalStateException("broken parser")))
	}

	@Test
	fun `configured language variant is selected`() {
		val variants = listOf("en", "fr", "ja")

		assertEquals("fr", selectLanguageVariant(variants, "fr") { it })
		assertEquals("en", selectLanguageVariant(variants, "de") { it })
	}
}
