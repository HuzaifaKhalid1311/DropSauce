package org.koitharu.kotatsu.filter.ui.mihon

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.shape.MaterialShapeDrawable
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_DRAGGING
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_EXPANDED
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_SETTLING
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetCallback
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.SheetFilterMihonBinding
import org.koitharu.kotatsu.filter.ui.FilterCoordinator
import org.koitharu.kotatsu.filter.ui.showSaveFilterDialog
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import com.google.android.material.R as materialR

@AndroidEntryPoint
class MihonFilterSheetFragment : BaseAdaptiveSheet<SheetFilterMihonBinding>(), AdaptiveSheetCallback {

	private val viewModel by viewModels<MihonFilterViewModel>(
		extrasProducer = {
			defaultViewModelCreationExtras.withCreationCallback<MihonFilterViewModel.Factory> { factory ->
				factory.create(FilterCoordinator.require(this))
			}
		},
	)

	// Insets and sheet-offset driven padding for the Compose list, in pixels.
	private val listPaddingLeft = mutableIntStateOf(0)
	private val listPaddingRight = mutableIntStateOf(0)
	private val listPaddingBottom = mutableIntStateOf(0)

	// Height the filter list needs when it fits on screen, reported by the Compose list; null when
	// the content is taller than the viewport (or isn't measurable yet).
	private val listContentHeight = mutableStateOf<Int?>(null)

	// The pinned button row is positioned from the sheet's live top offset. Sheet callbacks alone
	// don't cover every frame that offset can change (first layout, settle, the row's own height
	// arriving late), which left the row sitting low or entirely off-screen — so it re-syncs before
	// each draw and the work is skipped when nothing moved.
	private var offsetSyncListener: ViewTreeObserver.OnPreDrawListener? = null
	private var syncedSheetTop = Int.MIN_VALUE
	private var syncedBarHeight = -1

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetFilterMihonBinding {
		return SheetFilterMihonBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetFilterMihonBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		if (dialog == null) {
			binding.adjustForEmbeddedLayout()
		}
		val filter = FilterCoordinator.require(this)
		binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		// Required half of the nested-scroll interop: makes this the sheet's scrolling child so the
		// list's own scroll gets first refusal on a drag (see MihonFilterContent).
		ViewCompat.setNestedScrollingEnabled(binding.composeView, true)
		binding.composeView.setContent {
			DropSauceTheme {
				val items by viewModel.items.collectAsState()
				val isLoading by viewModel.isLoading.collectAsState()
				val isEmpty by viewModel.isEmptyState.collectAsState()
				val density = LocalDensity.current
				MihonFilterContent(
					items = items,
					isLoading = isLoading,
					isEmpty = isEmpty,
					listener = viewModel,
					contentPadding = with(density) {
						PaddingValues(
							start = listPaddingLeft.intValue.toDp(),
							end = listPaddingRight.intValue.toDp(),
							bottom = listPaddingBottom.intValue.toDp(),
						)
					},
					onContentHeight = ::onContentHeightChanged,
				)
			}
		}
		binding.buttonSave.setOnClickListener { showSaveFilterDialog(filter) }
		binding.buttonReset.setOnClickListener { viewModel.reset() }
		filter.canSaveFilter.observe(viewLifecycleOwner) {
			binding.buttonSave.isEnabled = it
			binding.buttonReset.isEnabled = it
		}
		addSheetCallback(this, viewLifecycleOwner)
		binding.layoutBottom.doOnLayout {
			dialog?.findViewById<View>(materialR.id.design_bottom_sheet)?.let { sheet ->
				updateLayoutForOffset(sheet)
			}
		}
	}

	override fun onStart() {
		super.onStart()
		setHalfExpanded()
		attachOffsetSync()
		viewBinding?.root?.doOnLayout { adjustHeightToContent() }
	}

	override fun onStop() {
		detachOffsetSync()
		super.onStop()
	}

	private fun attachOffsetSync() {
		if (offsetSyncListener != null) {
			return
		}
		val sheet = dialog?.findViewById<View>(materialR.id.design_bottom_sheet) ?: return
		val listener = ViewTreeObserver.OnPreDrawListener {
			updateLayoutForOffset(sheet)
			true
		}
		offsetSyncListener = listener
		sheet.viewTreeObserver.addOnPreDrawListener(listener)
	}

	private fun detachOffsetSync() {
		val listener = offsetSyncListener ?: return
		offsetSyncListener = null
		dialog?.findViewById<View>(materialR.id.design_bottom_sheet)
			?.viewTreeObserver
			?.removeOnPreDrawListener(listener)
	}

	/** Re-fits the sheet whenever the list reports a different content height. */
	private fun onContentHeightChanged(height: Int?) {
		if (listContentHeight.value == height) {
			return
		}
		listContentHeight.value = height
		adjustHeightToContent()
	}

