package org.koitharu.kotatsu.list.ui.adapter

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.toChipModel
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.getThemeResId
import org.koitharu.kotatsu.core.util.ext.resolveDp
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.ui.model.ExtensionFilter
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import kotlin.math.ceil
import com.google.android.material.R as materialR

internal object ExtensionFilterPopup {

	private const val VISIBLE_CHIP_ROWS = 6
	private const val CHIP_ROW_HEIGHT_DP = 40

	/** Wider than this the chips wrap instead, so a long list stays a compact block. */
	private const val MAX_WIDTH_DP = 360

	fun show(
		anchor: View,
		filter: ExtensionFilter,
		listener: QuickFilterClickListener,
	) {
		val context = anchor.context
		val selectedSourceNames = filter.selectedOptions.mapTo(HashSet()) { it.mangaSource.name }
		val resetButton = createResetButton(context)
		val chips = LayoutInflater.from(context).inflate(R.layout.view_extension_filter_chips, null) as ChipsView
		val bindChips = {
			chips.setChips(
				filter.options.map { option ->
					option.toChipModel(isChecked = option.mangaSource.name in selectedSourceNames)
						.copy(title = option.getMenuTitle(context), titleResId = 0)
				},
			)
		}
		bindChips()
		chips.onChipClickListener = ChipsView.OnChipClickListener { _, data ->
			val option = data as? ListFilterOption.Source ?: return@OnChipClickListener
			val name = option.mangaSource.name
			val checked = name !in selectedSourceNames
			if (checked) {
				selectedSourceNames += name
			} else {
				selectedSourceNames -= name
			}
			bindChips()
			listener.onFilterOptionChanged(option, checked)
			updateResetButton(resetButton, selectedSourceNames.isNotEmpty())
		}
		// Only the chips scroll; the header and Reset stay put. Past VISIBLE_CHIP_ROWS the area is
		// capped with half of the next row peeking out, so it reads as scrollable.
		val chipsScroll = MaxHeightScrollView(context).apply {
			maxHeight = context.resources.resolveDp(CHIP_ROW_HEIGHT_DP * VISIBLE_CHIP_ROWS + CHIP_ROW_HEIGHT_DP / 2)
			isVerticalScrollBarEnabled = true
			addView(chips, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		}
		val content = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			addView(createHeader(context), LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
			addView(chipsScroll, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
			addView(
				resetButton,
				LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
					topMargin = context.resources.resolveDp(8)
					bottomMargin = context.resources.resolveDp(12)
					marginStart = context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_padding_horizontal)
					marginEnd = context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_padding_horizontal)
				},
			)
		}
		resetButton.setOnClickListener {
			selectedSourceNames.clear()
			bindChips()
			updateResetButton(resetButton, isEnabled = false)
			listener.onFilterOptionsCleared(filter.options)
		}
		updateResetButton(resetButton, selectedSourceNames.isNotEmpty())

		PopupWindow(content, resolvePopupWidth(context, chips), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
			setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.m3_menu_background))
			isOutsideTouchable = true
			elevation = context.resources.resolveDp(8).toFloat()
			showAsDropDown(anchor, 0, context.resources.resolveDp(4), Gravity.NO_GRAVITY)
		}
	}

	private fun createHeader(context: Context): View {
		return TextView(context).apply {
			setText(R.string.extension_filters)
			setTextAppearanceAttr(materialR.attr.textAppearanceTitleMedium)
			setTextColor(context.getThemeColor(android.R.attr.textColorPrimary, Color.BLACK))
			gravity = Gravity.CENTER_VERTICAL
			minimumHeight = context.resources.resolveDp(48)
			setPadding(
				context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_padding_horizontal),
				0,
				context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_padding_horizontal),
				0,
			)
		}
	}

	private fun createResetButton(context: Context): MaterialButton {
		return MaterialButton(context, null, materialR.attr.materialButtonTonalStyle).apply {
			setText(R.string.reset)
			gravity = Gravity.CENTER
			minimumHeight = context.resources.resolveDp(48)
			minimumWidth = context.resources.getDimensionPixelSize(R.dimen.menu_popup_min_width)
		}
	}

	/**
	 * As wide as the widest line the chips actually wrap into (measured, not estimated), within the
	 * menu minimum and [MAX_WIDTH_DP]: chips that wrap leave no empty space on their right.
	 */
	private fun resolvePopupWidth(context: Context, chips: ChipsView): Int {
		val horizontalPadding = context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_padding_horizontal)
		val maxWidth = minOf(
			context.resources.displayMetrics.widthPixels - context.resources.resolveDp(32),
			context.resources.resolveDp(MAX_WIDTH_DP),
		)
		chips.measure(
			View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
			View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
		)
		val chipsWidth = chips.measuredWidth
		val headerWidth = horizontalPadding +
			context.measureTextWidth(context.getString(R.string.extension_filters), materialR.attr.textAppearanceTitleMedium) +
			horizontalPadding
		val minWidth = maxOf(context.resources.getDimensionPixelSize(R.dimen.menu_popup_min_width), headerWidth)
		return chipsWidth.coerceIn(minWidth, maxOf(minWidth, maxWidth))
	}

	private fun updateResetButton(resetButton: MaterialButton, isEnabled: Boolean) {
		resetButton.isEnabled = isEnabled
	}

	private fun ListFilterOption.Source.getMenuTitle(context: Context): String {
		return when (val source = mangaSource.unwrap()) {
			is MihonMangaSource -> if (source.hasLanguageSuffix) {
				"${source.displayName} (${source.languageDisplayName})"
			} else {
				source.displayName
			}

			else -> mangaSource.getTitle(context)
		}
	}

	private fun Context.measureTextWidth(text: CharSequence, @AttrRes textAppearanceAttr: Int): Int {
		val textView = TextView(this)
		textView.setTextAppearanceAttr(textAppearanceAttr)
		return ceil(textView.paint.measureText(text, 0, text.length)).toInt()
	}

	private fun TextView.setTextAppearanceAttr(@AttrRes attr: Int) {
		val style = context.getThemeResId(attr, 0)
		if (style != 0) {
			setTextAppearance(style)
		}
	}

	private class MaxHeightScrollView(context: Context) : ScrollView(context) {

		var maxHeight = 0

		override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
			val parentHeight = MeasureSpec.getSize(heightMeasureSpec)
			val resolvedHeight = if (maxHeight > 0) {
				// Never past what the parent offers (e.g. a short landscape screen).
				val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
					maxHeight
				} else {
					minOf(maxHeight, parentHeight)
				}
				MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST)
			} else {
				heightMeasureSpec
			}
			super.onMeasure(widthMeasureSpec, resolvedHeight)
		}
	}
}
