package org.koitharu.kotatsu.list.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import androidx.core.view.forEach
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.menu.setMenuOption
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment
import org.koitharu.kotatsu.history.ui.HistoryListFragment
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.ui.config.ListConfigSection
import org.koitharu.kotatsu.suggestions.ui.SuggestionsFragment
import org.koitharu.kotatsu.tracker.ui.updates.UpdatesFragment

class MangaListMenuProvider(
	private val fragment: MangaListFragment,
) : MenuProvider {

	private val section: ListConfigSection
		get() = when (fragment) {
			is HistoryListFragment -> ListConfigSection.History
			is SuggestionsFragment -> ListConfigSection.Suggestions
			is FavouritesListFragment -> ListConfigSection.Favorites(fragment.categoryId)
			is UpdatesFragment -> ListConfigSection.Updated
			else -> ListConfigSection.General
		}

	// Only Favourites and History are sortable; the other lists have a fixed order.
	private val sortTypes: List<ListSortOrder.Type>?
		get() = when (fragment) {
			is FavouritesListFragment -> ListSortOrder.FAVORITES
			is HistoryListFragment -> ListSortOrder.HISTORY
			else -> null
		}

	private var sortMenu: Menu? = null
	// what the open menu shows; a category's sort reaches the view model only after its database write
	private var shownSortOrder: ListSortOrder? = null

	// null while a category's own sort is still loading
	private val sortOrder: ListSortOrder?
		get() = when (fragment) {
			is FavouritesListFragment -> fragment.viewModel.sortOrder.value
			else -> fragment.settings.historySortOrder
		}

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_list, menu)
	}

	override fun onPrepareMenu(menu: Menu) {
		val sortItem = menu.findItem(R.id.action_sort) ?: return
		val types = sortTypes
		val current = types?.let { sortOrder }
		sortItem.isVisible = current != null
		val sortMenu = sortItem.subMenu ?: return
		this.sortMenu = sortMenu
		sortMenu.clear()
		types?.forEach { type ->
			sortMenu.add(R.id.action_sort, Menu.FIRST + type.ordinal, Menu.NONE, type.titleResId)
		}
		showSortOrder(sortMenu, current)
	}

	/** The active option shows its direction after its title; picking it again flips it, like the sort sheet. */
	private fun showSortOrder(sortMenu: Menu, order: ListSortOrder?) {
		shownSortOrder = order
		sortMenu.forEach { item ->
			val isActive = order != null && item.itemId == Menu.FIRST + order.type.ordinal
			item.setMenuOption(
				when {
					!isActive -> 0
					order.isAscending -> R.drawable.ic_arrow_up
					else -> R.drawable.ic_arrow_down
				},
			)
		}
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when {
		menuItem.itemId == R.id.action_list_mode -> {
			fragment.router.showListConfigSheet(section)
			true
		}

		menuItem.groupId == R.id.action_sort -> {
			shownSortOrder?.pick(ListSortOrder.Type.entries[menuItem.itemId - Menu.FIRST])?.let { value ->
				if (fragment is FavouritesListFragment) {
					fragment.viewModel.setSortOrder(value)
				} else {
					fragment.settings.historySortOrder = value
				}
				// The menu stays open: show the new order right away (a category's own sort is saved in the background)
				sortMenu?.let { showSortOrder(it, value) }
			}
			true
		}

		else -> false
	}
}
