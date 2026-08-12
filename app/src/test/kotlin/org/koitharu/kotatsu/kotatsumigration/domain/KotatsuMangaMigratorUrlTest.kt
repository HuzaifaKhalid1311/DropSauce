package org.koitharu.kotatsu.kotatsumigration.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class KotatsuMangaMigratorUrlTest {

	@Test
	fun `absolute kotatsu urls are reduced to the relative form mihon extensions expect`() {
		assertEquals("/manga/One-Piece", "https://demonicscans.org/manga/One-Piece".toMihonUrl())
		assertEquals("/manga.php?manga=One-Piece", "http://demonicscans.org/manga.php?manga=One-Piece".toMihonUrl())
		assertEquals("/", "https://demonicscans.org".toMihonUrl())
	}

	@Test
	fun `relative and opaque urls are left untouched`() {
		assertEquals("/manga/One-Piece", "/manga/One-Piece".toMihonUrl())
		assertEquals("manga/One-Piece", "manga/One-Piece".toMihonUrl())
		assertEquals("abcd-1234", "abcd-1234".toMihonUrl())
	}
}
