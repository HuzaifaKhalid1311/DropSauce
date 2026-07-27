package org.koitharu.kotatsu.filter.ui.mihon

import org.koitharu.kotatsu.filter.ui.mihon.model.MihonFilterItem

/** Callbacks from the dynamic Mihon filter sheet rows. */
interface MihonFilterListener {
	fun onCheckBoxClick(item: MihonFilterItem.CheckBox)
	fun onCheckBoxChipClick(checkBoxPath: String)
	fun onTriStateClick(item: MihonFilterItem.TriState)
	fun onTextChanged(item: MihonFilterItem.Text, value: String)
	fun onSelectChanged(item: MihonFilterItem.Select, index: Int)
	fun onExpandClick(item: MihonFilterItem.ExpandableHeader)
	fun onSortOptionClick(item: MihonFilterItem.SortOption)
}
