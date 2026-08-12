package org.koitharu.kotatsu.kotatsumigration.domain

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feeds OkHttp's real parse of a `baseUrl + absoluteUrl` concatenation into [isGluedUrl], because the
 * two glue shapes normalize differently and only one of them corrupts the host.
 */
class GluedUrlProbeTest {

	private val stored = "https://demonicscans.org/manga/One-Piece"

	private fun detect(baseUrl: String, url: String): Boolean {
		val request = (baseUrl + url).toHttpUrl()
		return isGluedUrl(baseUrl.toHttpUrl().host, request.host, request.encodedPath)
	}

	@Test
	fun `glued url is detected whether or not baseUrl ends in a slash`() {
		// host becomes "demonicscans.orghttps"
		assertTrue(detect("https://demonicscans.org", stored))
		// host stays valid, the whole url lands in the path
		assertTrue(detect("https://demonicscans.org/", stored))
	}

	@Test
	fun `a source requesting its own url unchanged is left alone`() {
		val request = stored.toHttpUrl()
		assertFalse(isGluedUrl("demonicscans.org", request.host, request.encodedPath))
		// a sibling api host must not be mistaken for appended junk
		assertFalse(isGluedUrl("mangadex.org", "api.mangadex.org", "/manga/uuid"))
	}

	@Test
	fun `normal relative resolution is healthy`() {
		assertFalse(detect("https://demonicscans.org", "/manga/One-Piece"))
		assertFalse(detect("https://demonicscans.org/", "manga/One-Piece"))
	}
}
