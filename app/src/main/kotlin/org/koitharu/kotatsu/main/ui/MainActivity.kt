package org.koitharu.kotatsu.main.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.Insets
import androidx.core.view.GravityCompat
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS
import com.google.android.material.appbar.AppBarLayout.LayoutParams.SCROLL_FLAG_NO_SCROLL
import com.google.android.material.appbar.AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL
import com.google.android.material.appbar.AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.search.SearchView
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.browser.AdListUpdateService
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.NavItem
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.util.FadingAppbarMediator
import org.koitharu.kotatsu.core.ui.widgets.SlidingBottomNavigationView
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.applySystemAnimatorScale
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.end
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.setOptionalIconsVisibleCompat
import org.koitharu.kotatsu.core.util.ext.setProgressIcon
import org.koitharu.kotatsu.core.util.ext.start
import org.koitharu.kotatsu.databinding.ActivityMainBinding
import org.koitharu.kotatsu.details.service.MangaPrefetchService
import org.koitharu.kotatsu.favourites.ui.container.FavouritesContainerFragment
import org.koitharu.kotatsu.history.ui.HistoryListFragment
import org.koitharu.kotatsu.local.ui.LocalIndexUpdateService
import org.koitharu.kotatsu.local.ui.LocalStorageCleanupWorker
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.main.ui.owners.BottomNavOwner
import org.koitharu.kotatsu.main.ui.owners.MainFabInvalidator
import org.koitharu.kotatsu.main.ui.owners.MainFabOwner
import org.koitharu.kotatsu.main.ui.welcome.OnboardingActivity
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.remotelist.ui.MangaSearchMenuProvider
import org.koitharu.kotatsu.search.ui.suggestion.SearchSuggestionItemCallback
import org.koitharu.kotatsu.search.ui.suggestion.SearchSuggestionListenerImpl
import org.koitharu.kotatsu.search.ui.suggestion.SearchSuggestionMenuProvider
import org.koitharu.kotatsu.search.ui.suggestion.SearchSuggestionViewModel
import org.koitharu.kotatsu.search.ui.suggestion.adapter.SearchSuggestionAdapter
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : BaseActivity<ActivityMainBinding>(), AppBarOwner, BottomNavOwner, MainFabInvalidator,
	View.OnClickListener,
	SearchSuggestionItemCallback.SuggestionItemListener,
	MainNavigationDelegate.OnFragmentChangedListener,
	View.OnLayoutChangeListener,
	SearchView.TransitionListener {

	@Inject
	lateinit var settings: AppSettings

	private val viewModel by viewModels<MainViewModel>()
	private val searchSuggestionViewModel by viewModels<SearchSuggestionViewModel>()
	private lateinit var navigationDelegate: MainNavigationDelegate
	private lateinit var fadingAppbarMediator: FadingAppbarMediator
	private val overflowMenuProviders = mutableListOf<OverflowMenuProviderEntry>()
	private var isSearchFullyShown = false
	private var mainFabModeKey: String? = null
	private val shrinkFabRunnable = Runnable { viewBinding.fab?.shrink() }
	private var navSystemBarBottom: Int = 0

	override val appBar: AppBarLayout
		get() = viewBinding.appbar

	override val bottomNav: SlidingBottomNavigationView?
		get() = viewBinding.bottomNav

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		if (!settings.isOnboardingCompleted) {
			startActivity(Intent(this, OnboardingActivity::class.java))
			finish()
			return
		}
		setContentView(ActivityMainBinding.inflate(layoutInflater))
		setSupportActionBar(viewBinding.searchBar)
		// Place the search icon inline, right before the hint, and centre the whole group.
		viewBinding.searchBar.textView.apply {
			gravity = android.view.Gravity.CENTER
			includeFontPadding = false
			setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
			compoundDrawablePadding = resources.getDimensionPixelOffset(R.dimen.margin_small)
		}

		viewBinding.fab?.setOnClickListener(this)
		viewBinding.navRail?.headerView?.findViewById<View>(R.id.railFab)?.setOnClickListener(this)
		viewBinding.bottomNav?.setOnContinueClickListener { viewModel.openLastReader() }
		viewBinding.buttonOverflow.setOnClickListener(this::showMainOverflowMenu)
		viewBinding.buttonSettings.setOnClickListener {
			router.openSettings()
		}
		applyStatusBarScrim()
		fadingAppbarMediator =
			FadingAppbarMediator(viewBinding.appbar, viewBinding.layoutSearch)

		navigationDelegate = MainNavigationDelegate(
			navBar = checkNotNull(bottomNav ?: viewBinding.navRail),
			fragmentManager = supportFragmentManager,
			settings = settings,
		)
		navigationDelegate.addOnFragmentChangedListener(this)
		navigationDelegate.onExploreReselected = {
			if (isSearchFullyShown) {
				viewBinding.searchView.hide()
			} else if (!viewBinding.searchView.isShowing) {
				viewBinding.searchView.show()
			}
		}
		navigationDelegate.onCreate(this, savedInstanceState)
		viewBinding.textViewTitle?.let { tv ->
			navigationDelegate.observeTitle().observe(this) { tv.text = it }
		}

		addMainOverflowMenuProvider(MainMenuProvider(viewModel))

		val exitCallback = ExitCallback(this, viewBinding.container)
		onBackPressedDispatcher.addCallback(exitCallback)
		onBackPressedDispatcher.addCallback(navigationDelegate)

		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !resources.getBoolean(R.bool.is_predictive_back_enabled)) {
			val legacySearchCallback = SearchViewLegacyBackCallback(viewBinding.searchView)
			viewBinding.searchView.addTransitionListener(legacySearchCallback)
			onBackPressedDispatcher.addCallback(legacySearchCallback)
		}

		if (savedInstanceState == null) {
			onFirstStart()
		}

		viewModel.onOpenReader.observeEvent(this, this::onOpenReader)
		viewModel.onError.observeEvent(this, SnackbarErrorObserver(viewBinding.container, null))
		viewModel.isLoading.observe(this, this::onLoadingStateChanged)
		viewModel.isResumeEnabled.observe(this, this::onResumeEnabledChanged)
		settings.observe(AppSettings.KEY_NAV_LEGACY).observe(this) { adjustFabVisibility() }
		viewModel.feedCounter.observe(this, ::onFeedCounterChanged)
		viewModel.hasExtensionUpdates.observe(this) { hasUpdates ->
			navigationDelegate.setCounter(NavItem.EXPLORE, if (hasUpdates) -1 else 0)
		}
		viewModel.appUpdate.observe(this) { update ->
			viewBinding.badgeSettingsUpdate.visibility = if (update != null) View.VISIBLE else View.GONE
		}
		viewModel.isBottomNavPinned.observe(this, ::setNavbarPinned)
		searchSuggestionViewModel.isIncognitoModeEnabled.observe(this, this::onIncognitoModeChanged)
		viewBinding.bottomNav?.addOnLayoutChangeListener(this)
		setupContainerInsetsListener()
		viewBinding.searchView.addTransitionListener(this)
		viewBinding.searchView.addTransitionListener(exitCallback)
		observeFoldHinge()
		initSearch()
	}

	override fun onRestoreInstanceState(savedInstanceState: Bundle) {
		super.onRestoreInstanceState(savedInstanceState)
		isSearchFullyShown = viewBinding.searchView.isShowing
		adjustSearchUI(viewBinding.searchView.isShowing)
		navigationDelegate.syncSelectedItem()
	}

	override fun onFragmentChanged(fragment: Fragment, fromUser: Boolean) {
		adjustFabVisibility(topFragment = fragment)
		adjustAppbar(topFragment = fragment)
		if (fromUser) {
			actionModeDelegate.finishActionMode()
			viewBinding.appbar.setExpanded(true)
		}
	}

	override fun onSupportNavigateUp(): Boolean {
		if (!viewBinding.searchView.isShowing) {
			viewBinding.searchView.show()
		}
		return true
	}

	override fun addMenuProvider(provider: MenuProvider, owner: LifecycleOwner, state: Lifecycle.State) {
		if (provider !is MangaSearchMenuProvider) { // do not duplicate search menu item
			addMainOverflowMenuProvider(provider, owner, state)
		}
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.fab, R.id.railFab -> {
				v.hapticFeedback(HapticEffect.CONFIRM)
				val fabOwner = navigationDelegate.primaryFragment as? MainFabOwner
				if (fabOwner != null) {
					fabOwner.onMainFabClick()
				} else {
					viewModel.openLastReader()
				}
			}
		}
	}

	override fun invalidateMainFab() {
		adjustFabVisibility()
	}

	private fun addMainOverflowMenuProvider(
		provider: MenuProvider,
		owner: LifecycleOwner? = null,
		state: Lifecycle.State = Lifecycle.State.CREATED,
	) {
		val entry = OverflowMenuProviderEntry(provider, owner, state)
		overflowMenuProviders += entry
		owner?.lifecycle?.addObserver(
			LifecycleEventObserver { _, event ->
				if (event == Lifecycle.Event.ON_DESTROY) {
					overflowMenuProviders -= entry
				}
			},
		)
	}

	private fun showMainOverflowMenu(anchor: View) {
		val providers = overflowMenuProviders
			.filter { it.isActive }
			.map { it.provider }
		val popup = PopupMenu(anchor.context, anchor, GravityCompat.END)
		val menu = popup.menu
		providers.forEach { it.onCreateMenu(menu, popup.menuInflater) }
		providers.forEach { it.onPrepareMenu(menu) }
		if (!menu.hasVisibleItems()) {
			return
		}
		menu.setOptionalIconsVisibleCompat(true)
		popup.setForceShowIcon(true)
		popup.setOnMenuItemClickListener { item ->
			providers.any { it.onMenuItemSelected(item) }
		}
		popup.setOnDismissListener {
			providers.forEach { it.onMenuClosed(menu) }
		}
		popup.show()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		navSystemBarBottom = barsInsets.bottom
		val searchBarDefaultMargin = resources.getDimensionPixelOffset(R.dimen.search_bar_margin_horizontal)
		viewBinding.layoutSearch.updateLayoutParams<MarginLayoutParams> {
			marginEnd = searchBarDefaultMargin + barsInsets.end(v)
			marginStart = if (viewBinding.navRail != null) {
				searchBarDefaultMargin
			} else {
				searchBarDefaultMargin + barsInsets.start(v)
			}
		}
		viewBinding.bottomNav?.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
			bottom = barsInsets.bottom,
		)
		viewBinding.navRail?.updateLayoutParams<MarginLayoutParams> {
			marginStart = barsInsets.start(v)
			topMargin = barsInsets.top
			bottomMargin = barsInsets.bottom
		}
		// Size the top scrim to cover the status bar and fade out just below it, so content that
		// scrolls up under the (transparent) status bar stays legible instead of looking messy.
		viewBinding.statusBarScrim.updateLayoutParams {
			height = barsInsets.top + resources.getDimensionPixelOffset(R.dimen.margin_normal)
		}
		updateContainerBottomMargin()
		return insets.consume(v, typeMask, start = viewBinding.navRail != null).also {
			handleSearchSuggestionsInsets(it)
		}
	}

	override fun onLayoutChange(
		v: View?,
		left: Int,
		top: Int,
		right: Int,
		bottom: Int,
		oldLeft: Int,
		oldTop: Int,
		oldRight: Int,
		oldBottom: Int,
	) {
		if (top != oldTop || bottom != oldBottom) {
			updateContainerBottomMargin()
			if (settings.isNavBarPinned && !settings.isLegacyNavigationBar) {
				ViewCompat.requestApplyInsets(viewBinding.container)
			}
		}
	}

	override fun onStateChanged(
		searchView: SearchView,
		previousState: SearchView.TransitionState,
		newState: SearchView.TransitionState,
	) {
		val wasOpened = previousState >= SearchView.TransitionState.SHOWING
		val isOpened = newState >= SearchView.TransitionState.SHOWING
		if (isOpened != wasOpened) {
			adjustSearchUI(isOpened)
		}
		isSearchFullyShown = (newState == SearchView.TransitionState.SHOWN)
	}

	override fun onRemoveQuery(query: String) {
		searchSuggestionViewModel.deleteQuery(query)
	}

	override fun onSupportActionModeStarted(mode: ActionMode) {
		super.onSupportActionModeStarted(mode)
		adjustFabVisibility()
		bottomNav?.hide()
		viewBinding.layoutSearch.fadeOutForActionMode()
		updateContainerBottomMargin()
	}

	override fun onSupportActionModeFinished(mode: ActionMode) {
		super.onSupportActionModeFinished(mode)
		adjustFabVisibility()
		bottomNav?.show()
		actionModeDelegate.runAfterActionModeExit {
			viewBinding.layoutSearch.fadeInAfterActionMode()
			updateContainerBottomMargin()
		}
	}

	private fun onOpenReader(manga: Manga) {
		val fab = viewBinding.fab ?: viewBinding.navRail?.headerView
		router.openReader(manga, fab)
	}

	private fun onFeedCounterChanged(counter: Int) {
		navigationDelegate.setCounter(NavItem.FEED, counter)
	}

	private fun onIncognitoModeChanged(isIncognito: Boolean) {
		var options = viewBinding.searchView.getEditText().imeOptions
		options = if (isIncognito) {
			options or EditorInfoCompat.IME_FLAG_NO_PERSONALIZED_LEARNING
		} else {
			options and EditorInfoCompat.IME_FLAG_NO_PERSONALIZED_LEARNING.inv()
		}
		viewBinding.searchView.getEditText().imeOptions = options
		invalidateOptionsMenu()
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		adjustFabVisibility()
	}

	private fun onResumeEnabledChanged(isEnabled: Boolean) {
		adjustFabVisibility(isResumeEnabled = isEnabled)
	}

	private fun onFirstStart() = try {
		lifecycleScope.launch(Dispatchers.Main) { // not a default `Main.immediate` dispatcher
			withContext(Dispatchers.Default) {
				LocalStorageCleanupWorker.enqueue(applicationContext)
			}
			withResumed {
				MangaPrefetchService.prefetchLast(this@MainActivity)
				startService(Intent(this@MainActivity, LocalIndexUpdateService::class.java))
				if (settings.isAdBlockEnabled) {
					startService(Intent(this@MainActivity, AdListUpdateService::class.java))
				}
			}
		}
	} catch (e: IllegalStateException) {
		e.printStackTraceDebug()
	}

	private fun adjustAppbar(topFragment: Fragment) {
		if (topFragment is FavouritesContainerFragment) {
			viewBinding.appbar.fitsSystemWindows = true
			fadingAppbarMediator.bind()
			// On first launch the freshly-added Favourites fragment and its ViewPager2 settle their
			// layout *after* the app bar has already measured its scroll range with the just-toggled
			// fitsSystemWindows, so the search bar + category tabs won't collapse on scroll until
			// something forces a re-measure — which is why it only starts working after switching tabs
			// and back. Re-dispatch insets and re-measure the app bar once laid out so it scrolls off
			// from the very first open too.
			viewBinding.appbar.post {
				ViewCompat.requestApplyInsets(viewBinding.appbar)
				viewBinding.appbar.requestLayout()
			}
		} else {
			viewBinding.appbar.fitsSystemWindows = false
			fadingAppbarMediator.unbind()
		}
	}

	private fun adjustFabVisibility(
		isResumeEnabled: Boolean = viewModel.isResumeEnabled.value,
		topFragment: Fragment? = navigationDelegate.primaryFragment,
		isSearchOpened: Boolean = viewBinding.searchView.isShowing,
	) {
		val fragment = if (topFragment?.isAdded == true) {
			topFragment
		} else {
			navigationDelegate.primaryFragment
		}
		// When the modern floating bar is shown, the "continue reading" action lives on it as a
		// standalone circular button (handled below), so it must never appear as a FAB. In legacy
		// navigation mode — or on the tablet nav rail, which has no floating bar — it keeps the old
		// behaviour of a FAB shown only inside the history tab.
		val useFloatingContinue = viewBinding.bottomNav != null && !settings.isLegacyNavigationBar
		val fabOwner = fragment as? MainFabOwner
		if (fabOwner != null && !actionModeDelegate.isActionModeStarted && !isSearchOpened) {
			showMainFab("owner:${fragment::class.java.name}") { fab ->
				configureOwnerFab(fab, fabOwner)
			}
		} else if (!useFloatingContinue && isResumeEnabled && !actionModeDelegate.isActionModeStarted &&
			!isSearchOpened && fragment is HistoryListFragment
		) {
			showMainFab("continue", ::configureContinueFab)
		} else {
			hideMainFab()
		}
		viewBinding.bottomNav?.setContinueVisible(useFloatingContinue && isResumeEnabled)
	}

	private fun showMainFab(
		modeKey: String,
		configure: (ExtendedFloatingActionButton) -> Unit,
	) {
		navigationDelegate.navRailHeader?.railFab?.let { railFab ->
			configure(railFab)
			railFab.isVisible = true
		}
		val fab = viewBinding.fab ?: run {
			mainFabModeKey = modeKey
			return
		}
		configure(fab)
		val isModeChanged = mainFabModeKey != modeKey
		mainFabModeKey = modeKey
		if (!fab.isVisible || isModeChanged) {
			// Show the full label, then collapse to just the icon after a short delay every time the
			// owning page is (re)opened.
			fab.extend()
			fab.show()
			fab.removeCallbacks(shrinkFabRunnable)
			fab.postDelayed(shrinkFabRunnable, FAB_SHRINK_DELAY_MS)
		}
	}

	private fun hideMainFab() {
		navigationDelegate.navRailHeader?.railFab?.isVisible = false
		mainFabModeKey = null
		val fab = viewBinding.fab ?: return
		if (fab.isVisible) {
			fab.removeCallbacks(shrinkFabRunnable)
			fab.hide()
		}
	}

	private fun configureContinueFab(fab: ExtendedFloatingActionButton) {
		fab.setText(R.string._continue)
		fab.setIconResource(R.drawable.ic_read)
		fab.isEnabled = !viewModel.isLoading.value
	}

	private fun configureOwnerFab(fab: ExtendedFloatingActionButton, owner: MainFabOwner) {
		fab.setText(owner.mainFabTextRes)
		if (owner.isMainFabLoading) {
			fab.setProgressIcon()
		} else {
			fab.setIconResource(owner.mainFabIconRes)
		}
		fab.isEnabled = owner.isMainFabEnabled
	}

	private fun adjustSearchUI(isOpened: Boolean) {
		val appBarScrollFlags = if (isOpened) {
			SCROLL_FLAG_NO_SCROLL
		} else {
			SCROLL_FLAG_SCROLL or SCROLL_FLAG_ENTER_ALWAYS or SCROLL_FLAG_SNAP
		}
		viewBinding.insetsHolder.updateLayoutParams<AppBarLayout.LayoutParams> {
			scrollFlags = appBarScrollFlags
		}
		adjustFabVisibility(isSearchOpened = isOpened)
		bottomNav?.showOrHide(!isOpened)
		updateContainerBottomMargin()
	}

	// Builds the themed top-of-screen fade: densest (~85%) at the very top where the status bar sits,
	// then a smooth multi-stop fade to fully transparent. The colour follows the app's surface colour
	// so it adapts to the active theme/colour scheme.
	private fun applyStatusBarScrim() {
		val scrim = viewBinding.statusBarScrim
		val surface = MaterialColors.getColor(scrim, com.google.android.material.R.attr.colorSurface)
		val alphas = floatArrayOf(0.85f, 0.62f, 0.4f, 0.22f, 0.1f, 0f)
		val colors = IntArray(alphas.size) { i ->
			ColorUtils.setAlphaComponent(surface, (alphas[i] * 255f).toInt())
		}
		scrim.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors)
	}

	private fun handleSearchSuggestionsInsets(insets: WindowInsetsCompat) {
		val typeMask = WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		// Add a gap between the active search field and the content below it (filter chips / suggestions).
		val topGap = resources.getDimensionPixelOffset(R.dimen.list_spacing_large)
		viewBinding.recyclerViewSearch.setPadding(barsInsets.left, topGap, barsInsets.right, barsInsets.bottom)
	}

	private fun initSearch() {
		val listener = SearchSuggestionListenerImpl(router, viewBinding.searchView, searchSuggestionViewModel)
		val adapter = SearchSuggestionAdapter(listener)
		viewBinding.searchView.toolbar.addMenuProvider(
			SearchSuggestionMenuProvider(this, searchSuggestionViewModel),
		)
		viewBinding.searchView.editText.addTextChangedListener(listener)
		viewBinding.recyclerViewSearch.adapter = adapter
		viewBinding.searchView.editText.setOnEditorActionListener(listener)

		viewBinding.searchView.observeState()
			.map { it >= SearchView.TransitionState.SHOWING }
			.distinctUntilChanged()
			.flatMapLatest { isShowing ->
				if (isShowing) {
					searchSuggestionViewModel.suggestion
				} else {
					emptyFlow()
				}
			}.observe(this, adapter)
		searchSuggestionViewModel.onError.observeEvent(
			this,
			SnackbarErrorObserver(viewBinding.recyclerViewSearch, null),
		)
		ItemTouchHelper(SearchSuggestionItemCallback(this))
			.attachToRecyclerView(viewBinding.recyclerViewSearch)
	}


	private fun setNavbarPinned(isPinned: Boolean) {
		val bottomNavBar = viewBinding.bottomNav
		bottomNavBar?.isPinned = isPinned
		for (view in viewBinding.appbar.children) {
			val lp = view.layoutParams as? AppBarLayout.LayoutParams ?: continue
			val scrollFlags = if (isPinned) {
				lp.scrollFlags and SCROLL_FLAG_SCROLL.inv()
			} else {
				lp.scrollFlags or SCROLL_FLAG_SCROLL
			}
			if (scrollFlags != lp.scrollFlags) {
				lp.scrollFlags = scrollFlags
				view.layoutParams = lp
			}
		}
		updateContainerBottomMargin()
		// Re-dispatch window insets so child fragments update their RecyclerView bottom padding.
		ViewCompat.requestApplyInsets(viewBinding.container)
	}

	private fun setupContainerInsetsListener() {
		// When the nav bar is pinned, augment the bottom system-bar inset dispatched to child
		// fragments by the pill's visual height. Fragment RecyclerViews apply this as bottom
		// padding, so the last row can always be scrolled fully into view above the bar — without
		// adding a container margin that would break the edge-to-edge look of the gesture area.
		ViewCompat.setOnApplyWindowInsetsListener(viewBinding.container) { _, windowInsets ->
			val extraBottom = if (settings.isNavBarPinned && !settings.isLegacyNavigationBar) {
				((viewBinding.bottomNav?.height ?: 0) - navSystemBarBottom).coerceAtLeast(0)
			} else {
				0
			}
			if (extraBottom == 0) {
				windowInsets
			} else {
				val type = WindowInsetsCompat.Type.systemBars()
				val current = windowInsets.getInsets(type)
				WindowInsetsCompat.Builder(windowInsets)
					.setInsets(
						type,
						Insets.of(current.left, current.top, current.right, current.bottom + extraBottom),
					)
					.build()
			}
		}
	}

	private fun updateContainerBottomMargin() {
		// The bottom navigation bar is a floating pill. Content fills the full height *behind* it
		// so the bar looks floating — the container never gets a bottom margin for the nav bar.
		// Extra scrollable space when pinned is handled by augmenting window insets dispatched to
		// child fragments (see the container insets listener set up in onCreate).
		with(viewBinding.container) {
			val params = layoutParams as MarginLayoutParams
			if (params.bottomMargin != 0) {
				params.bottomMargin = 0
				layoutParams = params
			}
		}
	}

	private fun View.fadeOutForActionMode() {
		animate().cancel()
		isInvisible = false
		animate()
			.alpha(0f)
			.setDuration(ACTION_MODE_TOP_BAR_FADE_DURATION_MS)
			.applySystemAnimatorScale(context)
			.withEndAction {
				isInvisible = true
				alpha = 0f
			}
			.start()
	}

	private fun View.fadeInAfterActionMode() {
		animate().cancel()
		val targetAlpha = fadingAppbarMediator.targetAlpha
		alpha = 0f
		isInvisible = false
		animate()
			.alpha(targetAlpha)
			.setDuration(ACTION_MODE_TOP_BAR_FADE_DURATION_MS)
			.applySystemAnimatorScale(context)
			.withEndAction {
				alpha = targetAlpha
			}
			.start()
	}

	private fun observeFoldHinge() {
		val spacer = viewBinding.foldHingeSpacer ?: return
		lifecycleScope.launch {
			WindowInfoTracker.getOrCreate(this@MainActivity)
				.windowLayoutInfo(this@MainActivity)
				.collect { layoutInfo ->
					val hingeWidth = layoutInfo.displayFeatures
						.filterIsInstance<FoldingFeature>()
						.firstOrNull { it.isSeparating && it.orientation == FoldingFeature.Orientation.VERTICAL }
						?.bounds
						?.width()
						?: 0
					spacer.isVisible = hingeWidth > 0
					spacer.updateLayoutParams<LinearLayout.LayoutParams> {
						width = hingeWidth
					}
				}
		}
	}

	private fun SearchView.observeState() = callbackFlow {
		val listener = SearchView.TransitionListener { _, _, state ->
			trySendBlocking(state)
		}
		addTransitionListener(listener)
		awaitClose { removeTransitionListener(listener) }
	}

	private companion object {

		private const val FAB_SHRINK_DELAY_MS = 1500L
		private const val ACTION_MODE_TOP_BAR_FADE_DURATION_MS = 150L
	}
}

private data class OverflowMenuProviderEntry(
	val provider: MenuProvider,
	val owner: LifecycleOwner?,
	val minState: Lifecycle.State,
) {

	val isActive: Boolean
		get() = owner?.lifecycle?.currentState?.isAtLeast(minState) ?: true
}
