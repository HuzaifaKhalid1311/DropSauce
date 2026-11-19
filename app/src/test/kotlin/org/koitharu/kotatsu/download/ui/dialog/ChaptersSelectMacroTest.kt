package org.koitharu.kotatsu.download.ui.dialog

import androidx.collection.MutableLongLongMap
import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.parsers.model.MangaChapter

class ChaptersSelectMacroTest {

	@Test
	fun `unread chapters without history starts from first chapter in preferred branch`() {
		val chapters = listOf(
			chapter(1L, 1f, "A"),
			chapter(2L, 1f, "B"),
			chapter(3L, 2f, "A"),
			chapter(4L, 2f, "B"),
			chapter(5L, 3f, "A"),
		)
		val macro = ChaptersSelectMacro.UnreadChapters(
			chaptersCount = 2,
			maxAvailableCount = 3,
			currentChaptersIds = MutableLongLongMap(0),
			defaultBranches = mapOf(10L to "A"),
		)

		assertEquals(setOf(1L, 3L), macro.getChaptersIds(10L, chapters))
	}

	@Test
	fun `unread chapters with history excludes read chapters and other branches`() {
		val chapters = listOf(
			chapter(1L, 1f, "A"),
			chapter(2L, 1f, "B"),
			chapter(3L, 2f, "A"),
			chapter(4L, 2f, "B"),
			chapter(5L, 3f, "A"),
			chapter(6L, 4f, "A"),
		)
		val historyIds = MutableLongLongMap(1).apply {
			set(10L, 1L)
		}
		val macro = ChaptersSelectMacro.UnreadChapters(
			chaptersCount = 2,
			maxAvailableCount = 2,
			currentChaptersIds = historyIds,
			defaultBranches = mapOf(10L to "A"),
		)

		assertEquals(setOf(3L, 5L), macro.getChaptersIds(10L, chapters))
	}

	@Test
	fun `unread chapters falls back to preferred branch if history chapter is missing`() {
		val chapters = listOf(
			chapter(101L, 1f, "EN"),
			chapter(102L, 1f, "ES"),
			chapter(103L, 2f, "EN"),
		)
		val historyIds = MutableLongLongMap(1).apply {
			set(10L, 999L)
		}
		val macro = ChaptersSelectMacro.UnreadChapters(
			chaptersCount = 5,
			maxAvailableCount = 2,
			currentChaptersIds = historyIds,
			defaultBranches = mapOf(10L to "EN"),
		)

		assertEquals(setOf(101L, 103L), macro.getChaptersIds(10L, chapters))
	}

	private fun chapter(id: Long, number: Float, branch: String?) = MangaChapter(
		id = id,
		title = "Chapter $number",
		number = number,
		volume = 0,
		url = "/chapter/$id",
		scanlator = null,
		uploadDate = 0L,
		branch = branch,
		source = MissingMangaSource("TEST"),
	)
}
