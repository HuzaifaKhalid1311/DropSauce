package org.haziffe.dropsauce.history.ui

import android.content.Context
import org.haziffe.dropsauce.core.ui.list.fastscroll.FastScroller
import org.haziffe.dropsauce.list.ui.adapter.MangaListAdapter
import org.haziffe.dropsauce.list.ui.adapter.MangaListListener
import org.haziffe.dropsauce.list.ui.size.ItemSizeResolver

class HistoryListAdapter(
	listener: MangaListListener,
	sizeResolver: ItemSizeResolver,
) : MangaListAdapter(listener, sizeResolver), FastScroller.SectionIndexer {

	override fun getSectionText(context: Context, position: Int): CharSequence? {
		return findHeader(position)?.getText(context)
	}
}
