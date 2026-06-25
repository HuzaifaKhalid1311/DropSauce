package org.koitharu.kotatsu.list.ui.model

data class MissingChapters(
	val id: String,
	val count: Int,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is MissingChapters && id == other.id
	}
}
