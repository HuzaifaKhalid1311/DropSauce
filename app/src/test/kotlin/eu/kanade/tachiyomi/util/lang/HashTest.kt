package eu.kanade.tachiyomi.util.lang

import org.junit.Assert.assertEquals
import org.junit.Test

class HashTest {

	@Test
	fun `sha256 of empty string`() {
		assertEquals(
			"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
			Hash.sha256("")
		)
	}

	@Test
	fun `sha256 of basic string`() {
		assertEquals(
			"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
			Hash.sha256("test")
		)
	}

	@Test
	fun `sha256 of string with spaces and special characters`() {
		assertEquals(
			"230cd5d039fa491794b022261091f53de649487ab2a2dabec7dee37a67beda0a",
			Hash.sha256("Hello, World! 123")
		)
	}

	@Test
	fun `sha256 of byte array`() {
		assertEquals(
			"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
			Hash.sha256("test".toByteArray())
		)
	}

	@Test
	fun `md5 of empty string`() {
		assertEquals(
			"d41d8cd98f00b204e9800998ecf8427e",
			Hash.md5("")
		)
	}

	@Test
	fun `md5 of basic string`() {
		assertEquals(
			"098f6bcd4621d373cade4e832627b4f6",
			Hash.md5("test")
		)
	}

	@Test
	fun `md5 of string with spaces and special characters`() {
		assertEquals(
			"8a732eef6124eb6b5aa8c0af2318fdbb",
			Hash.md5("Hello, World! 123")
		)
	}

	@Test
	fun `md5 of byte array`() {
		assertEquals(
			"098f6bcd4621d373cade4e832627b4f6",
			Hash.md5("test".toByteArray())
		)
	}
}
