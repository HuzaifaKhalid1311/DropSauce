package org.koitharu.kotatsu.list.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode.NONE
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode.PERCENT_READ

class ReadingProgressTest {

	@Test
	fun `percent mode accepts only normalized progress`() {
		assertTrue(readingProgress(percent = 0.4f, mode = PERCENT_READ).isValid())
		assertFalse(readingProgress(percent = 1.1f, mode = PERCENT_READ).isValid())
	}

	@Test
	fun `none mode disables progress`() {
		assertFalse(readingProgress(percent = 0.4f, mode = NONE).isValid())
	}

	@Test
	fun `percent strings are clamped to whole percentages`() {
		assertEquals("0", ReadingProgress.percentToString(ReadingProgress.PROGRESS_NONE))
		assertEquals("40", ReadingProgress.percentToString(0.4f))
		assertEquals("100", ReadingProgress.percentToString(ReadingProgress.PROGRESS_COMPLETED))
	}

	@Test
	fun `multi chapter progress remains chapter based`() {
		assertEquals(
			0.5f,
			ReadingProgress.calculatePercent(
				chapterIndex = 1,
				chaptersCount = 4,
				pageIndex = 0,
				pagesCount = 20,
			),
		)
	}

	@Test
	fun `single chapter progress starts at zero`() {
		assertEquals(
			0f,
			ReadingProgress.calculatePercent(
				chapterIndex = 0,
				chaptersCount = 1,
				pageIndex = 0,
				pagesCount = 11,
			),
		)
	}

	@Test
	fun `single chapter progress follows its pages`() {
		assertEquals(
			0.5f,
			ReadingProgress.calculatePercent(
				chapterIndex = 0,
				chaptersCount = 1,
				pageIndex = 5,
				pagesCount = 11,
			),
		)
	}

	@Test
	fun `single chapter progress ends at one hundred percent`() {
		assertEquals(
			1f,
			ReadingProgress.calculatePercent(
				chapterIndex = 0,
				chaptersCount = 1,
				pageIndex = 10,
				pagesCount = 11,
			),
		)
	}

	@Test
	fun `one page chapter is complete once displayed`() {
		assertEquals(
			1f,
			ReadingProgress.calculatePercent(
				chapterIndex = 0,
				chaptersCount = 1,
				pageIndex = 0,
				pagesCount = 1,
			),
		)
	}

	@Test
	fun `invalid chapter position has no progress`() {
		assertEquals(
			ReadingProgress.PROGRESS_NONE,
			ReadingProgress.calculatePercent(
				chapterIndex = -1,
				chaptersCount = 1,
				pageIndex = 0,
				pagesCount = 10,
			),
		)
	}

	private fun readingProgress(
		percent: Float,
		mode: ProgressIndicatorMode,
	) = ReadingProgress(
		percent = percent,
		totalChapters = 10,
		mode = mode,
	)
}
