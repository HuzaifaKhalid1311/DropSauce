package org.haziffe.dropsauce.settings.nav.model

import org.haziffe.dropsauce.list.ui.model.ListModel

data class NavItemAddModel(
	val canAdd: Boolean,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean = other is NavItemAddModel
}
