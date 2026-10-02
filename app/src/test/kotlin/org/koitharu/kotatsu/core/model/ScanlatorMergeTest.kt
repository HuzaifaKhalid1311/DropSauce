package org.koitharu.kotatsu.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.MangaChapter

class ScanlatorMergeTest {

	private val chapters = listOf(
		chapter(1, 2f, "A"), chapter(2, 1f, "B"), chapter(3, 3f, "A"), chapter(4, 1f, "C"),
	)

	@Test
	fun `none keeps every branch`() {
		assertSame(chapters, chapters.mergedBranches(ScanlatorMerge.None))
	}

	@Test
	fun `all folds into one nameless branch sorted by number`() {
		val merged = chapters.mergedBranches(ScanlatorMerge.All)
		assertEquals(setOf(null), merged.mapTo(HashSet()) { it.branch })
		assertEquals(listOf(1f, 1f, 2f, 3f), merged.map { it.number })
	}

	@Test
	fun `some folds only the picked branches and keeps the rest`() {
		val merged = chapters.mergedBranches(ScanlatorMerge.Some(setOf("A", "B")))
		assertEquals(listOf(4L), merged.filter { it.branch == "C" }.map { it.id })
		assertEquals(listOf(2L, 1L, 3L), merged.filter { it.branch == "A + B" }.map { it.id })
	}

	@Test
	fun `some covering every branch behaves like all`() {
		val merged = chapters.mergedBranches(ScanlatorMerge.Some(setOf("A", "B", "C")))
		assertEquals(setOf(null), merged.mapTo(HashSet()) { it.branch })
	}

	@Test
	fun `some with a single present branch changes nothing`() {
		assertSame(chapters, chapters.mergedBranches(ScanlatorMerge.Some(setOf("A", "gone"))))
	}

	private fun chapter(id: Long, number: Float, branch: String?) = MangaChapter(
		id = id,
		title = null,
		number = number,
		volume = 0,
		url = "/$id",
		scanlator = null,
		uploadDate = 0L,
		branch = branch,
		source = MissingMangaSource("TEST"),
	)
}
