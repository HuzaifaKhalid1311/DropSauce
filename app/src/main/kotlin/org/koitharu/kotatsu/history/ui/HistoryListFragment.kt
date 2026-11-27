package org.koitharu.kotatsu.history.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.view.ActionMode
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.dialog.showConfirmDialog
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.ui.list.RecyclerScrollKeeper
import org.koitharu.kotatsu.core.ui.util.MenuInvalidator
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.FragmentListBinding
import org.koitharu.kotatsu.list.ui.MangaListFragment
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.size.DynamicItemSizeResolver

@AndroidEntryPoint
class HistoryListFragment : MangaListFragment(), HistorySectionListener {

	override val viewModel by viewModels<HistoryListViewModel>()
	private val selectionState = HistorySelectionState()

	/** A section picked before all of it was loaded; the rest gets picked as it arrives. */
	private var pendingSection: String? = null

	/** Its entries picked so far, so one the user unticks meanwhile isn't picked again. */
	private var pendingSectionIds: Set<Long> = emptySet()
	override val isSwipeRefreshEnabled = false
	override val checkpointScope get() = "history"

	override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val animator = HistoryItemAnimator()
		binding.recyclerView.itemAnimator = animator
		listAdapter?.addListListener { previousList, currentList ->
			animator.onListDiffed(previousList, currentList)
			onHistoryChanged(currentList)
		}
		RecyclerScrollKeeper(binding.recyclerView).attach()
		addMenuProvider(HistoryListMenuProvider(binding.root.context, router, viewModel))
		viewModel.isStatsEnabled.observe(viewLifecycleOwner, MenuInvalidator(requireActivity()))
	}

	private fun onHistoryChanged(list: List<ListModel>) {
		val key = pendingSection ?: return
		val section = list.firstNotNullOfOrNull { (it as? HistorySection)?.takeIf { s -> s.key == key } }
		if (section == null || selectionState.ids.isEmpty()) {
			pendingSection = null
			return
		}
		selectionController?.addAll(section.itemIds - pendingSectionIds)
		pendingSectionIds = pendingSectionIds + section.itemIds
		if (section.isFullyLoaded) {
			pendingSection = null
		} else {
			viewModel.requestRestOf(section) // its size may be unknown (e.g. On device), so page by page
		}
	}

	private fun selectSection(section: HistorySection) {
		selectionController?.addAll(section.itemIds)
		if (!section.isFullyLoaded) {
			pendingSection = section.key
			pendingSectionIds = section.itemIds.toSet()
			viewModel.requestRestOf(section)
		}
	}

	/** The jump-back target may sit in a folded section: open it up. */
	override fun revealCheckpointRow(mangaId: Long): Boolean {
		val section = listAdapter?.items?.firstNotNullOfOrNull { item ->
			(item as? HistorySection)?.takeIf { it.isCollapsed && mangaId in it.itemIds }
		} ?: return false
		viewModel.setSectionCollapsed(section.key, false)
		return true
	}

	override fun onScrolledToEnd() = viewModel.requestMoreItems()

	/** Folds the section, or while selecting, picks (or drops) every entry in it. */
	override fun onSectionClick(section: HistorySection) {
		if (selectionState.ids.isEmpty()) {
			viewModel.setSectionCollapsed(section.key, !section.isCollapsed)
			viewBinding?.recyclerView?.hapticFeedback(HapticEffect.LIGHT_CLICK)
		} else {
			toggleSectionSelection(section)
		}
	}

	/** Holding a section starts selecting with all of it already picked. */
	override fun onSectionLongClick(section: HistorySection) {
		if (selectionState.ids.isEmpty()) {
			viewModel.setSectionCollapsed(section.key, false)
			selectSection(section)
			viewBinding?.recyclerView?.hapticFeedback(HapticEffect.LONG_PRESS)
		} else {
			toggleSectionSelection(section)
		}
	}

	private fun toggleSectionSelection(section: HistorySection) {
		val controller = selectionController ?: return
		// A folded section opens up, so whatever gets picked is also on screen for the actions.
		viewModel.setSectionCollapsed(section.key, false)
		val checked = controller.snapshot()
		val isAllPicked = section.isFullyLoaded || pendingSection == section.key
		if (isAllPicked && section.itemIds.isNotEmpty() && checked.containsAll(section.itemIds)) {
			if (pendingSection == section.key) pendingSection = null
			controller.retainAll(checked - section.itemIds.toSet())
			viewBinding?.recyclerView?.hapticFeedback(HapticEffect.DESELECT)
		} else {
			selectSection(section)
			viewBinding?.recyclerView?.hapticFeedback(HapticEffect.CLICK)
		}
	}

	override fun collectSelectedItems() = selectionController?.peekCheckedIds()
		?.let(viewModel::getLoadedManga)
		.orEmpty()
		.ifEmpty { super.collectSelectedItems() }

	override fun onSelectionChanged(controller: ListSelectionController, count: Int) {
		super.onSelectionChanged(controller, count)
		selectionState.ids = if (count == 0) emptySet() else controller.snapshot()
		if (count == 0) pendingSection = null
	}

	override fun onEmptyActionClick() = viewModel.clearFilter()

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu
	): Boolean {
		menuInflater.inflate(R.menu.mode_history, menu)
		return super.onCreateActionMode(controller, menuInflater, menu)
	}

	override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.action_remove -> {
				viewModel.removeFromHistory(selectedItemsIds)
				mode?.finish()
				true
			}

			R.id.action_mark_read, R.id.action_mark_unread -> {
				val isRead = item.itemId == R.id.action_mark_read
				val itemsSnapshot = selectedItems
				val ctx = context ?: return false
				showConfirmDialog(
					context = ctx,
					icon = if (isRead) R.drawable.ic_eye_check else R.drawable.ic_mark_unread,
					title = ctx.getString(if (isRead) R.string.mark_as_read else R.string.mark_as_unread),
					message = ctx.getString(if (isRead) R.string.mark_as_completed_prompt else R.string.mark_as_unread_prompt),
				) {
					viewModel.markAsRead(itemsSnapshot, isRead)
					mode?.finish()
				}
				true
			}

			else -> super.onActionItemClicked(controller, mode, item)
		}
	}

	override fun onCreateAdapter() = HistoryListAdapter(
		listener = this,
		sectionListener = this,
		selection = selectionState,
		imageLoader = coil,
		sizeResolver = DynamicItemSizeResolver(resources, viewLifecycleOwner, settings, adjustWidth = false),
		titleTapToRead = settings.isTitleTapToReadEnabled,
		onTipClose = { viewModel.dismissScalingTip() },
	)
}
