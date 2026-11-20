package org.koitharu.kotatsu.alternatives.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.parsers.model.Manga

class AlternativesMatchTest {

	private val ref = manga("Solo Leveling", altTitles = setOf("Na Honjaman Level Up"))

	@Test
	fun `punctuation, brackets and alt titles still match strictly`() {
		assertTrue(manga("Solo-Leveling (Official)").matchesTitles(ref.normalizedTitles(), loose = false))
		assertTrue(manga("Na Honjaman Level-Up!").matchesTitles(ref.normalizedTitles(), loose = false))
	}

	@Test
	fun `a subtitle only matches loosely`() {
		val sequel = manga("Solo Leveling: Ragnarok")
		assertFalse(sequel.matchesTitles(ref.normalizedTitles(), loose = false))
		assertTrue(sequel.matchesTitles(ref.normalizedTitles(), loose = true))
	}

	@Test
	fun `loose matching needs whole words`() {
		assertFalse(manga("Solo Levelingsss Zone").matchesTitles(ref.normalizedTitles(), loose = true))
		assertFalse(manga("One").matchesTitles(manga("One Piece").normalizedTitles(), loose = true))
	}

	private fun manga(title: String, altTitles: Set<String> = emptySet()) = Manga(
		id = title.hashCode().toLong(),
		title = title,
		altTitles = altTitles,
		url = "/manga",
		publicUrl = "https://example.org/manga",
		rating = 0f,
		contentRating = null,
		coverUrl = null,
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = MissingMangaSource("test"),
	)
}
