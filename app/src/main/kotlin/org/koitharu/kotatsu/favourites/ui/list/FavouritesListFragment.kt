package org.koitharu.kotatsu.favourites.ui.list

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.view.ActionMode
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.util.ext.withArgs
import org.koitharu.kotatsu.list.ui.MangaListFragment
import org.koitharu.kotatsu.list.ui.adapter.MangaListAdapter
import org.koitharu.kotatsu.list.ui.config.ListConfigSection
import org.koitharu.kotatsu.list.ui.size.DynamicItemSizeResolver

@AndroidEntryPoint
class FavouritesListFragment : MangaListFragment() {

	override val viewModel by viewModels<FavouritesListViewModel>()

	override val isSwipeRefreshEnabled = false

	val categoryId
		get() = viewModel.categoryId

	override fun onCreateAdapter() = MangaListAdapter(
		listener = this,
		sizeResolver = DynamicItemSizeResolver(resources, viewLifecycleOwner, settings, adjustWidth = false),
		titleTapToRead = settings.isTitleTapToReadEnabled,
		onTipClose = { viewModel.dismissScalingTip() },
	)

	override fun onScrolledToEnd() = viewModel.requestMoreItems()

	override fun onEmptyActionClick() = viewModel.clearFilter()

	override fun onFilterClick(view: View?) {
		router.showListSortSheet(ListConfigSection.Favorites(categoryId))
	}

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu
	): Boolean {
		menuInflater.inflate(R.menu.mode_favourites, menu)
		return super.onCreateActionMode(controller, menuInflater, menu)
	}

	override fun onPrepareActionMode(controller: ListSelectionController, mode: ActionMode?, menu: Menu): Boolean {
		val pinned = viewModel.pinnedIds.value
		val ids = selectedItemsIds
		menu.findItem(R.id.action_pin)?.isVisible = ids.isNotEmpty() && ids.none { it in pinned }
		menu.findItem(R.id.action_unpin)?.isVisible = ids.isNotEmpty() && ids.all { it in pinned }
		return super.onPrepareActionMode(controller, mode, menu)
	}

	override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.action_pin -> {
				viewModel.setPinned(selectedItemsIds, true)
				mode?.finish()
				true
			}

			R.id.action_unpin -> {
				viewModel.setPinned(selectedItemsIds, false)
				mode?.finish()
				true
			}

			R.id.action_remove -> {
				viewModel.removeFromFavourites(selectedItemsIds)
				mode?.finish()
				true
			}

			R.id.action_mark_current -> {
				val itemsSnapshot = selectedItems
				MaterialAlertDialogBuilder(context ?: return false)
					.setTitle(item.title)
					.setMessage(R.string.mark_as_completed_prompt)
					.setNegativeButton(android.R.string.cancel, null)
					.setPositiveButton(android.R.string.ok) { _, _ ->
						viewModel.markAsRead(itemsSnapshot)
						mode?.finish()
					}.show()
				true
			}

			else -> super.onActionItemClicked(controller, mode, item)
		}
	}

	companion object {

		const val NO_ID = 0L

		fun newInstance(categoryId: Long) = FavouritesListFragment().withArgs(1) {
			putLong(AppRouter.KEY_ID, categoryId)
		}
	}
}
