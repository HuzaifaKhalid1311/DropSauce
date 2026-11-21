package org.koitharu.kotatsu.details.ui.pager

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.appcompat.view.ActionMode
import androidx.appcompat.view.ContextThemeWrapper
import androidx.fragment.app.Fragment
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_COLLAPSED
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_DRAGGING
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_EXPANDED
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_HIDDEN
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_SETTLING
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetCallback
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.ui.util.ActionModeListener
import org.koitharu.kotatsu.core.ui.util.MenuInvalidator
import org.koitharu.kotatsu.core.ui.util.applyTonalActionMenuStyle
import org.koitharu.kotatsu.core.ui.util.RecyclerViewOwner
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.doOnPageChanged
import org.koitharu.kotatsu.core.util.ext.findCurrentPagerFragment
import org.koitharu.kotatsu.core.util.ext.menuView
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.recyclerView
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.smoothScrollToTop
import org.koitharu.kotatsu.databinding.SheetChaptersPagesBinding
import org.koitharu.kotatsu.details.ui.DetailsExpressiveActivity
import org.koitharu.kotatsu.details.ui.DetailsViewModel
import org.koitharu.kotatsu.local.data.isEpub
import org.koitharu.kotatsu.download.ui.worker.DownloadStartedObserver
import javax.inject.Inject
import com.google.android.material.R as materialR

