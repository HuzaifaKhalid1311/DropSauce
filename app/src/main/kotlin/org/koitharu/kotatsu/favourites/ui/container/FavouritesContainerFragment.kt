package org.koitharu.kotatsu.favourites.ui.container

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.TextView
import androidx.appcompat.view.ActionMode
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.util.ActionModeListener
import org.koitharu.kotatsu.core.ui.util.RecyclerViewOwner
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.centerContentOnDisplay
import org.koitharu.kotatsu.core.util.ext.findCurrentPagerFragment
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.recyclerView
import org.koitharu.kotatsu.core.util.ext.setTabsEnabled
import org.koitharu.kotatsu.core.util.ext.setTextAndVisible
import org.koitharu.kotatsu.databinding.FragmentFavouritesContainerBinding
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.databinding.ItemEmptyStateBinding

@AndroidEntryPoint
class FavouritesContainerFragment : BaseFragment<FragmentFavouritesContainerBinding>(),
	ActionModeListener,
	RecyclerViewOwner,
	ViewStub.OnInflateListener,
	View.OnClickListener {

	private val viewModel: FavouritesContainerViewModel by viewModels()

	override val recyclerView: RecyclerView?
		get() = (findCurrentFragment() as? RecyclerViewOwner)?.recyclerView

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentFavouritesContainerBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentFavouritesContainerBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val pagerAdapter = FavouritesContainerAdapter(this)
		binding.pager.adapter = pagerAdapter
		binding.pager.offscreenPageLimit = 1
		binding.pager.recyclerView?.isNestedScrollingEnabled = false
		TabLayoutMediator(
			binding.tabs,
			binding.pager,
			FavouritesTabConfigurationStrategy(pagerAdapter, viewModel, router),
		).attach()
		binding.tabs.addOnLayoutChangeListener(alignTabsToCovers)
		binding.pager.addOnLayoutChangeListener(alignTabsToCovers)
		binding.stubEmpty.setOnInflateListener(this)
		if (!isHidden) {
			attachTabsToAppBar()
		}
		actionModeDelegate.addListener(this)
		viewModel.categories.observe(viewLifecycleOwner, pagerAdapter)
		viewModel.isEmpty.observe(viewLifecycleOwner, ::onEmptyStateChanged)
		addMenuProvider(FavouritesContainerMenuProvider(router))
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.pager))
	}

	override fun onDestroyView() {
		detachTabsFromAppBar()
		actionModeDelegate.removeListener(this)
		super.onDestroyView()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

	override fun onHiddenChanged(hidden: Boolean) {
		super.onHiddenChanged(hidden)
		if (!hidden) {
			// This tab is kept alive across bottom-nav switches, so its category lists would retain
			// their previous scroll. Reset every instantiated category page (the visible one plus any
			// cached offscreen pages) to the top whenever Favourites is reopened, matching the other tabs.
			for (page in childFragmentManager.fragments) {
				val recyclerView = (page as? RecyclerViewOwner)?.recyclerView ?: continue
				when (val lm = recyclerView.layoutManager) {
					is LinearLayoutManager -> lm.scrollToPositionWithOffset(0, 0)
					else -> recyclerView.scrollToPosition(0)
				}
			}
		}
	}

	override fun onActionModeStarted(mode: ActionMode) {
		viewBinding?.run {
			pager.isUserInputEnabled = false
			tabs.setTabsEnabled(false)
		}
	}

	override fun onActionModeFinished(mode: ActionMode) {
		viewBinding?.run {
			pager.isUserInputEnabled = true
			tabs.setTabsEnabled(true)
		}
	}

	override fun onInflate(stub: ViewStub?, inflated: View) {
		val stubBinding = ItemEmptyStateBinding.bind(inflated)
		inflated.centerContentOnDisplay()
		stubBinding.icon.setImageAsync(R.drawable.ic_empty_favourites)
		stubBinding.textPrimary.setText(R.string.text_empty_holder_primary)
		stubBinding.textSecondary.setTextAndVisible(R.string.empty_favourite_categories)
		stubBinding.buttonRetry.setTextAndVisible(R.string.manage)
		stubBinding.buttonRetry.setOnClickListener(this)
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_retry -> router.openFavoriteCategories()
		}
	}

	private fun onEmptyStateChanged(isEmpty: Boolean) {
		viewBinding?.run {
			pager.isGone = isEmpty
			tabs.isGone = isEmpty
			stubEmpty.isVisible = isEmpty
		}
	}

	// Shifts the tab strip so the first tab's indicator starts where the leftmost grid cover's
	// rounded corner ends. Screen coordinates, so insets/nav rail/app-bar reparenting are all covered.
	private val alignTabsToCovers = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
		val binding = viewBinding ?: return@OnLayoutChangeListener
		val tabs = binding.tabs
		val list = recyclerView ?: return@OnLayoutChangeListener
		val firstTab = (tabs.getChildAt(0) as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: return@OnLayoutChangeListener
		// The content-width indicator spans the label, which the tab view centres.
		val label = firstTab.children.firstOrNull { it is TextView && it.isVisible } ?: return@OnLayoutChangeListener
		val res = tabs.resources
		// The pager, not the page: pages slide sideways mid-swipe, the pager doesn't.
		val coverStraightX = binding.pager.locationOnScreenX() + list.paddingLeft +
			res.getDimensionPixelOffset(R.dimen.grid_spacing_outer) +
			res.getDimensionPixelOffset(R.dimen.cover_corner_large)
		// ponytail: clamped at 0; a label narrower than the tab's min width can't reach further left.
		val padding = (coverStraightX - tabs.locationOnScreenX() - firstTab.left - label.left).coerceAtLeast(0)
		if (padding != tabs.paddingLeft) tabs.updatePadding(left = padding)
	}

	private fun View.locationOnScreenX() = IntArray(2).also(::getLocationOnScreen)[0]

	private fun findCurrentFragment(): Fragment? {
		return childFragmentManager.findCurrentPagerFragment(
			viewBinding?.pager ?: return null,
		)
	}

	// The category tabs live in the activity's AppBarLayout while this tab is visible, so they scroll
	// off-screen together with the search bar instead of being pinned above the (edge-to-edge) lists.
	// Called by MainActivity at bottom-nav commit time, not from onHiddenChanged: that callback is only
	// delivered after the tab-switch animation finishes, so the app bar would grow by the tab strip's
	// height a frame after the crossfade ended and visibly shove the list down.
	fun attachTabsToAppBar() {
		val tabs = viewBinding?.tabs ?: return
		val appBar = (activity as? AppBarOwner)?.appBar ?: return
		if (tabs.parent === appBar) {
			return
		}
		(tabs.parent as? ViewGroup)?.removeView(tabs)
		appBar.addView(
			tabs,
			AppBarLayout.LayoutParams(
				AppBarLayout.LayoutParams.MATCH_PARENT,
				AppBarLayout.LayoutParams.WRAP_CONTENT,
			).apply {
				scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
					AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS or
					AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
			},
		)
	}

	fun detachTabsFromAppBar() {
		val tabs = viewBinding?.tabs ?: return
		val parent = tabs.parent as? ViewGroup ?: return
		if (parent !== viewBinding?.root) {
			parent.removeView(tabs)
		}
	}
}
