package org.koitharu.kotatsu.mihon

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.core.model.ContentWarning
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.mihon.model.toManga
import org.koitharu.kotatsu.parsers.model.ContentRating

class MihonDataConvertersTest {

	@Test
	fun `nsfw extension does not mark every manga adult`() {
		val manga = SManga.create().apply {
			url = "/safe-title"
			title = "Safe title"
			genre = "Action, Comedy"
		}

		assertNull(manga.toManga(source(ContentWarning.NSFW)).contentRating)
	}

	@Test
	fun `adult title genre still marks manga adult`() {
		val manga = SManga.create().apply {
			url = "/adult-title"
			title = "Adult title"
			genre = "Romance, 18+"
		}

		assertEquals(ContentRating.ADULT, manga.toManga(source(ContentWarning.NSFW)).contentRating)
	}

	private fun source(contentWarning: ContentWarning) = MihonMangaSource(
		catalogueSource = object : CatalogueSource {
			override val id = 1L
			override val name = "Test"
			override val lang = "en"
			override val supportsLatest = false
		},
		pkgName = "test.extension",
		contentWarning = contentWarning,
	)
}
