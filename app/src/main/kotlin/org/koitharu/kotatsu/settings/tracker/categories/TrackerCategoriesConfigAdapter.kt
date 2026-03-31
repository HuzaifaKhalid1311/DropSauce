package org.haziffe.dropsauce.settings.tracker.categories

import org.haziffe.dropsauce.core.model.FavouriteCategory
import org.haziffe.dropsauce.core.ui.BaseListAdapter
import org.haziffe.dropsauce.core.ui.list.OnListItemClickListener

class TrackerCategoriesConfigAdapter(
	listener: OnListItemClickListener<FavouriteCategory>,
) : BaseListAdapter<FavouriteCategory>() {

	init {
		delegatesManager.addDelegate(trackerCategoryAD(listener))
	}
}
