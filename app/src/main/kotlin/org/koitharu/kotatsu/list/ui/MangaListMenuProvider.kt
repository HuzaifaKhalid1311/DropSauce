package org.koitharu.kotatsu.list.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment
import org.koitharu.kotatsu.history.ui.HistoryListFragment
import org.koitharu.kotatsu.list.ui.config.ListConfigSection
import org.koitharu.kotatsu.suggestions.ui.SuggestionsFragment
import org.koitharu.kotatsu.tracker.ui.updates.UpdatesFragment

class MangaListMenuProvider(
	private val fragment: Fragment,
) : MenuProvider {

	private val section: ListConfigSection
		get() = when (fragment) {
			is HistoryListFragment -> ListConfigSection.History
			is SuggestionsFragment -> ListConfigSection.Suggestions
			is FavouritesListFragment -> ListConfigSection.Favorites(fragment.categoryId)
			is UpdatesFragment -> ListConfigSection.Updated
			else -> ListConfigSection.General
		}

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_list, menu)
	}

	override fun onPrepareMenu(menu: Menu) {
		// Only Favourites and History are sortable; the other lists have a fixed order.
		menu.findItem(R.id.action_sort)?.isVisible = fragment is HistoryListFragment ||
			fragment is FavouritesListFragment
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
		R.id.action_list_mode -> {
			fragment.router.showListConfigSheet(section)
			true
		}

		R.id.action_sort -> {
			fragment.router.showListSortSheet(section)
			true
		}

		else -> false
	}
}
