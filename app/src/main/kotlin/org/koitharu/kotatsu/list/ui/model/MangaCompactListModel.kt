package org.haziffe.dropsauce.list.ui.model

import org.haziffe.dropsauce.core.ui.model.MangaOverride
import org.haziffe.dropsauce.parsers.model.Manga

data class MangaCompactListModel(
	override val manga: Manga,
	override val override: MangaOverride?,
	val subtitle: String,
	override val counter: Int,
) : MangaListModel()
