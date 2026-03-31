package org.haziffe.dropsauce.history.domain.model

import org.haziffe.dropsauce.core.model.MangaHistory
import org.haziffe.dropsauce.parsers.model.Manga

data class MangaWithHistory(
	val manga: Manga,
	val history: MangaHistory
)
