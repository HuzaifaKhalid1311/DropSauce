package org.koitharu.kotatsu.reader.ui.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubLinkResolverTest {

	@Test
	fun `epub toc link resolves relative chapter path`() {
		val chapters = listOf(
			"file:/books/story.epub#OEBPS/Text/toc.xhtml",
			"file:/books/story.epub#OEBPS/Text/chapter-1.xhtml",
		)

		assertEquals(1, resolveChapterLink(chapters[0], "chapter-1.xhtml#opening", chapters))
	}

	@Test
	fun `same document anchor stays in current chapter`() {
		val chapters = listOf("file:/books/story.epub#OEBPS/Text/chapter-1.xhtml")

		assertEquals(0, resolveChapterLink(chapters[0], "#footnote-1", chapters))
	}

	@Test
	fun `root relative toc path resolves encoded chapter entry`() {
		val chapters = listOf(
			"file:/books/story.epub#OEBPS/nav.xhtml",
			"file:/books/story.epub#OEBPS/Text/Chapter%202.xhtml",
		)

		assertEquals(1, resolveChapterLink(chapters[0], "/OEBPS/Text/Chapter%202.xhtml", chapters))
	}

	@Test
	fun `external url is not treated as a chapter`() {
		val chapters = listOf("https://novel.example/chapter-1")

		assertNull(resolveChapterLink(chapters[0], "https://author.example", chapters))
	}
}
