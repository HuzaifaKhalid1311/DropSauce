package org.haziffe.dropsauce.favourites.domain.model

import org.haziffe.dropsauce.core.model.MangaSource

data class Cover(
	val url: String?,
	val source: String,
) {
	val mangaSource by lazy { MangaSource(source) }
}
