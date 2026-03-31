package org.haziffe.dropsauce.list.ui.model

import org.haziffe.dropsauce.core.ui.widgets.ChipsView
import org.haziffe.dropsauce.list.ui.ListModelDiffCallback

data class QuickFilter(
	val items: List<ChipsView.ChipModel>,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean = other is QuickFilter

	override fun getChangePayload(previousState: ListModel) = ListModelDiffCallback.PAYLOAD_NESTED_LIST_CHANGED
}
