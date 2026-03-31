package org.haziffe.dropsauce.reader.ui

import org.haziffe.dropsauce.bookmarks.domain.Bookmark
import org.haziffe.dropsauce.parsers.model.MangaChapter
import org.haziffe.dropsauce.reader.ui.pager.ReaderPage

interface ReaderNavigationCallback {

	fun onPageSelected(page: ReaderPage): Boolean

	fun onChapterSelected(chapter: MangaChapter): Boolean

	fun onBookmarkSelected(bookmark: Bookmark): Boolean = onPageSelected(
		ReaderPage(bookmark.toMangaPage(), bookmark.page, bookmark.chapterId),
	)
}
