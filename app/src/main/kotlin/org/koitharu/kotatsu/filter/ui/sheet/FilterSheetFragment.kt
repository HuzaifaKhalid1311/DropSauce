package org.koitharu.kotatsu.filter.ui.sheet

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import com.google.android.material.shape.MaterialShapeDrawable
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.titleResId
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.model.titleRes
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_DRAGGING
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_EXPANDED
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior.Companion.STATE_SETTLING
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetCallback
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.ui.sheet.SheetChip
import org.koitharu.kotatsu.core.ui.sheet.SheetChips
import org.koitharu.kotatsu.core.ui.sheet.SheetContentPadding
import org.koitharu.kotatsu.core.ui.sheet.SheetSection
import org.koitharu.kotatsu.core.ui.sheet.SheetSelectorField
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.getDisplayName
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.SheetFilterBinding
import org.koitharu.kotatsu.filter.ui.FilterCoordinator
import org.koitharu.kotatsu.filter.ui.model.FilterProperty
import org.koitharu.kotatsu.filter.ui.showSaveFilterDialog
import org.koitharu.kotatsu.parsers.model.YEAR_UNKNOWN
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import kotlin.math.roundToInt
import com.google.android.material.R as materialR

class FilterSheetFragment : BaseAdaptiveSheet<SheetFilterBinding>(), AdaptiveSheetCallback {

	private var systemBarsBottom = 0

