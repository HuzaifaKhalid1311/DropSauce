package org.koitharu.kotatsu.history.ui

import android.content.Context
import org.koitharu.kotatsu.core.ui.list.fastscroll.FastScroller
import org.koitharu.kotatsu.list.ui.adapter.MangaListAdapter
import org.koitharu.kotatsu.list.ui.adapter.MangaListListener
import org.koitharu.kotatsu.list.ui.model.TipModel
import org.koitharu.kotatsu.list.ui.size.ItemSizeResolver

class HistoryListAdapter(
	listener: MangaListListener,
	sizeResolver: ItemSizeResolver,
	titleTapToRead: Boolean = false,
	onTipClose: ((TipModel) -> Unit)? = null,
) : MangaListAdapter(listener, sizeResolver, titleTapToRead, onTipClose), FastScroller.SectionIndexer {

	override fun getSectionText(context: Context, position: Int): CharSequence? {
		return findHeader(position)?.getText(context)
	}
}
