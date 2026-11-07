package org.koitharu.kotatsu.list.ui

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
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
		sortMenu.clear()
		// The active option shows its direction; picking it again flips it, like the sort sheet
		types?.forEach { type ->
			sortMenu.add(R.id.action_sort, Menu.FIRST + type.ordinal, Menu.NONE, type.titleResId).apply {
				if (current?.type == type) {
					setIcon(if (current.isAscending) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down)
				}
			}
		}
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when {
		menuItem.itemId == R.id.action_list_mode -> {
			fragment.router.showListConfigSheet(section)
			true
		}

		menuItem.groupId == R.id.action_sort -> {
			val value = sortOrder?.pick(ListSortOrder.Type.entries[menuItem.itemId - Menu.FIRST])
			when {
				value == null -> Unit
				fragment is FavouritesListFragment -> fragment.viewModel.setSortOrder(value)
				else -> fragment.settings.historySortOrder = value
			}
			true
		}

		else -> false
	}
}
