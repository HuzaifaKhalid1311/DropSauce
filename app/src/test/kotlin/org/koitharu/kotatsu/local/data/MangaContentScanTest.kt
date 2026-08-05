package org.koitharu.kotatsu.local.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MangaContentScanTest {

	@get:Rule
	val folder = TemporaryFolder()

	@Test
	fun `folder of loose pages is manga`() {
		val dir = folder.newFolder("Some Manga")
		File(dir, "001.jpg").createNewFile()

		assertTrue(dir.hasMangaContent())
	}

	@Test
	fun `folder of archived chapters is manga`() {
		val dir = folder.newFolder("Archived Manga")
		File(dir, "chapter 1.cbz").createNewFile()

		assertTrue(dir.hasMangaContent())
	}

	@Test
	fun `downloaded folder with only an index is manga`() {
		val dir = folder.newFolder("Downloaded")
		File(dir, "index.json").createNewFile()

		assertTrue(dir.hasMangaContent())
	}

	@Test
	fun `pages nested under volume and chapter are found`() {
		val dir = folder.newFolder("Deep Manga", "Volume 1", "Chapter 1")
		File(dir, "page.png").createNewFile()

		assertTrue(folder.root.resolve("Deep Manga").hasMangaContent())
	}

	@Test
	fun `app data folders are not manga`() {
		val fonts = folder.newFolder("fonts")
		File(fonts, "NotoSans.ttf").createNewFile()
		val plugins = folder.newFolder("lnreader_plugins", "someplugin")
		File(plugins, "index.js").createNewFile()

		assertFalse(fonts.hasMangaContent())
		assertFalse(folder.root.resolve("lnreader_plugins").hasMangaContent())
	}

	@Test
	fun `empty folder is not manga`() {
		assertFalse(folder.newFolder("empty").hasMangaContent())
	}
}
