package org.koitharu.kotatsu.explore.ui.model

import org.koitharu.kotatsu.list.ui.model.ListModel

/**
 * Explore's extension header. Not a [org.koitharu.kotatsu.list.ui.model.ListHeader] because it also
 * carries the manga/novel filter chips, which no other screen has.
 */
data class ExtensionsHeaderItem(
	val isNovel: Boolean,
	val hasUpdates: Boolean,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean = other is ExtensionsHeaderItem
}
