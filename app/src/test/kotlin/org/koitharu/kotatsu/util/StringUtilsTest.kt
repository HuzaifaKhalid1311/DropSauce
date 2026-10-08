package org.koitharu.kotatsu.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.parsers.util.almostEquals
import org.koitharu.kotatsu.parsers.util.caseInsensitiveLevenshteinDistance
import org.koitharu.kotatsu.parsers.util.levenshteinDistance

class StringUtilsTest {

	@Test
	fun `levenshtein distance calculates correct distances`() {
		assertEquals(0, "kitten".levenshteinDistance("kitten"))
		assertEquals(3, "kitten".levenshteinDistance("sitting"))
		assertEquals(1, "flaw".levenshteinDistance("flaws"))
		assertEquals(4, "".levenshteinDistance("test"))
		assertEquals(4, "test".levenshteinDistance(""))
		assertEquals(1, "One Piece".levenshteinDistance("One-Piece"))
	}

	@Test
	fun `case insensitive levenshtein works correctly`() {
		assertEquals(0, "Naruto".caseInsensitiveLevenshteinDistance("naruto"))
		assertEquals(2, "Naruto".caseInsensitiveLevenshteinDistance("boruto"))
		assertEquals(1, "Naruto".caseInsensitiveLevenshteinDistance("baruto"))
		assertEquals(0, "".caseInsensitiveLevenshteinDistance(""))
	}

	@Test
	fun `almostEquals matches similar titles within threshold`() {
		assertTrue("Naruto".almostEquals("naruto", 0.2f))
		assertTrue("One Piece".almostEquals("One-Piece", 0.2f))
		assertTrue("Solo Leveling".almostEquals("solo leveling", 0.2f))
		assertFalse("Naruto".almostEquals("Bleach", 0.2f))
		assertFalse("Naruto".almostEquals("The Great Mage Returns After 4000 Years", 0.2f))
	}
}
