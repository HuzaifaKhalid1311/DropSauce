package org.haziffe.dropsauce.explore.ui.model

import org.haziffe.dropsauce.list.ui.model.ListModel

data class ExploreButtons(
	val isRandomLoading: Boolean,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is ExploreButtons
	}
}
