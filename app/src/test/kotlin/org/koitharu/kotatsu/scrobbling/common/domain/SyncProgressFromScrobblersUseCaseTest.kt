package org.koitharu.kotatsu.scrobbling.common.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.parsers.model.MangaChapter

class SyncProgressFromScrobblersUseCaseTest {

	@Test
	fun `remote progress resolves the last continuous local chapter`() {
		val chapters = listOf(chapter(1, 1f), chapter(2, 2f), chapter(3, 3f))

		assertEquals(1, findRemoteProgressIndex(chapters, remoteChapter = 2))
	}

	@Test
	fun `unnumbered chapters use their source order`() {
		val chapters = listOf(chapter(1, 0f), chapter(2, 0f), chapter(3, 0f))

		assertEquals(1, findRemoteProgressIndex(chapters, remoteChapter = 2))
	}

	@Test
	fun `abnormal chapter order stops progress instead of skipping ahead`() {
		val chapters = listOf(chapter(1, 1f), chapter(3, 3f), chapter(2, 2f), chapter(4, 4f))

		assertEquals(1, findRemoteProgressIndex(chapters, remoteChapter = 4))
	}

	@Test
	fun `zero remote progress does not create local history`() {
		assertEquals(-1, findRemoteProgressIndex(listOf(chapter(1, 1f)), remoteChapter = 0))
	}

	private fun chapter(id: Long, number: Float) = MangaChapter(
		id = id,
		title = "Chapter $number",
		number = number,
		volume = 0,
		url = "/chapter/$id",
		scanlator = null,
		uploadDate = 0L,
		branch = null,
		source = MissingMangaSource("TEST"),
	)
}
