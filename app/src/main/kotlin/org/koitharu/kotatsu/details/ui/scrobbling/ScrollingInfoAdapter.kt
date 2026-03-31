package org.haziffe.dropsauce.details.ui.scrobbling

import org.haziffe.dropsauce.core.nav.AppRouter
import org.haziffe.dropsauce.core.ui.BaseListAdapter
import org.haziffe.dropsauce.list.ui.model.ListModel

class ScrollingInfoAdapter(
	router: AppRouter,
) : BaseListAdapter<ListModel>() {

	init {
		delegatesManager.addDelegate(scrobblingInfoAD(router))
	}
}
