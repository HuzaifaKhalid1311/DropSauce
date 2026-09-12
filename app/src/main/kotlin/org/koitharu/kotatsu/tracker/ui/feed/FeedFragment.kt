package org.koitharu.kotatsu.tracker.ui.feed

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ActionMode
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.list.PaginationScrollListener
import org.koitharu.kotatsu.core.ui.list.RecyclerScrollKeeper
import org.koitharu.kotatsu.core.ui.util.RecyclerViewOwner
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.ui.widgets.TipView
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.consumeAll
import org.koitharu.kotatsu.core.util.ext.findAppCompatDelegate
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.roundTopCorners
import org.koitharu.kotatsu.databinding.FragmentListBinding
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.ui.adapter.MangaListListener
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.tracker.ui.feed.adapter.FeedAdapter
import org.koitharu.kotatsu.tracker.ui.feed.adapter.FeedSwipeCallback
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem
import javax.inject.Inject

@AndroidEntryPoint
class FeedFragment :
	BaseFragment<FragmentListBinding>(),
	PaginationScrollListener.Callback,
	RecyclerViewOwner,
	MangaListListener,
	SwipeRefreshLayout.OnRefreshListener,
	ListSelectionController.Callback {

	@Inject
	lateinit var coil: ImageLoader

	private val viewModel by viewModels<FeedViewModel>()
	private var selectionController: ListSelectionController? = null
	private var itemTouchHelper: ItemTouchHelper? = null
	private var isSwipeEnabled = true

	override val recyclerView: RecyclerView?
		get() = viewBinding?.recyclerView

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentListBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		selectionController = ListSelectionController(
			appCompatDelegate = checkNotNull(findAppCompatDelegate()),
			decoration = FeedSelectionDecoration(binding.root.context),
			registryOwner = this,
			callback = this,
		)
		val feedAdapter = FeedAdapter(
			listener = this,
			feedClickListener = object : OnListItemClickListener<FeedItem> {
				override fun onItemClick(item: FeedItem, view: View) {
					if (selectionController?.onItemClick(item.id) != true) {
						router.openDetails(item.toMangaWithOverride())
					}
				}

				override fun onItemLongClick(item: FeedItem, view: View): Boolean {
					return selectionController?.onItemLongClick(view, item.id) == true
				}
			},
			onTipClose = { viewModel.dismissGesturesTip() },
			onExpandClick = { item ->
				val controller = selectionController
				// in selection mode a tap anywhere on the row toggles selection instead of expanding
				if (controller != null && controller.count > 0) {
					controller.onItemClick(item.id)
				} else {
					viewModel.toggleExpanded(item)
				}
			},
		)
		val touchHelper = ItemTouchHelper(
			FeedSwipeCallback(binding.recyclerView.context) { item, isRead ->
				if (isRead) {
					// the row stays in place (the swipe never commits); the dot clears via the content flow
					viewModel.markAsRead(item)
				} else {
					feedAdapter.setItems(feedAdapter.items.filterNot { it is FeedItem && it.id == item.id })
					viewModel.remove(item)
				}
			},
		)
		with(binding.recyclerView) {
			// the same spacing the item decoration insets feed rows by
			val spacing = resources.getDimensionPixelSize(R.dimen.list_spacing_normal)
			setPadding(0, spacing, 0, spacing)
			// cut rows under the toolbar with a feed tile's own radius, at a tile's own edges
			roundTopCorners(
				radius = resources.getDimension(R.dimen.corner_large),
				extraInset = spacing,
			)
			layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
			adapter = feedAdapter
			setHasFixedSize(true)
			addOnScrollListener(PaginationScrollListener(4, this@FeedFragment))
			addItemDecoration(TypedListSpacingDecoration(context, true))
			RecyclerScrollKeeper(this).attach()
			selectionController?.attachToRecyclerView(this)
		}
		itemTouchHelper = touchHelper
		viewModel.isSwipeGesturesEnabled.observe(viewLifecycleOwner) { isEnabled ->
			isSwipeEnabled = isEnabled
			updateSwipeAttachment()
		}
		binding.swipeRefreshLayout.setOnRefreshListener(this)
		addMenuProvider(FeedMenuProvider(binding.recyclerView, viewModel, router))

		viewModel.content.observe(viewLifecycleOwner, feedAdapter)
		viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(binding.recyclerView, this))
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.recyclerView))
		viewModel.isRunning.observe(viewLifecycleOwner, this::onIsTrackerRunningChanged)
	}

	override fun onPause() {
		super.onPause()
		viewModel.collapseAll()
	}

	override fun onDestroyView() {
		super.onDestroyView()
		selectionController = null
		itemTouchHelper = null
	}

	override fun onSelectionChanged(controller: ListSelectionController, count: Int) {
		viewBinding?.recyclerView?.invalidateItemDecorations()
		// swipe rows and multi-select fight over the same touch gesture; suspend swiping while selecting
		updateSwipeAttachment()
	}

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu,
	): Boolean {
		menuInflater.inflate(R.menu.mode_feed, menu)
		return true
	}

	override fun onActionItemClicked(
		controller: ListSelectionController,
		mode: ActionMode?,
		item: MenuItem,
	): Boolean = when (item.itemId) {
		R.id.action_mark_read -> {
			viewModel.markAsRead(controller.snapshot())
			mode?.finish()
			true
		}

		R.id.action_remove -> {
			viewModel.remove(controller.snapshot())
			mode?.finish()
			true
		}

		R.id.action_select_all -> {
			val ids = viewModel.content.value.mapNotNull { (it as? FeedItem)?.id }
			controller.addAll(ids)
			true
		}

		else -> false
	}

	private fun updateSwipeAttachment() {
		val attach = isSwipeEnabled && (selectionController?.count ?: 0) == 0
		itemTouchHelper?.attachToRecyclerView(if (attach) viewBinding?.recyclerView else null)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		val paddingVertical = resources.getDimensionPixelSize(R.dimen.list_spacing_normal)
		viewBinding?.recyclerView?.setPadding(
			left = barsInsets.left,
			top = paddingVertical,
			right = barsInsets.right,
			bottom = barsInsets.bottom + paddingVertical,
		)
		return insets.consumeAll(typeMask)
	}

	override fun onRefresh() {
		viewModel.update()
	}

	override fun onFilterOptionClick(option: ListFilterOption) = viewModel.toggleFilterOption(option)

	override fun onRetryClick(error: Throwable) = Unit

	override fun onFilterClick(view: View?) = Unit

	override fun onEmptyActionClick() = Unit

	override fun onPrimaryButtonClick(tipView: TipView) = Unit

	override fun onSecondaryButtonClick(tipView: TipView) = Unit

	override fun onListHeaderClick(item: ListHeader, view: View) {
		router.openMangaUpdates()
	}

	private fun onIsTrackerRunningChanged(isRunning: Boolean) {
		requireViewBinding().swipeRefreshLayout.isRefreshing = isRunning
		requireActivity().invalidateMenu()
	}

	override fun onScrolledToEnd() {
		viewModel.requestMoreItems()
	}

	override fun onItemClick(item: MangaListModel, view: View) {
		router.openDetails(item.toMangaWithOverride())
	}

	override fun onReadClick(manga: Manga, view: View) {
		router.openReader(manga, view)
	}

	override fun onTagClick(manga: Manga, tag: MangaTag, view: View) = Unit
}
