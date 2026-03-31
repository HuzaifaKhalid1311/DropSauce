package org.haziffe.dropsauce.favourites.ui.categories.adapter

import org.haziffe.dropsauce.core.ui.ReorderableListAdapter
import org.haziffe.dropsauce.favourites.ui.categories.FavouriteCategoriesListListener
import org.haziffe.dropsauce.list.ui.adapter.ListItemType
import org.haziffe.dropsauce.list.ui.adapter.ListStateHolderListener
import org.haziffe.dropsauce.list.ui.adapter.emptyStateListAD
import org.haziffe.dropsauce.list.ui.adapter.loadingStateAD
import org.haziffe.dropsauce.list.ui.model.ListModel

class CategoriesAdapter(
	onItemClickListener: FavouriteCategoriesListListener,
	listListener: ListStateHolderListener,
) : ReorderableListAdapter<ListModel>() {

	init {
		addDelegate(ListItemType.CATEGORY_LARGE, categoryAD(onItemClickListener))
		addDelegate(ListItemType.NAV_ITEM, allCategoriesAD(onItemClickListener))
		addDelegate(ListItemType.STATE_EMPTY, emptyStateListAD(listListener))
		addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
	}
}
