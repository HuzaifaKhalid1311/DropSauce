package org.koitharu.kotatsu.list.ui.model

import eu.kanade.tachiyomi.source.CatalogueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.core.model.ContentWarning
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback.Companion.PAYLOAD_ANYTHING_CHANGED
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource

class MangaListModelTest {

	private val resolved = MihonMangaSource(
		catalogueSource = object : CatalogueSource {
			override val id = 1L
			override val name = "Test"
			override val lang = "en"
			override val supportsLatest = false
		},
		pkgName = "test.extension",
		contentWarning = ContentWarning.SAFE,
	)

	@Test
	fun `resolving the source rebinds in place`() {
		val before = model(manga(MissingMangaSource(resolved.name)))
		assertEquals(PAYLOAD_ANYTHING_CHANGED, model(manga(resolved)).getChangePayload(before))
	}

	@Test
	fun `another manga change keeps the change animation`() {
		val before = model(manga(MissingMangaSource(resolved.name)))
		assertNull(model(manga(resolved).copy(title = "Renamed")).getChangePayload(before))
	}

	private fun model(manga: Manga) = MangaGridModel(
		manga = manga,
		override = null,
		counter = 0,
		progress = null,
		isFavorite = false,
		isSaved = false,
	)

	private fun manga(source: MangaSource) = Manga(
		id = 1L,
		title = "Title",
		altTitles = emptySet(),
		url = "/manga",
		publicUrl = "https://example.org/manga",
		rating = 0f,
		contentRating = null,
		coverUrl = "https://example.org/cover.jpg",
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = source,
	)
}
