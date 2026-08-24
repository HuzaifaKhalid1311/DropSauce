package org.koitharu.kotatsu.scrobbling.common.domain.model

class ScrobblerMangaInfo(
	val id: Long,
	val name: String,
	val cover: String,
	val url: String,
	val descriptionHtml: String,
	/** Total chapters as the tracker knows them, or 0 when it does not publish a count. */
	val totalChapters: Int,
)
