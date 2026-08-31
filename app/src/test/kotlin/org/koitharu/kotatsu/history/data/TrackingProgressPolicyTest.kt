package org.koitharu.kotatsu.history.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.parsers.model.MangaChapter

class TrackingProgressPolicyTest {

	private val chapters = listOf(chapter(1), chapter(2), chapter(3))

	@Test
	fun `tracking creates progress when no history exists`() {
		assertTrue(canAdvanceFromTracking(null, chapters, targetIndex = 1))
	}

	@Test
	fun `tracking preserves explicitly deleted history`() {
		assertFalse(canAdvanceFromTracking(history(chapterId = 1, deletedAt = 1), chapters, targetIndex = 1))
	}

	@Test
	fun `tracking never moves newer local progress backward`() {
		assertFalse(canAdvanceFromTracking(history(chapterId = 3), chapters, targetIndex = 1))
	}

	@Test
	fun `tracking never moves behind progress made on another branch`() {
		val otherBranch = listOf(chapter(99, number = 3f))
		assertFalse(canAdvanceFromTracking(history(chapterId = 99), chapters, targetIndex = 1, chapters + otherBranch))
	}

	@Test
	fun `tracking advances past progress made on another branch`() {
		val otherBranch = listOf(chapter(99, number = 1f))
		assertTrue(canAdvanceFromTracking(history(chapterId = 99), chapters, targetIndex = 1, chapters + otherBranch))
	}

	@Test
	fun `tracking advances when the current chapter no longer exists`() {
		assertTrue(canAdvanceFromTracking(history(chapterId = 99), chapters, targetIndex = 1))
	}

	private fun history(chapterId: Long, deletedAt: Long = 0) = HistoryEntity(
		mangaId = 1,
		createdAt = 1,
		updatedAt = 1,
		chapterId = chapterId,
		page = 0,
		scroll = 0f,
		percent = 0f,
		deletedAt = deletedAt,
		chaptersCount = chapters.size,
	)

	private fun chapter(id: Long, number: Float = id.toFloat()) = MangaChapter(
		id = id,
		title = "Chapter $id",
		number = number,
		volume = 0,
		url = "/chapter/$id",
		scanlator = null,
		uploadDate = 0L,
		branch = null,
		source = MissingMangaSource("TEST"),
	)
}
