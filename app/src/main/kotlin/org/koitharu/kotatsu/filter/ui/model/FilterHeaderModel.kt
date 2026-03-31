package org.haziffe.dropsauce.filter.ui.model

import org.haziffe.dropsauce.core.ui.widgets.ChipsView
import org.haziffe.dropsauce.parsers.model.SortOrder

data class FilterHeaderModel(
	val chips: Collection<ChipsView.ChipModel>,
	val sortOrder: SortOrder?,
	val isFilterApplied: Boolean,
) {

	val textSummary: String
		get() = chips.mapNotNull { if (it.isChecked) it.title else null }.joinToString()
}