	/**
	 * Shrinks the half-expanded resting height when the filter list is shorter than the default
	 * half-page height, so short filter sets don't leave a large empty area. The sheet stays
	 * draggable to full screen either way.
	 */
	private fun adjustHeightToContent() {
		val sheetDialog = dialog as? BottomSheetDialog ?: return
		val sheet = sheetDialog.findViewById<View>(materialR.id.design_bottom_sheet) ?: return
		val parentHeight = (sheet.parent as? View)?.height ?: return
		if (parentHeight <= 0) {
			return
		}
		val behavior = sheetDialog.behavior
		val desired = wrappedContentHeight()
		behavior.halfExpandedRatio = if (desired == null) {
			HALF_EXPANDED_RATIO
		} else {
			(desired.toFloat() / parentHeight).coerceIn(MIN_HEIGHT_RATIO, HALF_EXPANDED_RATIO)
		}
		if (behavior.state == BottomSheetBehavior.STATE_HALF_EXPANDED) {
			sheet.requestLayout()
		}
	}

	/**
	 * Height the sheet needs to show all filter items without scrolling,
	 * or null if the content doesn't fit (or isn't measurable yet).
	 */
	private fun wrappedContentHeight(): Int? {
		val binding = viewBinding ?: return null
		if (viewModel.isLoading.value || viewModel.isEmptyState.value) {
			return null
		}
		val content = listContentHeight.value ?: return null
		val basePadding = resources.getDimensionPixelOffset(R.dimen.margin_small)
		// layoutBottom already carries the navigation-bar inset in its own padding
		return binding.headerBar.height + content + basePadding + binding.layoutBottom.height
	}

	override fun onStateChanged(sheet: View, newState: Int) {
		updateLayoutForOffset(sheet)
		if (newState == STATE_DRAGGING || newState == STATE_SETTLING) {
			return
		}
		// A ratio change applied mid-settle doesn't retarget the running animation, so re-check
		// once the sheet comes to rest.
		adjustHeightToContent()
		// Snap the drag handle to its resting state for programmatic moves; manual drags drive it via onSlide.
		viewBinding?.headerBar?.setDragHandleCollapseProgress(if (newState == STATE_EXPANDED) 1f else 0f)
	}

	override fun onSlide(sheet: View, slideOffset: Float) {
		updateLayoutForOffset(sheet)
		// Melt the drag handle away over the top stretch of the drag so reaching full screen is one
		// seamless motion rather than the handle snapping out once expanded.
		val binding = viewBinding ?: return
		val progress = (slideOffset - DRAG_HANDLE_COLLAPSE_START) / (1f - DRAG_HANDLE_COLLAPSE_START)
		binding.headerBar.setDragHandleCollapseProgress(progress)
	}

	private fun updateLayoutForOffset(sheet: View) {
		val binding = viewBinding ?: return
		val top = sheet.top
		val barHeight = binding.layoutBottom.height
		// Called before every draw, so bail out unless something that feeds the layout actually moved.
		if (top == syncedSheetTop && barHeight == syncedBarHeight) {
			return
		}
		syncedSheetTop = top
		syncedBarHeight = barHeight
		binding.layoutBottom.translationY = -top.toFloat()

		val surfaceColor = getSheetSurfaceColor(sheet)
		binding.layoutBottom.setBackgroundColor(surfaceColor)

		// The sheet is match_parent tall, so at rest its bottom (with the button row) hangs below
		// the screen by `top` minus the overlaid button row — padding of basePadding + top is
		// exactly what lets the last item scroll clear of the pinned buttons, with no dead
		// scroll range left over.
		val basePadding = resources.getDimensionPixelOffset(R.dimen.margin_small)
		listPaddingBottom.intValue = basePadding + top
	}

	private fun getSheetSurfaceColor(sheet: View): Int {
		val color = when (val background = sheet.background) {
			is MaterialShapeDrawable -> background.fillColor?.defaultColor
			is ColorDrawable -> background.color
			else -> null
		}
		return color ?: requireContext().getThemeColor(android.R.attr.colorBackground)
	}

	private fun SheetFilterMihonBinding.adjustForEmbeddedLayout() {
		root.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		listPaddingLeft.intValue = barsInsets.left
		listPaddingRight.intValue = barsInsets.right
		// The action buttons now sit at the bottom, so the navigation-bar inset must keep them clear.
		// Preserve the layout's own vertical breathing room on top of the system inset.
		val basePadding = resources.getDimensionPixelOffset(R.dimen.margin_small)
		viewBinding?.layoutBottom?.updatePadding(bottom = basePadding + barsInsets.bottom)
		dialog?.findViewById<View>(materialR.id.design_bottom_sheet)?.let { sheet ->
			updateLayoutForOffset(sheet)
		} ?: run {
			// Embedded layout fallback
			viewBinding?.run {
				val surfaceColor = requireContext().getThemeColor(android.R.attr.colorBackground)
				layoutBottom.setBackgroundColor(surfaceColor)
				listPaddingBottom.intValue = basePadding + barsInsets.bottom + layoutBottom.height
			}
		}
		return insets.consume(v, typeMask, bottom = true)
	}

	private companion object {
		// Slide offset (0 = half, 1 = full screen) at which the drag handle starts collapsing. Kept above
		// the half-expanded resting offset so the handle stays full at the centre position.
		const val DRAG_HANDLE_COLLAPSE_START = 0.65f

		// Lower bound for the content-fitted sheet height so a couple of filters don't produce a sliver
		const val MIN_HEIGHT_RATIO = 0.2f
	}
}
