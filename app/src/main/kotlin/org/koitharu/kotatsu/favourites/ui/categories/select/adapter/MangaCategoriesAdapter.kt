package org.haziffe.dropsauce.favourites.ui.categories.select.adapter

import org.haziffe.dropsauce.core.ui.BaseListAdapter
import org.haziffe.dropsauce.core.ui.list.OnListItemClickListener
import org.haziffe.dropsauce.favourites.ui.categories.select.model.MangaCategoryItem
import org.haziffe.dropsauce.list.ui.adapter.ListItemType
import org.haziffe.dropsauce.list.ui.adapter.emptyStateListAD
import org.haziffe.dropsauce.list.ui.adapter.loadingStateAD
import org.haziffe.dropsauce.list.ui.model.ListModel

class MangaCategoriesAdapter(
	clickListener: OnListItemClickListener<MangaCategoryItem>,
) : BaseListAdapter<ListModel>() {

	init {
		addDelegate(ListItemType.NAV_ITEM, mangaCategoryAD(clickListener))
		addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
		addDelegate(ListItemType.STATE_EMPTY, emptyStateListAD(null))
	}
}
