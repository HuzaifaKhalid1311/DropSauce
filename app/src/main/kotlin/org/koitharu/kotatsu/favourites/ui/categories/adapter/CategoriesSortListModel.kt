package org.koitharu.kotatsu.favourites.ui.categories.adapter

import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.ui.model.ListModel

data class CategoriesSortListModel(
	val order: ListSortOrder,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is CategoriesSortListModel
	}
}
