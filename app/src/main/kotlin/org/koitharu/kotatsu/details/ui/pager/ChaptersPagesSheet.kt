package org.koitharu.kotatsu.details.ui.pager

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ActionMode
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
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
		return SheetChaptersPagesBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetChaptersPagesBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		disableFitToContents()

		val args = arguments ?: Bundle.EMPTY
		var defaultTab = args.getInt(AppRouter.KEY_TAB, settings.defaultDetailsTab)
		// EPUB books have text chapters and highlights, but no page thumbnails.
		val isEpub = viewModel.getMangaOrNull()?.isEpub == true
		val adapter = ChaptersPagesAdapter(this, settings.isPagesTabEnabled && !isEpub)
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

		actionModeDelegate?.addListener(this, viewLifecycleOwner)
		addSheetCallback(this, viewLifecycleOwner)

		viewModel.newChaptersCount.observe(viewLifecycleOwner, ::onNewChaptersChanged)
		if (dialog != null) {
			viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(binding.pager, this))
			viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.pager))
			viewModel.onDownloadStarted.observeEvent(viewLifecycleOwner, DownloadStartedObserver(binding.pager))
		} else {
			PeekHeightController(arrayOf(binding.headerBar, binding.toolbar)).attach()
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat = insets

	override fun onStart() {
		super.onStart()
		val sheetDialog = dialog as? BottomSheetDialog
		applyDetailsSheetSurface(sheetDialog)
		// In the details screen the sheet opens at a centred, half-expanded position. Keep nested list
		// swipes for the RecyclerViews; only direct drags on the header/toolbar should move the sheet.
		if (viewModel is DetailsViewModel) {
			sheetDialog?.behavior?.apply {
				isFitToContents = false
				isHideable = true
				setDraggableOnNestedScroll(false)
				skipCollapsed = true
				halfExpandedRatio = HALF_EXPANDED_RATIO
				state = BottomSheetBehavior.STATE_HALF_EXPANDED
			}
		}
	}

	private fun applyDetailsSheetSurface(sheetDialog: BottomSheetDialog?) {
		val sheetView = sheetDialog?.findViewById<View>(materialR.id.design_bottom_sheet) ?: return
		val surfaceColor = requireActivity().getThemeColor(android.R.attr.colorBackground)
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
		// Recolour the sheet's MaterialShapeDrawable *fill* to the activity surface colour. Its style
		// backgroundTint resolves against the dialog theme, where android:colorBackground maps to a lighter
		// surface tone, so the raw fill is the wrong colour even though the view tint renders it correctly.
		// The contextual action bar (BaseAdaptiveSheet.resolveSheetSurfaceColor) reads this raw fillColor to
		// match the sheet, so it must hold the real surface colour. The drawable is assigned by
		// BottomSheetBehavior on its first layout pass (after onStart), so apply now and again post-layout.
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
		val progress = (slideOffset - DRAG_HANDLE_COLLAPSE_START) / (1f - DRAG_HANDLE_COLLAPSE_START)
		binding.headerBar.setDragHandleCollapseProgress(progress)
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

		const val TAB_CHAPTERS = 0
		const val TAB_PAGES = 1
		const val TAB_BOOKMARKS = 2

		// How much of the screen height the sheet covers when it first opens at the centre position.
		private const val HALF_EXPANDED_RATIO = 0.62f

		// Slide offset (0 = centre/half, 1 = full screen) at which the drag handle starts collapsing.
		// Kept above the half-expanded resting offset so the handle stays full at the centre position.
		private const val DRAG_HANDLE_COLLAPSE_START = 0.65f
	}
}
