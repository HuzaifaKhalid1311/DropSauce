package org.koitharu.kotatsu.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.util.AlphanumComparator

class AlphanumComparatorTest {

	private val comparator = AlphanumComparator()

	@Test
	fun `sorts numbers in natural numeric order`() {
		val list = listOf("Chapter 10", "Chapter 1", "Chapter 2", "Chapter 20", "Chapter 3")
		val sorted = list.sortedWith(comparator)
		assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3", "Chapter 10", "Chapter 20"), sorted)
	}

	@Test
	fun `handles fractional chapter numbers`() {
		val list = listOf("10.5", "10.1", "10", "11", "9")
		val sorted = list.sortedWith(comparator)
		assertEquals(listOf("9", "10", "10.1", "10.5", "11"), sorted)
	}

	@Test
	fun `handles identical strings and nulls`() {
		assertEquals(0, comparator.compare("Chapter 1", "Chapter 1"))
		assertEquals(0, comparator.compare(null, null))
		assertEquals(0, comparator.compare("Chapter 1", null))
	}

	@Test
	fun `handles mixed text and numbers`() {
		assertTrue(comparator.compare("A 1", "B 1") < 0)
		assertTrue(comparator.compare("Chapter 2a", "Chapter 2b") < 0)
		assertTrue(comparator.compare("Chapter 100", "Chapter 99") > 0)
	}
}
