package org.haziffe.dropsauce.settings.nav.model

import androidx.annotation.StringRes
import org.haziffe.dropsauce.core.prefs.NavItem
import org.haziffe.dropsauce.list.ui.model.ListModel

data class NavItemConfigModel(
	val item: NavItem,
	@StringRes val disabledHintResId: Int,
) : ListModel {

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is NavItemConfigModel && other.item == item
	}
}
