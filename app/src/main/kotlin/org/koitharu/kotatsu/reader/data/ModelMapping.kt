package org.haziffe.dropsauce.reader.data

import org.haziffe.dropsauce.parsers.model.Manga
import org.haziffe.dropsauce.parsers.model.MangaChapter

fun Manga.filterChapters(branch: String?): Manga {
	if (chapters.isNullOrEmpty()) return this
	return withChapters(chapters = chapters?.filter { it.branch == branch })
}

private fun Manga.withChapters(chapters: List<MangaChapter>?) = copy(
	chapters = chapters,
)
