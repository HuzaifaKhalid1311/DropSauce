package org.haziffe.dropsauce.bookmarks.domain

import org.haziffe.dropsauce.core.util.MimeTypes
import org.haziffe.dropsauce.core.util.ext.isImage
import org.haziffe.dropsauce.list.ui.model.ListModel
import org.haziffe.dropsauce.parsers.model.Manga
import org.haziffe.dropsauce.parsers.model.MangaPage
import java.time.Instant

data class Bookmark(
	val manga: Manga,
	val pageId: Long,
	val chapterId: Long,
	val page: Int,
	val scroll: Int,
	val imageUrl: String,
	val createdAt: Instant,
	val percent: Float,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is Bookmark &&
			manga.id == other.manga.id &&
			chapterId == other.chapterId &&
			page == other.page
	}

	fun toMangaPage() = MangaPage(
		id = pageId,
		url = imageUrl,
		preview = imageUrl.takeIf {
			MimeTypes.getMimeTypeFromUrl(it)?.isImage == true
		},
		source = manga.source,
	)
}
