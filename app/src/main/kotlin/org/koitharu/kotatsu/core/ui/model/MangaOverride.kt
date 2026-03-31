package org.haziffe.dropsauce.core.ui.model

import org.haziffe.dropsauce.parsers.model.ContentRating

data class MangaOverride(
	val coverUrl: String?,
	val title: String?,
	val contentRating: ContentRating?,
)
