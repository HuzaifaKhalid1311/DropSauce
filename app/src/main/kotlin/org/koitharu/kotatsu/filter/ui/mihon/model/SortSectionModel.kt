package org.koitharu.kotatsu.filter.ui.mihon.model

import org.koitharu.kotatsu.list.ui.ListModelDiffCallback.Companion.PAYLOAD_CHECKED_CHANGED
import org.koitharu.kotatsu.list.ui.model.ListModel

/** The collapsible header of the source's own sort options in the sort picker. */
class SortSectionModel(
	val title: String,
	val isExpanded: Boolean,
) : ListModel {

	override fun areItemsTheSame(other: ListModel) = other is SortSectionModel

	override fun equals(other: Any?) =
		other is SortSectionModel && other.title == title && other.isExpanded == isExpanded

	override fun hashCode() = title.hashCode() * 31 + isExpanded.hashCode()

	override fun getChangePayload(previousState: ListModel) =
		if (previousState is SortSectionModel && previousState.isExpanded != isExpanded) PAYLOAD_CHECKED_CHANGED else null
}
