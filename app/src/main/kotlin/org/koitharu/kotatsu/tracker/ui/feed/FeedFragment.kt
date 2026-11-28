package org.koitharu.kotatsu.tracker.ui.feed

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ActionMode
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.compose.MovingContainerComposeHost
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.ui.list.decor.AbstractSelectionItemDecoration
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.findAppCompatDelegate
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.core.util.ext.toSet
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem
import javax.inject.Inject

class FeedHostBinding(
	val host: MovingContainerComposeHost,
) : ViewBinding {
	override fun getRoot(): View = host
}

@AndroidEntryPoint
class FeedFragment :
	BaseFragment<FeedHostBinding>(),
	FeedActions,
	ListSelectionController.Callback {

	@Inject
	lateinit var coil: ImageLoader

	private val viewModel by viewModels<FeedViewModel>()
	private var selectionController: ListSelectionController? = null
	private var lazyListState: LazyListState? = null
	private var composeScope: CoroutineScope? = null

	private val selectedIds = mutableStateOf(emptySet<Long>())
	private val leftInsetPx = mutableIntStateOf(0)
	private val rightInsetPx = mutableIntStateOf(0)
	private val bottomInsetPx = mutableIntStateOf(0)
	private var pendingSelectAll: Boolean = false

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	): FeedHostBinding = FeedHostBinding(MovingContainerComposeHost(inflater.context))

	override fun onViewBindingCreated(binding: FeedHostBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		selectionController = ListSelectionController(
			appCompatDelegate = checkNotNull(findAppCompatDelegate()),
			decoration = object : AbstractSelectionItemDecoration() {
				override fun getItemId(parent: RecyclerView, child: View) = RecyclerView.NO_ID
			},
			registryOwner = this,
			callback = this,
		)

		binding.host.composeView.setContent {
			val items by viewModel.content.collectAsState()
			val workState by viewModel.workState.collectAsState()
			val lastCheckTimeMillis by viewModel.lastCheckTimeMillis.collectAsState()
			val undoEpoch by viewModel.undoEpoch.collectAsState()
			val isSwipeEnabled by viewModel.isSwipeGesturesEnabled.collectAsState()
			val state = rememberLazyListState().also { lazyListState = it }
			// Must use Compose's rememberCoroutineScope (carries MonotonicFrameClock) for animateScrollToItem.
			val scope = rememberCoroutineScope().also { composeScope = it }
			DisposableEffect(scope) {
				onDispose {
					if (composeScope === scope) composeScope = null
				}
			}

			val density = LocalDensity.current
			val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
			val leftDp = with(density) { leftInsetPx.intValue.toDp() }
			val rightDp = with(density) { rightInsetPx.intValue.toDp() }
			val bottomDp = with(density) { bottomInsetPx.intValue.toDp() }

			DropSauceTheme {
				FeedScreen(
					host = binding.host,
					listState = state,
					items = items,
					selectedIds = selectedIds.value,
					workState = workState,
					lastCheckTimeMillis = lastCheckTimeMillis,
					undoEpoch = undoEpoch,
					isSwipeEnabled = isSwipeEnabled,
					canLoadMore = viewModel.canLoadMore,
					startInset = if (isRtl) rightDp else leftDp,
					endInset = if (isRtl) leftDp else rightDp,
					bottomInset = bottomDp,
					imageLoader = coil,
					actions = this,
				)
			}
		}

		addMenuProvider(FeedMenuProvider(binding.host.composeView, viewModel, router))

		var seenIds = emptySet<Long>()
		viewModel.content.observe(viewLifecycleOwner) {
			val validIds = viewModel.allLoadedFeedIds().toSet()
			val controller = selectionController
			if (controller != null) {
				controller.retainAll(validIds + controller.snapshot().filterNot { it in seenIds })
				if (pendingSelectAll && selectedIds.value.isNotEmpty()) {
					controller.addAll(validIds)
					if (!viewModel.canLoadMore) {
						pendingSelectAll = false
					}
				}
			}
			seenIds = seenIds + validIds
		}
		viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(binding.host.composeView, this))
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.host.composeView))
		viewModel.isRunning.observe(viewLifecycleOwner) { activity?.invalidateMenu() }
		viewModel.unreadCount.observe(viewLifecycleOwner) { activity?.invalidateMenu() }
		viewModel.totalCount.observe(viewLifecycleOwner) { activity?.invalidateMenu() }
	}

	/** Back to the top of Feed (and re-expands a hidden top bar), for tab switches and reselecting its nav item. */
	fun scrollToTop(smooth: Boolean) {
		(activity as? AppBarOwner)?.appBar?.setExpanded(true, smooth)
		val state = lazyListState ?: return
		val scope = composeScope ?: return
		scope.launch {
			if (smooth) {
				if (state.firstVisibleItemIndex > 20) {
					state.scrollToItem(20)
				}
				state.animateScrollToItem(0)
			} else {
				state.scrollToItem(0)
			}
		}
	}

	override fun onPause() {
		super.onPause()
		viewModel.collapseAll()
	}

	override fun onDestroyView() {
		selectionController = null
		lazyListState = null
		composeScope = null
		super.onDestroyView()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.systemBarsInsets
		leftInsetPx.intValue = bars.left
		rightInsetPx.intValue = bars.right
		bottomInsetPx.intValue = bars.bottom
		return insets.consumeAllSystemBarsInsets()
	}

	// region FeedActions

	override fun onRefresh() {
		viewModel.update()
	}

	override fun onFilterOptionClick(option: ListFilterOption) {
		viewModel.toggleFilterOption(option)
	}

	override fun onClearFiltersClick() {
		viewModel.clearFilters()
	}

	override fun onTipDismiss() {
		viewModel.dismissGesturesTip()
	}

	override fun onItemClick(item: FeedItem) {
		val controller = selectionController ?: return
		if (controller.onItemClick(null, item.id)) {
			val isChecked = item.id in controller.peekCheckedIds()
			view?.hapticFeedback(if (isChecked) HapticEffect.LIGHT_CLICK else HapticEffect.DESELECT)
		} else {
			router.openDetails(item.toMangaWithOverride())
		}
	}

	override fun onItemLongClick(item: FeedItem) {
		if (selectionController?.onItemLongClick(null, item.id) == true) {
			view?.hapticFeedback(HapticEffect.LONG_PRESS)
		}
	}

	override fun onToggleExpand(item: FeedItem) {
		viewModel.toggleExpanded(item)
	}

	override fun onReadClick(item: FeedItem) {
		router.openReader(item.toMangaWithOverride())
	}

	override fun onSwipeMarkRead(item: FeedItem) {
		viewModel.markAsRead(item)
	}

	override fun onSwipeRemove(item: FeedItem) {
		viewModel.remove(item)
	}

	override fun onRequestMoreItems() {
		viewModel.requestMoreItems()
	}

	// endregion

	// region Selection

	override fun onSelectionChanged(controller: ListSelectionController, count: Int) {
		selectedIds.value = if (count == 0) emptySet() else controller.peekCheckedIds().toSet()
		if (count == 0) {
			pendingSelectAll = false
		}
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
			controller.addAll(viewModel.allLoadedFeedIds())
			if (viewModel.canLoadMore) {
				pendingSelectAll = true
				viewModel.requestAllItems()
			}
			true
		}

		else -> false
	}

	// endregion
}
