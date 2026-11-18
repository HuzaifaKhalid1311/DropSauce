package org.koitharu.kotatsu.kotatsumigration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.kotatsumigration.domain.forMihonSource
import org.koitharu.kotatsu.mihon.model.mihonChapterId
import org.koitharu.kotatsu.parsers.model.MangaChapter

class KotatsuMangaMigratorTest {

	@Test
	fun `migrated chapter uses the live Mihon identity`() {
		val legacySource = MissingMangaSource("MANGADEX")
		val mihonSource = MissingMangaSource("MIHON_2499283573021220255")
		val chapter = MangaChapter(
			id = 123L,
			title = "Chapter 100",
			number = 100f,
			volume = 0,
			url = "/chapter/100",
			scanlator = null,
			uploadDate = 0L,
			branch = null,
			source = legacySource,
		)

		val migrated = chapter.forMihonSource(mihonSource, emptyList())

		assertNotEquals(chapter.id, migrated.id)
		assertEquals(mihonChapterId(mihonSource.name, chapter.url), migrated.id)
		assertEquals(mihonSource, migrated.source)
	}
}
