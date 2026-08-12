package org.koitharu.kotatsu.list.ui.adapter

import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.model.TipModel
import org.koitharu.kotatsu.list.ui.size.ItemSizeResolver

open class MangaListAdapter(
	listener: MangaListListener,
	sizeResolver: ItemSizeResolver,
	titleTapToRead: Boolean = false,
	/** Required for a tip to show its close button at all — see [tipAD]. */
	onTipClose: ((TipModel) -> Unit)? = null,
) : BaseListAdapter<ListModel>() {

	init {
		val titleClickListener = if (titleTapToRead) {
			OnListItemClickListener<MangaListModel> { item, view ->
				listener.onReadClick(item.toMangaWithOverride(), view)
			}
		} else {
			null
		}
		addDelegate(ListItemType.MANGA_LIST, mangaListItemAD(listener, titleClickListener))
		addDelegate(ListItemType.MANGA_LIST_DETAILED, mangaListDetailedItemAD(listener, titleClickListener))
		addDelegate(ListItemType.MANGA_GRID, mangaGridItemAD(sizeResolver, listener, titleClickListener))
		addDelegate(ListItemType.FOOTER_LOADING, loadingFooterAD())
		addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
		addDelegate(ListItemType.STATE_ERROR, errorStateListAD(listener))
		addDelegate(ListItemType.FOOTER_ERROR, errorFooterAD(listener))
		addDelegate(ListItemType.STATE_EMPTY, emptyStateListAD(listener))
		addDelegate(ListItemType.HEADER, listHeaderAD(listener))
		addDelegate(ListItemType.QUICK_FILTER, quickFilterAD(listener))
		addDelegate(ListItemType.TIP, tipAD(listener, onTipClose))
		addDelegate(ListItemType.INFO, infoAD())
		addDelegate(ListItemType.FOOTER_BUTTON, buttonFooterAD(listener))
	}
}
