package org.haziffe.dropsauce.core.model

import org.haziffe.dropsauce.parsers.model.MangaSource

data class MangaSourceInfo(
	val mangaSource: MangaSource,
	val isEnabled: Boolean,
	val isPinned: Boolean,
) : MangaSource by mangaSource
