package org.haziffe.dropsauce.favourites.ui

import android.os.Bundle
import org.haziffe.dropsauce.core.nav.AppRouter
import org.haziffe.dropsauce.core.ui.FragmentContainerActivity
import org.haziffe.dropsauce.favourites.ui.list.FavouritesListFragment

class FavouritesActivity : FragmentContainerActivity(FavouritesListFragment::class.java) {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val categoryTitle = intent.getStringExtra(AppRouter.KEY_TITLE)
		if (categoryTitle != null) {
			title = categoryTitle
		}
	}
}
