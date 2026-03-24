package org.koitharu.kotatsu.list.ui.adapter

import android.view.View
import org.koitharu.kotatsu.list.ui.model.ListHeader

interface ListHeaderClickListener {

	fun onListHeaderClick(item: ListHeader, view: View)

	fun onListHeaderFilterModeChanged(item: ListHeader, mode: org.koitharu.kotatsu.explore.ui.SourceFilterMode) {}
}
