package org.koitharu.kotatsu.reader.ui.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubChapterLinkTest {

	private val book = "file+zip:///storage/emulated/0/Books/My Book.epub"
	private val chapters = listOf(
		"$book#OEBPS/Text/toc.xhtml",
		"$book#OEBPS/Text/chapter 1.xhtml",
		"$book#OEBPS/Text/chapter2.xhtml",
	)

	@Test
	fun `toc entry resolves to a sibling chapter`() {
		assertEquals(
			2,
			resolveChapterLink(chapters[0], "chapter2.xhtml", chapters),
		)
	}

	@Test
	fun `percent encoded href resolves to an entry with a space`() {
		assertEquals(
			1,
			resolveChapterLink(chapters[0], "chapter%201.xhtml#heading", chapters),
		)
	}

	@Test
	fun `href relative to the opf root resolves`() {
		assertEquals(
			2,
			resolveChapterLink(chapters[0], "../Text/chapter2.xhtml", chapters),
		)
	}

	@Test
	fun `anchor only href stays in the current chapter`() {
		assertEquals(
			0,
			resolveChapterLink(chapters[0], "#section-3", chapters),
		)
	}

	@Test
	fun `link into a different book is not followed`() {
		val other = "file+zip:///storage/emulated/0/Books/Other.epub#OEBPS/Text/chapter2.xhtml"
		assertNull(resolveChapterLink(other, "chapter2.xhtml", chapters))
	}

	@Test
	fun `external link is not a chapter`() {
		assertNull(resolveChapterLink(chapters[0], "https://example.com/page", chapters))
	}
}
