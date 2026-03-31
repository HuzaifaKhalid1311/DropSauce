package org.haziffe.dropsauce.list.ui.adapter

import android.view.View
import org.haziffe.dropsauce.core.ui.widgets.TipView

interface MangaListListener : MangaDetailsClickListener, ListStateHolderListener, ListHeaderClickListener,
	TipView.OnButtonClickListener, QuickFilterClickListener {

	fun onFilterClick(view: View?)
}
