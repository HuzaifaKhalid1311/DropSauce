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

	@Test
	fun `url templates reshape kotatsu urls and leave converted ones alone`() {
		val uuid = "e7eabe96-aa17-476f-b431-2497d5e9d060"
		assertEquals("/manga/$uuid", uuid.toMihonUrl("/manga/{id}"))
		assertEquals("/manga/$uuid", "/manga/$uuid".toMihonUrl("/manga/{id}"))
		assertEquals("/chapter/$uuid", uuid.toMihonUrl("/chapter/{id}"))
		assertEquals("abc123", "/manga/abc123".toMihonUrl("{id}"))
		assertEquals("abc123", "abc123".toMihonUrl("{id}"))
		assertEquals("abc123", "https://atsu.moe/manga/abc123/".toMihonUrl("{id}"))
	}
}
