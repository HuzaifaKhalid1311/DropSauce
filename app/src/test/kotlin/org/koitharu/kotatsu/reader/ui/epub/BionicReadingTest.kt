package org.koitharu.kotatsu.reader.ui.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class BionicReadingTest {

	@Test
	fun `prefix length matches the text-vide table`() {
		// Boundaries [0, 4, 12, 17, 24, 29, 35, 42, 48]: length minus the index it falls into.
		assertEquals(0, bionicPrefixLength(1))
		assertEquals(1, bionicPrefixLength(2))
		assertEquals(2, bionicPrefixLength(3))
		assertEquals(3, bionicPrefixLength(4))
		assertEquals(3, bionicPrefixLength(5))
		assertEquals(4, bionicPrefixLength(6))
		assertEquals(10, bionicPrefixLength(12))
		assertEquals(10, bionicPrefixLength(13))
		assertEquals(14, bionicPrefixLength(18))
		assertEquals(20, bionicPrefixLength(25))
	}

	@Test
	fun `prefix never exceeds the word and never goes negative`() {
		(0..200).forEach { length ->
			val prefix = bionicPrefixLength(length)
			assert(prefix in 0..length) { "length $length gave prefix $prefix" }
		}
		// Past the last boundary the prefix keeps growing with the word instead of clamping.
		assertEquals(40, bionicPrefixLength(49))
		assertEquals(91, bionicPrefixLength(100))
	}
}
