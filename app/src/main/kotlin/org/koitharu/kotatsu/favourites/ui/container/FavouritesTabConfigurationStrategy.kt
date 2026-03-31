package org.haziffe.dropsauce.favourites.ui.container

import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator.TabConfigurationStrategy
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.core.nav.AppRouter
import org.haziffe.dropsauce.core.ui.util.PopupMenuMediator

class FavouritesTabConfigurationStrategy(
	private val adapter: FavouritesContainerAdapter,
	private val viewModel: FavouritesContainerViewModel,
	private val router: AppRouter,
) : TabConfigurationStrategy {

	override fun onConfigureTab(tab: TabLayout.Tab, position: Int) {
		val item = adapter.getItem(position)
		tab.text = item.title ?: tab.view.context.getString(R.string.all_favourites)
		tab.tag = item
		PopupMenuMediator(
			FavouriteTabPopupMenuProvider(tab.view.context, router, viewModel, item.id)
		).attach(tab.view)
	}
}
