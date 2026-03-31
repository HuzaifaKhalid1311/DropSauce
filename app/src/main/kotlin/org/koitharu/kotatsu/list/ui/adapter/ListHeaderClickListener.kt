package org.haziffe.dropsauce.list.ui.adapter

import android.view.View
import org.haziffe.dropsauce.list.ui.model.ListHeader

interface ListHeaderClickListener {

	fun onListHeaderClick(item: ListHeader, view: View)

	fun onListHeaderFilterModeChanged(item: ListHeader, mode: org.haziffe.dropsauce.explore.ui.SourceFilterMode) {}
}
