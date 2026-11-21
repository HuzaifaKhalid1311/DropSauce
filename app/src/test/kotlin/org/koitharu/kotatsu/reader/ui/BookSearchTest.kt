package org.koitharu.kotatsu.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSearchTest {

	@Test
	fun `snippet starts on a whole word close before the match`() {
		val text = "The quick brown fox jumps over the lazy dog and keeps running far away from the hunter"
		val start = text.indexOf("lazy")
		val snippet = BookSearch.snippet(text, start, 4)
		assertTrue(snippet, snippet.startsWith("…"))
		assertTrue(snippet, snippet.indexOf("lazy") in 1..31)
		assertTrue(snippet, snippet.removePrefix("…").first() != ' ')
	}

	@Test
	fun `snippet of a match at the start has no leading dots`() {
		assertEquals("lazy dog", BookSearch.snippet("lazy dog", 0, 4))
	}
}
