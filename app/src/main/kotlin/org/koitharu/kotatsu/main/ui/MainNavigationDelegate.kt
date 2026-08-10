package org.koitharu.kotatsu.main.ui

import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.annotation.IdRes
import androidx.core.view.isEmpty
import androidx.core.view.isVisible
import androidx.core.view.iterator
import androidx.core.view.size
import androidx.core.view.updateLayoutParams
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigationrail.NavigationRailView
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.ui.AllBookmarksFragment
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.NavItem
import org.koitharu.kotatsu.core.ui.util.RecyclerViewOwner
import org.koitharu.kotatsu.core.ui.widgets.FloatingBottomNavigationView
import org.koitharu.kotatsu.core.ui.widgets.SlidingBottomNavigationView
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.buildBundle
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
import org.koitharu.kotatsu.core.util.ext.setContentDescriptionAndTooltip
import org.koitharu.kotatsu.core.util.ext.smoothScrollToTop
import org.koitharu.kotatsu.databinding.NavigationRailFabBinding
import org.koitharu.kotatsu.explore.ui.ExploreFragment
import org.koitharu.kotatsu.favourites.ui.container.FavouritesContainerFragment
import org.koitharu.kotatsu.history.ui.HistoryListFragment
import org.koitharu.kotatsu.local.ui.LocalListFragment
import org.koitharu.kotatsu.suggestions.ui.SuggestionsFragment
import org.koitharu.kotatsu.tracker.ui.feed.FeedFragment
import org.koitharu.kotatsu.tracker.ui.updates.UpdatesFragment
import java.util.LinkedList
import com.google.android.material.R as materialR

