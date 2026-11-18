package org.koitharu.kotatsu.kotatsumigration.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.kotatsumigration.data.UrlRule

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
	fun `url rules reshape kotatsu urls and leave the extension's own urls alone`() {
		val uuid = "e7eabe96-aa17-476f-b431-2497d5e9d060"
		val mangaDex = listOf(rule("^([0-9a-f-]{36})$", "/manga/$1"))
		assertEquals("/manga/$uuid", mangaDex.rewrite(uuid, null, null))
		assertEquals("/manga/$uuid", mangaDex.rewrite("/manga/$uuid", null, null))
		val atsumaru = listOf(rule("^/manga/([^/?#]+)/?$", "$1"))
		assertEquals("abc123", atsumaru.rewrite("/manga/abc123/", null, null))
		assertEquals("abc123", atsumaru.rewrite("abc123", null, null))
	}

	@Test
	fun `public url rules and kotatsu ids`() {
		val webtoons = listOf(
			rule("^\\d+$", "$1", public = "^(/[^/]+/[^/]+/[^/]+/list\\?title_no=\\d+)$"),
			rule("^(\\d+)$", "/episodeList?titleNo=$1"),
		)
		assertEquals(
			"/en/drama/x/list?title_no=95",
			webtoons.rewrite("95", "https://www.webtoons.com/en/drama/x/list?title_no=95", null),
		)
		assertEquals("/episodeList?titleNo=95", webtoons.rewrite("95", "", null))
		val iken = listOf(rule("^/series/([^/?#]+)/?$", "$1#{mangaId}"), rule("^/series/([^/?#]+)/?$", "$1"))
		assertEquals("solo#42", iken.rewrite("/series/solo", null, 42))
		assertEquals("solo", iken.rewrite("/series/solo", null, null))
		assertEquals("solo#42", iken.rewrite("solo#42", null, null))
	}

	private fun rule(url: String, to: String, public: String? = null) = UrlRule(Regex(url), public?.let(::Regex), to)
}
