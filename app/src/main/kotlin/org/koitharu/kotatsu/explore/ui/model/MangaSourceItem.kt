package org.koitharu.kotatsu.explore.ui.model

import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.util.longHashCode

data class MangaSourceItem(
	val source: MangaSourceInfo,
	val isGrid: Boolean,
	/** A newer build of this source's extension is waiting in its store. */
	val hasUpdate: Boolean = false,
) : ListModel {

	val id: Long = source.name.longHashCode()

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is MangaSourceItem && other.source == source
	}
}