	// The pinned button row is positioned from the sheet's live top offset. Sheet callbacks alone
	// don't cover every frame that offset can change (first layout, settle, the row's own height
	// arriving late), which left the row sitting low or entirely off-screen — so it re-syncs before
	// each draw and the work is skipped when nothing moved.
	private var offsetSyncListener: ViewTreeObserver.OnPreDrawListener? = null
	private var syncedSheetTop = Int.MIN_VALUE
	private var syncedBarHeight = -1
	private var syncedBarsBottom = -1

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetFilterBinding {
		return SheetFilterBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetFilterBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		if (dialog == null) {
			binding.adjustForEmbeddedLayout()
		}
		val filter = FilterCoordinator.require(this)
		binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		binding.composeView.setContent {
			DropSauceTheme {
				Content(filter)
			}
		}
		filter.canSaveFilter.observe(viewLifecycleOwner) {
			binding.buttonSave.isEnabled = it
			binding.buttonReset.isEnabled = it
		}
		binding.buttonSave.setOnClickListener { showSaveFilterDialog(filter) }
		binding.buttonReset.setOnClickListener { filter.clearSavedFilter() }
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

	override fun onStateChanged(sheet: View, newState: Int) {
		updateLayoutForOffset(sheet)
		if (newState == STATE_DRAGGING || newState == STATE_SETTLING) {
			return
		}
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
		if (top == syncedSheetTop && barHeight == syncedBarHeight && systemBarsBottom == syncedBarsBottom) {
			return
		}
		syncedSheetTop = top
		syncedBarHeight = barHeight
		syncedBarsBottom = systemBarsBottom
		binding.layoutBottom.translationY = -top.toFloat()

		val surfaceColor = getSheetSurfaceColor(sheet)
		binding.layoutBottom.setBackgroundColor(surfaceColor)

		val basePadding = resources.getDimensionPixelOffset(R.dimen.margin_small)
		binding.scrollView.updatePadding(
			bottom = basePadding + systemBarsBottom + barHeight + top,
		)
	}

	private fun getSheetSurfaceColor(sheet: View): Int {
		val color = when (val background = sheet.background) {
			is MaterialShapeDrawable -> background.fillColor?.defaultColor
			is ColorDrawable -> background.color
			else -> null
		}
		return color ?: requireContext().getThemeColor(android.R.attr.colorBackground)
	}

	private fun SheetFilterBinding.adjustForEmbeddedLayout() {
		layoutBody.updatePadding(top = layoutBody.paddingBottom)
		scrollView.scrollIndicators = 0
		this.root.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		systemBarsBottom = barsInsets.bottom
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
				scrollView.updatePadding(bottom = basePadding + barsInsets.bottom + layoutBottom.height)
			}
		}
		return insets.consume(v, typeMask, bottom = true)
	}

	@Composable
	private fun Content(filter: FilterCoordinator) {
		val context = LocalContext.current
		val sortOrder by filter.sortOrder.collectAsState()
		val locale by filter.locale.collectAsState()
		val originalLocale by filter.originalLocale.collectAsState()
		val tags by filter.tags.collectAsState()
		val tagsExcluded by filter.tagsExcluded.collectAsState()
		val authors by filter.authors.collectAsState()
		val states by filter.states.collectAsState()
		val contentTypes by filter.contentTypes.collectAsState()
		val contentRating by filter.contentRating.collectAsState()
		val demographics by filter.demographics.collectAsState()
		val year by filter.year.collectAsState()
		val yearRange by filter.yearRange.collectAsState()
		val isMultipleTagsSupported = remember { filter.capabilities.isMultipleTagsSupported }

		Column(modifier = Modifier.padding(bottom = 8.dp)) {
			// Single-choice properties keep the "pick one" affordance of the spinners they replace.
			if (!sortOrder.isEmpty()) {
				SheetSection(title = stringResource(R.string.sort_order)) {
					SingleChoiceField(
						property = sortOrder,
						label = { stringResource(it.titleRes) },
						onSelect = filter::setSortOrder,
					)
				}
			}
			if (!locale.isEmpty()) {
				SheetSection(title = stringResource(R.string.language)) {
					SingleChoiceField(
						property = locale,
						label = { it.getDisplayName(context) },
						onSelect = filter::setLocale,
					)
				}
			}
			if (!originalLocale.isEmpty()) {
				SheetSection(title = stringResource(R.string.original_language)) {
					SingleChoiceField(
						property = originalLocale,
						label = { it.getDisplayName(context) },
						onSelect = filter::setOriginalLocale,
					)
				}
			}

			// Genres can fail to load on their own, so this section stays visible to carry the error.
			if (!tags.isEmptyAndSuccess()) {
				SheetSection(
					title = stringResource(if (isMultipleTagsSupported) R.string.genres else R.string.genre),
					moreLabel = stringResource(R.string.show_all),
					onMore = { router.showTagsCatalogSheet(excludeMode = false) },
				) {
					val error = tags.error
					if (error != null) {
						Text(
							text = error.getDisplayMessage(resources),
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.error,
							modifier = Modifier.padding(horizontal = SheetContentPadding),
						)
					}
					ChipsField(
						property = tags,
						label = { it.title },
						onToggle = { tag, isSelected -> filter.toggleTag(tag, isSelected) },
					)
				}
			}
			if (!tagsExcluded.isEmpty()) {
				SheetSection(
					title = stringResource(R.string.genres_exclude),
					moreLabel = stringResource(R.string.show_all),
					onMore = { router.showTagsCatalogSheet(excludeMode = true) },
				) {
					ChipsField(
						property = tagsExcluded,
						label = { it.title },
						onToggle = { tag, isSelected -> filter.toggleTagExclude(tag, isSelected) },
					)
				}
			}
			if (!authors.isEmpty()) {
				SheetSection(title = stringResource(R.string.author)) {
					ChipsField(
						property = authors,
						label = { it },
						// One author at a time: tapping the active chip clears the filter.
						onToggle = { author, isSelected -> filter.setAuthor(author.takeIf { isSelected }) },
					)
				}
			}
			if (!contentTypes.isEmpty()) {
				SheetSection(title = stringResource(R.string.type)) {
					ChipsField(
						property = contentTypes,
						label = { stringResource(it.titleResId) },
						onToggle = { type, isSelected -> filter.toggleContentType(type, isSelected) },
					)
				}
			}
			if (!states.isEmpty()) {
				SheetSection(title = stringResource(R.string.state)) {
					ChipsField(
						property = states,
						label = { stringResource(it.titleResId) },
						onToggle = { state, isSelected -> filter.toggleState(state, isSelected) },
					)
				}
			}
			if (!contentRating.isEmpty()) {
				SheetSection(title = stringResource(R.string.content_rating)) {
					ChipsField(
						property = contentRating,
						label = { stringResource(it.titleResId) },
						onToggle = { rating, isSelected -> filter.toggleContentRating(rating, isSelected) },
					)
				}
			}
			if (!demographics.isEmpty()) {
				SheetSection(title = stringResource(R.string.demographics)) {
					ChipsField(
						property = demographics,
						label = { stringResource(it.titleResId) },
						onToggle = { demographic, isSelected -> filter.toggleDemographic(demographic, isSelected) },
					)
				}
			}

			if (!year.isEmpty()) {
				val from = year.availableItems.first().toFloat()
				val to = year.availableItems.last().toFloat()
				val selected = year.selectedItems.singleOrNull() ?: YEAR_UNKNOWN
				SheetSection(
					title = stringResource(R.string.year),
					value = if (selected == YEAR_UNKNOWN) stringResource(R.string.any) else selected.toString(),
				) {
					Slider(
						value = if (selected == YEAR_UNKNOWN) from else selected.toFloat().coerceIn(from, to),
						valueRange = from..to,
						onValueChange = { value ->
							// The low end of the track means "any year", as on the slider it replaces.
							filter.setYear(if (value <= from) YEAR_UNKNOWN else value.roundToInt())
						},
						modifier = Modifier.padding(horizontal = SheetContentPadding),
					)
				}
			}
			if (!yearRange.isEmpty()) {
				val from = yearRange.availableItems.first().toFloat()
				val to = yearRange.availableItems.last().toFloat()
				val selectedFrom = (yearRange.selectedItems.firstOrNull()?.toFloat() ?: from).coerceIn(from, to)
				val selectedTo = (yearRange.selectedItems.lastOrNull()?.toFloat() ?: to).coerceIn(selectedFrom, to)
				SheetSection(
					title = stringResource(R.string.years),
					value = stringResource(
						R.string.memory_usage_pattern,
						selectedFrom.roundToInt().toString(),
						selectedTo.roundToInt().toString(),
					),
				) {
					RangeSlider(
						value = selectedFrom..selectedTo,
						valueRange = from..to,
						onValueChange = { range ->
							// Either handle parked against the track edge means "unbounded" on that side.
							filter.setYearRange(
								valueFrom = if (range.start <= from) YEAR_UNKNOWN else range.start.roundToInt(),
								valueTo = if (range.endInclusive >= to) YEAR_UNKNOWN else range.endInclusive.roundToInt(),
							)
						},
						modifier = Modifier.padding(horizontal = SheetContentPadding, vertical = 8.dp),
					)
				}
			}
		}
	}

	/** Dropdown over a property's available items, showing the selected one. */
	@Composable
	private fun <T> SingleChoiceField(
		property: FilterProperty<T>,
		label: @Composable (T) -> String,
		onSelect: (T) -> Unit,
	) {
		val selected = property.selectedItems.singleOrNull()
		SheetSelectorField(
			current = selected?.let { label(it) }.orEmpty(),
			items = property.availableItems.map { label(it) },
			onSelect = { index -> property.availableItems.getOrNull(index)?.let(onSelect) },
			modifier = Modifier.padding(horizontal = SheetContentPadding),
		)
	}

	/** Wrapping chip row over a property; [onToggle] receives the item and its new selected state. */
	@Composable
	private fun <T> ChipsField(
		property: FilterProperty<T>,
		label: @Composable (T) -> String,
		onToggle: (T, Boolean) -> Unit,
	) {
		val items = property.availableItems
		SheetChips(
			chips = items.map { SheetChip(title = label(it), isChecked = it in property.selectedItems) },
			onClick = { index ->
				val item = items.getOrNull(index) ?: return@SheetChips
				onToggle(item, item !in property.selectedItems)
			},
			modifier = Modifier.padding(horizontal = SheetContentPadding),
		)
	}

	private companion object {
		// Slide offset (0 = half, 1 = full screen) at which the drag handle starts collapsing. Kept above
		// the half-expanded resting offset so the handle stays full at the centre position.
		const val DRAG_HANDLE_COLLAPSE_START = 0.65f
	}
}
