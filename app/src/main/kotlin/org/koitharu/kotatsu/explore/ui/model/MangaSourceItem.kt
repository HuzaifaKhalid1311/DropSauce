package org.haziffe.dropsauce.explore.ui.model

import org.haziffe.dropsauce.core.model.MangaSourceInfo
import org.haziffe.dropsauce.list.ui.model.ListModel
import org.haziffe.dropsauce.parsers.util.longHashCode

data class MangaSourceItem(
	val source: MangaSourceInfo,
	val isGrid: Boolean,
) : ListModel {

	val id: Long = source.name.longHashCode()

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is MangaSourceItem && other.source == source
	}
}