class MainNavigationDelegate(
	private val navBar: NavigationBarView,
	private val fragmentManager: FragmentManager,
	private val settings: AppSettings,
) : OnBackPressedCallback(false),
	NavigationBarView.OnItemSelectedListener,
	NavigationBarView.OnItemReselectedListener, View.OnClickListener {

	private val listeners = LinkedList<OnFragmentChangedListener>()
	val navRailHeader = (navBar as? NavigationRailView)?.headerView?.let {
		NavigationRailFabBinding.bind(it)
	}

	var onExploreReselected: (() -> Unit)? = null

	// Tabs are kept alive (see [setPrimaryFragment]); the "primary" one is whichever tab fragment
	// is currently shown (not hidden). All others stay added but hidden + capped to STARTED.
	val primaryFragment: Fragment?
		get() = fragmentManager.fragments.lastOrNull {
			it.isAdded && !it.isHidden && getItemId(it) != 0
		}

	init {
		navBar.setOnItemSelectedListener(this)
		navBar.setOnItemReselectedListener(this)
		navRailHeader?.run {
			root.updateLayoutParams<FrameLayout.LayoutParams> {
				gravity = Gravity.TOP or Gravity.CENTER
			}
			val horizontalPadding = (navBar as NavigationRailView).itemActiveIndicatorMarginHorizontal
			root.setPadding(horizontalPadding, 0, horizontalPadding, 0)
			buttonExpand.setOnClickListener(this@MainNavigationDelegate)
			buttonExpand.setContentDescriptionAndTooltip(R.string.expand)
			railFab.isExtended = false
			railFab.isAnimationEnabled = false
		}
	}

	override fun onNavigationItemSelected(item: MenuItem): Boolean {
		return if (onNavigationItemSelected(item.itemId)) {
			item.isChecked = true
			navBar.hapticFeedback(HapticEffect.CONFIRM)
			true
		} else {
			false
		}
	}

	override fun onNavigationItemReselected(item: MenuItem) {
		if (item.itemId == R.id.nav_explore) {
			onExploreReselected?.invoke()
		}
		onNavigationItemReselected()
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_expand -> {
				if (navBar is NavigationRailView) {
					setNavbarIsExpanded(!navBar.isExpanded)
				}
			}
		}
	}

	override fun handleOnBackPressed() {
		navBar.selectedItemId = firstItem()?.itemId ?: return
	}

	fun onCreate(lifecycleOwner: LifecycleOwner, savedInstanceState: Bundle?) {
		if (navBar.menu.isEmpty()) {
			createMenu(settings.mainNavItems, navBar.menu)
		}
		(navBar as? FloatingBottomNavigationView)?.let { floating ->
			floating.setComposeItems(settings.mainNavItems)
			floating.setComposeLabeled(settings.isNavLabelsVisible)
		}
		observeSettings(lifecycleOwner)
		val fragment = primaryFragment
		if (fragment != null) {
			normalizeFragmentLifecycles(fragment)
			onFragmentChanged(fragment, fromUser = false)
			val itemId = getItemId(fragment)
			if (navBar.selectedItemId != itemId) {
				navBar.selectedItemId = itemId
			}
		} else {
			val itemId = if (savedInstanceState == null) {
				firstItem()?.itemId ?: navBar.selectedItemId
			} else {
				navBar.selectedItemId
			}
			onNavigationItemSelected(itemId)
		}
	}

	fun observeTitle() = callbackFlow {
		val listener = OnFragmentChangedListener { f, _ ->
			trySendBlocking(getItemId(f))
		}
		addOnFragmentChangedListener(listener)
		awaitClose { removeOnFragmentChangedListener(listener) }
	}.map {
		navBar.menu.findItem(it)?.title
	}

	fun setCounter(item: NavItem, counter: Int) {
		setCounter(item.id, counter)
	}

	fun syncSelectedItem() {
		val fragment = primaryFragment ?: return
		onFragmentChanged(fragment, fromUser = false)
		val itemId = getItemId(fragment)
		if (navBar.selectedItemId != itemId) {
			navBar.selectedItemId = itemId
		}
	}

	private fun setCounter(@IdRes id: Int, counter: Int) {
		if (counter == 0) {
			navBar.getBadge(id)?.isVisible = false
		} else {
			val badge = navBar.getOrCreateBadge(id)
			if (counter < 0) {
				badge.clearNumber()
			} else {
				badge.number = counter
			}
			badge.isVisible = true
		}
		(navBar as? FloatingBottomNavigationView)?.setComposeBadge(id, counter)
	}

	fun setItemVisibility(@IdRes itemId: Int, isVisible: Boolean) {
		val item = navBar.menu.findItem(itemId) ?: return
		item.isVisible = isVisible
		(navBar as? FloatingBottomNavigationView)?.setComposeItemVisibility(itemId, isVisible)
		if (item.isChecked && !isVisible) {
			navBar.selectedItemId = firstItem()?.itemId ?: return
		}
	}

	fun addOnFragmentChangedListener(listener: OnFragmentChangedListener) {
		listeners.add(listener)
	}

	fun removeOnFragmentChangedListener(listener: OnFragmentChangedListener) {
		listeners.remove(listener)
	}

	private fun onNavigationItemSelected(@IdRes itemId: Int): Boolean {
		val newFragment = when (itemId) {
			R.id.nav_history -> HistoryListFragment::class.java
			R.id.nav_favorites -> FavouritesContainerFragment::class.java
			R.id.nav_explore -> ExploreFragment::class.java
			R.id.nav_feed -> FeedFragment::class.java
			R.id.nav_local -> LocalListFragment::class.java
			R.id.nav_suggestions -> SuggestionsFragment::class.java
			R.id.nav_bookmarks -> AllBookmarksFragment::class.java
			R.id.nav_updated -> UpdatesFragment::class.java
			else -> return false
		}
		if (!setPrimaryFragment(newFragment)) {
			// probably already selected
			onNavigationItemReselected()
		}
		return true
	}

	private fun getItemId(fragment: Fragment) = when (fragment) {
		is HistoryListFragment -> R.id.nav_history
		is FavouritesContainerFragment -> R.id.nav_favorites
		is ExploreFragment -> R.id.nav_explore
		is FeedFragment -> R.id.nav_feed
		is LocalListFragment -> R.id.nav_local
		is SuggestionsFragment -> R.id.nav_suggestions
		is AllBookmarksFragment -> R.id.nav_bookmarks
		is UpdatesFragment -> R.id.nav_updated
		else -> 0
	}

	private fun setPrimaryFragment(fragmentClass: Class<out Fragment>): Boolean {
		if (fragmentManager.isStateSaved || fragmentClass.isInstance(primaryFragment)) {
			return false
		}
		// Each tab is added once and then kept alive for the rest of the session: switching tabs
		// hides the current fragment and shows the target instead of destroying/recreating it. This
		// preserves the fragment's ViewModel, loaded content and scroll position, so returning to a
		// tab is instant instead of triggering a full reload. Hidden tabs are capped at STARTED so
		// their RESUMED-bound menu providers stay inactive and don't leak into the visible toolbar.
		val tag = fragmentClass.name
		val current = primaryFragment
		val transaction = fragmentManager.beginTransaction()
			.setReorderingAllowed(true)
			.setCustomAnimations(
				R.anim.m3_fade_through_enter,
				R.anim.m3_fade_through_exit,
				R.anim.m3_fade_through_pop_enter,
				R.anim.m3_fade_through_pop_exit,
			)
		if (current != null) {
			transaction.hide(current)
			transaction.setMaxLifecycle(current, Lifecycle.State.STARTED)
		}
		val existing = fragmentManager.findFragmentByTag(tag)
		val shownFragment: Fragment
		if (existing != null) {
			shownFragment = existing
			transaction.show(existing)
		} else {
			shownFragment = instantiateFragment(fragmentClass).apply {
				arguments = buildBundle(1) {
					putBoolean(AppRouter.KEY_IS_BOTTOMTAB, true)
				}
			}
			transaction.add(R.id.container, shownFragment, tag)
		}
		transaction.setMaxLifecycle(shownFragment, Lifecycle.State.RESUMED)
		transaction.runOnCommit {
			val shown = primaryFragment ?: shownFragment
			// Tabs are kept alive, so the list would otherwise retain its previous scroll position.
			// Reset it to the top so every tab always opens at the top. Done here, before the first
			// frame is drawn (and while the fade-in is still near-transparent), so there's no visible jump.
			shown.resetContentToTop()
			onFragmentChanged(shown, fromUser = true)
		}
		transaction.commit()
		return true
	}

	private fun Fragment.resetContentToTop() {
		// Explore scrolls its whole content in one NestedScrollView rather than exposing a list.
		val recyclerView = (this as? RecyclerViewOwner)?.recyclerView ?: run {
			(view as? NestedScrollView)?.scrollTo(0, 0)
			return
		}
		when (val lm = recyclerView.layoutManager) {
			is LinearLayoutManager -> lm.scrollToPositionWithOffset(0, 0)
			else -> recyclerView.scrollToPosition(0)
		}
	}

	/**
	 * Re-applies the correct max lifecycle to retained tab fragments after the activity is recreated
	 * (configuration change or process death). [androidx.fragment.app.FragmentTransaction.setMaxLifecycle]
	 * state is not persisted, so without this every restored tab would come back RESUMED — keeping
	 * hidden tabs' menu providers and observers active behind the visible one.
	 */
	private fun normalizeFragmentLifecycles(active: Fragment) {
		if (fragmentManager.isStateSaved) {
			return
		}
		val transaction = fragmentManager.beginTransaction().setReorderingAllowed(true)
		var hasChanges = false
		for (f in fragmentManager.fragments) {
			if (getItemId(f) == 0) {
				continue
			}
			transaction.setMaxLifecycle(
				f,
				if (f === active) Lifecycle.State.RESUMED else Lifecycle.State.STARTED,
			)
			hasChanges = true
		}
		if (hasChanges) {
			transaction.commitNow()
		}
	}

	private fun onNavigationItemReselected() {
		val fragment = primaryFragment
		val recyclerView = (fragment as? RecyclerViewOwner)?.recyclerView ?: run {
			(fragment?.view as? NestedScrollView)?.smoothScrollTo(0, 0)
			return
		}
		recyclerView.smoothScrollToTop()
	}

	private fun onFragmentChanged(fragment: Fragment, fromUser: Boolean) {
		isEnabled = getItemId(fragment) != firstItem()?.itemId
		listeners.forEach { it.onFragmentChanged(fragment, fromUser) }
	}

	private fun createMenu(items: List<NavItem>, menu: Menu) {
		for (item in items) {
			menu.add(Menu.NONE, item.id, Menu.NONE, item.title)
				.setIcon(item.icon)
			if (menu.size >= navBar.maxItemCount) {
				break
			}
		}
	}

	private fun instantiateFragment(fragmentClass: Class<out Fragment>): Fragment {
		val classLoader = navBar.context.classLoader
		return fragmentManager.fragmentFactory.instantiate(classLoader, fragmentClass.name)
	}

	private fun observeSettings(lifecycleOwner: LifecycleOwner) {
		settings.observe(
			AppSettings.KEY_TRACKER_ENABLED,
			AppSettings.KEY_SUGGESTIONS,
			AppSettings.KEY_NAV_LABELS,
		)
			.onEach {
				setItemVisibility(R.id.nav_suggestions, settings.isSuggestionsEnabled)
				setItemVisibility(R.id.nav_feed, settings.isTrackerEnabled)
				setNavbarIsLabeled(settings.isNavLabelsVisible)
			}.launchIn(lifecycleOwner.lifecycleScope)
	}

	private fun firstItem(): MenuItem? {
		val menu = navBar.menu
		for (item in menu) {
			if (item.isVisible) return item
		}
		return null
	}

	private fun setNavbarIsLabeled(value: Boolean) {
		if (navBar is SlidingBottomNavigationView) {
			navBar.minimumHeight = navBar.resources.getDimensionPixelSize(
				if (value) {
					materialR.dimen.m3_bottom_nav_min_height
				} else {
					R.dimen.nav_bar_height_compact
				},
			)
		}
		(navBar as? FloatingBottomNavigationView)?.setComposeLabeled(value)
		navRailHeader?.buttonExpand?.isVisible = value
		if (!value) {
			setNavbarIsExpanded(false)
		}
		navBar.labelVisibilityMode = if (value) {
			NavigationBarView.LABEL_VISIBILITY_LABELED
		} else {
			NavigationBarView.LABEL_VISIBILITY_UNLABELED
		}
	}

	private fun setNavbarIsExpanded(value: Boolean) {
		if (navBar !is NavigationRailView) {
			return
		}
		if (value) {
			navBar.expand()
			navRailHeader?.run {
				root.updateLayoutParams<FrameLayout.LayoutParams> {
					gravity = Gravity.TOP or Gravity.START
				}
				railFab.extend()
				buttonExpand.setImageResource(R.drawable.ic_drawer_menu_open)
				buttonExpand.setContentDescriptionAndTooltip(R.string.collapse)
				val horizontalPadding = navBar.itemActiveIndicatorExpandedMarginHorizontal
				root.setPadding(horizontalPadding, 0, horizontalPadding, 0)
			}
		} else {
			navBar.collapse()
			navRailHeader?.run {
				root.updateLayoutParams<FrameLayout.LayoutParams> {
					gravity = Gravity.TOP or Gravity.CENTER
				}
				railFab.shrink()
				buttonExpand.setImageResource(R.drawable.ic_drawer_menu)
				buttonExpand.setContentDescriptionAndTooltip(R.string.expand)
				val horizontalPadding = navBar.itemActiveIndicatorMarginHorizontal
				root.setPadding(horizontalPadding, 0, horizontalPadding, 0)
			}
		}
	}

	fun interface OnFragmentChangedListener {

		fun onFragmentChanged(fragment: Fragment, fromUser: Boolean)
	}

	companion object {

		const val MAX_ITEM_COUNT = 4
	}
}