@AndroidEntryPoint
class ChaptersPagesSheet : BaseAdaptiveSheet<SheetChaptersPagesBinding>(),
	TabLayout.OnTabSelectedListener,
	ActionModeListener,
	AdaptiveSheetCallback {

	@Inject
	lateinit var settings: AppSettings

	private val viewModel by ChaptersPagesViewModel.ActivityVMLazy(this)

	private var keepTabsVisible = false

	override fun getTheme(): Int {
		return if (context?.resources?.getBoolean(R.bool.is_tablet) == true) {
			super.getTheme()
		} else {
			R.style.ThemeOverlay_Kotatsu_DetailsBottomSheetDialog
		}
	}

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetChaptersPagesBinding {
		// As a popup the sheet is a lighter surface, so its tab indicator and toolbar pill go a tone up.
		val themedInflater = if (showsDialog) {
			inflater.cloneInContext(ContextThemeWrapper(inflater.context, R.style.ThemeOverlay_Kotatsu_RaisedContainers))
		} else {
			inflater
		}
		return SheetChaptersPagesBinding.inflate(themedInflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetChaptersPagesBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		disableFitToContents()
		binding.headerBar.setCompactDragHandle()

		val args = arguments ?: Bundle.EMPTY
		var defaultTab = args.getInt(AppRouter.KEY_TAB, settings.defaultDetailsTab)
		// EPUB books have text chapters and highlights, but no page thumbnails.
		val isEpub = viewModel.getMangaOrNull()?.isEpub == true
		val adapter = ChaptersPagesAdapter(this, settings.isPagesTabEnabled && !isEpub, isEpub)
		if (isEpub) {
			defaultTab = if (defaultTab == TAB_BOOKMARKS) 1 else TAB_CHAPTERS
		} else if (!adapter.isPagesTabEnabled) {
			defaultTab = (defaultTab - 1).coerceAtLeast(TAB_CHAPTERS)
		}
		binding.pager.offscreenPageLimit = adapter.itemCount
		binding.pager.recyclerView?.isNestedScrollingEnabled = false
		binding.pager.adapter = adapter
		binding.pager.doOnPageChanged(::onPageChanged)
		TabLayoutMediator(binding.tabs, binding.pager, adapter).attach()
		binding.tabs.addOnTabSelectedListener(this)
		binding.pager.setCurrentItem(defaultTab, false)
		keepTabsVisible = false
		binding.tabs.isVisible = adapter.itemCount > 1 || keepTabsVisible

		val menuProvider = ChapterPagesMenuProvider(this, binding.pager, settings, viewModel, binding.layoutToolbarContent)
		onBackPressedDispatcher.addCallback(viewLifecycleOwner, menuProvider)
		binding.toolbar.addMenuProvider(menuProvider)
		// Group the search + overflow actions into the same tonal pill the rest of the app's top bars use.
		binding.toolbar.applyTonalActionMenuStyle()

		val menuInvalidator = MenuInvalidator(binding.toolbar)
		viewModel.isChaptersReversed.observe(viewLifecycleOwner, menuInvalidator)
		viewModel.isChaptersInGridView.observe(viewLifecycleOwner, menuInvalidator)
		viewModel.isDownloadedOnly.observe(viewLifecycleOwner, menuInvalidator)
		viewModel.chaptersSortOrder.observe(viewLifecycleOwner, menuInvalidator)

		actionModeDelegate?.addListener(this, viewLifecycleOwner)
		addSheetCallback(this, viewLifecycleOwner)

		viewModel.newChaptersCount.observe(viewLifecycleOwner, ::onNewChaptersChanged)
		// Embedded as the details screen's right pane (no dialog), the activity shows these messages.
		if (dialog != null) {
			viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(binding.pager, this))
			viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.pager))
			viewModel.onDownloadStarted.observeEvent(viewLifecycleOwner, DownloadStartedObserver(binding.pager))
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

	override fun onStart() {
		super.onStart()
		val sheetDialog = dialog as? BottomSheetDialog
		applyDetailsSheetSurface(sheetDialog)
		// In the details screen the sheet opens half-expanded, its top level with the progress card (just
		// under the cover in the centred layout). Keep nested list swipes for the RecyclerViews; only
		// direct drags on the header/toolbar move the sheet.
		if (viewModel is DetailsViewModel) {
			setHalfExpanded(anchoredHalfRatio())
		}
		sheetDialog?.findViewById<View>(materialR.id.design_bottom_sheet)
			?.viewTreeObserver?.addOnPreDrawListener(fitPagerToScreen)
	}

	override fun onStop() {
		dialog?.findViewById<View>(materialR.id.design_bottom_sheet)
			?.viewTreeObserver?.removeOnPreDrawListener(fitPagerToScreen)
		super.onStop()
	}

	// The sheet is match_parent tall, so short of full screen its bottom hangs off-screen and the lists'
	// last items (and the fast-scroll track's end) can't be reached. Pad the pager up to the screen edge.
	// Runs before every draw because the offset moves on drag, settle and first layout alike.
	// While the sheet is moving the pager may only grow (so rising content is revealed live); it is
	// trimmed once the sheet rests. Otherwise dragging the sheet away would squeeze the lists to
	// nothing, and the only part that changes is off-screen anyway.
	private val fitPagerToScreen = ViewTreeObserver.OnPreDrawListener {
		val sheet = dialog?.findViewById<View>(materialR.id.design_bottom_sheet)
		val parent = sheet?.parent as? View
		val touchBlock = viewBinding?.layoutTouchBlock
		if (sheet != null && parent != null && touchBlock != null) {
			val offScreen = (sheet.bottom - parent.height).coerceAtLeast(0)
			val hidden = when (behavior?.state) {
				STATE_DRAGGING, STATE_SETTLING, STATE_HIDDEN -> minOf(offScreen, touchBlock.paddingBottom)
				else -> offScreen
			}
			if (hidden != touchBlock.paddingBottom) touchBlock.updatePadding(bottom = hidden)
		}
		true
	}

	/** Share of the screen below the details screen's anchor; the default when it is off screen or too low. */
	private fun anchoredHalfRatio(): Float {
		val anchor = (activity as? DetailsExpressiveActivity)?.chaptersSheetTop ?: Float.NaN
		val root = requireActivity().window.decorView
		val rootTop = IntArray(2).also(root::getLocationOnScreen)[1]
		val ratio = (rootTop + root.height - anchor) / root.height
		return if (ratio in 0.3f..0.92f) ratio else HALF_EXPANDED_RATIO
	}

	private fun applyDetailsSheetSurface(sheetDialog: BottomSheetDialog?) {
		val sheetView = sheetDialog?.findViewById<View>(materialR.id.design_bottom_sheet) ?: return
		val surfaceColor = detailsSheetSurfaceColor()
		val surfaceColorState = ColorStateList.valueOf(surfaceColor)
		sheetView.elevation = 0f
		sheetView.clipToOutline = true
		sheetView.backgroundTintList = surfaceColorState
		viewBinding?.run {
			root.setBackgroundColor(surfaceColor)
			headerBar.setBackgroundColor(surfaceColor)
			toolbar.setBackgroundColor(surfaceColor)
			layoutTouchBlock.setBackgroundColor(surfaceColor)
			pager.setBackgroundColor(surfaceColor)
		}
		// Pin the sheet's MaterialShapeDrawable fill (no elevation overlay) to the same colour. The contextual
		// action bar (BaseAdaptiveSheet.resolveSheetSurfaceColor) reads this raw fillColor to match the sheet.
		// The drawable is assigned by BottomSheetBehavior on its first layout pass (after onStart), so apply
		// now and again post-layout.
		fun applyShapeSurface() {
			(sheetView.background as? MaterialShapeDrawable)?.apply {
				fillColor = surfaceColorState
				tintList = surfaceColorState
				elevation = 0f
				parentAbsoluteElevation = 0f
			}
		}
		applyShapeSurface()
		sheetView.doOnLayout { applyShapeSurface() }
	}

	override fun onStateChanged(sheet: View, newState: Int) {
		val binding = viewBinding ?: return
		binding.layoutTouchBlock.isTouchEventsAllowed = dialog != null || newState != STATE_COLLAPSED
		if (newState == STATE_DRAGGING || newState == STATE_SETTLING) {
			return
		}
		val isActionModeStarted = actionModeDelegate?.isActionModeStarted == true
		// The search + list-options pill is available whenever the sheet is on screen as a modal (it opens
		// at the centre position) or fully expanded — it only hides during selection action mode.
		val isModalOrExpanded = dialog != null || newState == STATE_EXPANDED
		binding.toolbar.menuView?.isVisible = isModalOrExpanded && !isActionModeStarted
		// Snap the drag handle to its final collapse state. During a manual drag onSlide already drove it
		// there continuously, so this is a no-op then; it only matters for programmatic state changes
		// (e.g. entering selection action mode) where no slide occurs.
		binding.headerBar.setDragHandleCollapseProgress(if (newState == STATE_EXPANDED) 1f else 0f)
	}

	override fun onSlide(sheet: View, slideOffset: Float) {
		// Melt the drag handle away over the top stretch of the drag so reaching full screen is one
		// seamless upward motion rather than a rise followed by a separate "handle disappears" step.
		val binding = viewBinding ?: return
		binding.headerBar.setDragHandleCollapseFromSlide(slideOffset)
	}

	override fun onActionModeStarted(mode: ActionMode) {
		viewBinding?.toolbar?.menuView?.isVisible = false
		view?.post(::expandAndLock)
	}

	override fun onActionModeFinished(mode: ActionMode) {
		unlock()
		val state = behavior?.state ?: STATE_EXPANDED
		viewBinding?.toolbar?.menuView?.isVisible = state != STATE_COLLAPSED
	}

	override fun onTabSelected(tab: TabLayout.Tab?) = Unit

	override fun onTabUnselected(tab: TabLayout.Tab?) = Unit

	override fun onTabReselected(tab: TabLayout.Tab?) {
		val f = childFragmentManager.findCurrentPagerFragment(
			viewBinding?.pager ?: return,
		) as? RecyclerViewOwner ?: return
		f.recyclerView?.smoothScrollToTop()
	}

	override fun expandAndLock() {
		super.expandAndLock()
		adjustLockState()
	}

	override fun unlock() {
		super.unlock()
		adjustLockState()
	}

	private fun adjustLockState() {
		viewBinding?.run {
			pager.isUserInputEnabled = !isLocked
			tabs.visibility = when {
				(pager.adapter?.itemCount ?: 0) <= 1 && !keepTabsVisible -> View.GONE
				isLocked -> View.INVISIBLE
				else -> View.VISIBLE
			}
		}
	}

	private fun onPageChanged(position: Int) {
		viewBinding?.toolbar?.invalidateMenu()
		val adapter = viewBinding?.pager?.adapter as? ChaptersPagesAdapter
		settings.lastDetailsTab = if (position == 1 && adapter?.isPagesTabEnabled == false) {
			TAB_BOOKMARKS
		} else {
			position
		}
	}

	private fun onNewChaptersChanged(counter: Int) {
		val tab = viewBinding?.tabs?.getTabAt(0) ?: return
		if (counter == 0) {
			tab.removeBadge()
		} else {
			val badge = tab.orCreateBadge
			badge.number = counter
		}
	}

	companion object {

		/** The sheet's surface: a lighter container tone as a popup, the screen's own background as the tablet pane. */
		fun Fragment.detailsSheetSurfaceColor(): Int {
			val sheet = this as? ChaptersPagesSheet ?: parentFragment as? ChaptersPagesSheet
			return requireActivity().getThemeColor(
				if (sheet?.dialog != null) materialR.attr.colorSurfaceContainerLow else android.R.attr.colorBackground,
			)
		}

		const val TAB_CHAPTERS = 0
		const val TAB_PAGES = 1
		const val TAB_BOOKMARKS = 2
	}
}
