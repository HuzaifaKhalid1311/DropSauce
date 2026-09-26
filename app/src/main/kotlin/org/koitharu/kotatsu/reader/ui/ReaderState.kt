package org.koitharu.kotatsu.reader.ui

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.parsers.model.Manga

@Parcelize
data class ReaderState(
	val chapterId: Long,
	val page: Int,
	val scroll: Int,
) : Parcelable {

	constructor(history: MangaHistory) : this(
		chapterId = history.chapterId,
		page = history.page,
		scroll = history.scroll,
	)

	constructor(manga: Manga, branch: String?) : this(
		chapterId = manga.chapters?.let {
			it.firstOrNull { x -> x.branch == branch } ?: it.firstOrNull()
		}?.id ?: error("Cannot find first chapter"),
		page = 0,
		scroll = 0,
	)

	companion object {

		// EPUB progress: scroll >= 0 is a legacy 0..1000 permille value, negative values
		// encode an exact character offset within the chapter (lossless restore)
		fun encodeEpubOffset(offset: Int): Int = -offset - 1

		fun decodeEpubOffset(scroll: Int): Int? = if (scroll < 0) -scroll - 1 else null

		// EPUB `page` marker for a highlight jump: centre the offset instead of pinning it to the top.
		// The reader overwrites `page` with its real position on the next progress report.
		const val EPUB_PAGE_CENTERED = -1
	}
}
