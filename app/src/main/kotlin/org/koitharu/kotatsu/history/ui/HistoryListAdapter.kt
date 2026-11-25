package org.koitharu.kotatsu.history.ui

import android.content.Context
import coil3.ImageLoader
import org.koitharu.kotatsu.core.ui.list.fastscroll.FastScroller
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.MangaListAdapter
import org.koitharu.kotatsu.list.ui.adapter.MangaListListener
import org.koitharu.kotatsu.list.ui.model.TipModel
import org.koitharu.kotatsu.list.ui.size.ItemSizeResolver

class HistoryListAdapter(
	listener: MangaListListener,
	sectionListener: HistorySectionListener,
	selection: HistorySelectionState,
	imageLoader: ImageLoader,
	sizeResolver: ItemSizeResolver,
	titleTapToRead: Boolean = false,
	onTipClose: ((TipModel) -> Unit)? = null,
) : MangaListAdapter(listener, sizeResolver, titleTapToRead, onTipClose), FastScroller.SectionIndexer {

	init {
		// Same view types as the shared list, so spans and spacing stay as they were.
		delegatesManager.addDelegate(ListItemType.HEADER.ordinal, true, historySectionAD(sectionListener, selection, imageLoader))
		delegatesManager.addDelegate(ListItemType.QUICK_FILTER.ordinal, true, historyQuickFilterAD(listener, imageLoader))
	}

	override fun getSectionText(context: Context, position: Int): CharSequence? {
		val snapshot = items
		for (i in position downTo 0) {
			(snapshot.getOrNull(i) as? HistorySection)?.let { return it.getTitle(context) }
		}
		return null
	}
}
