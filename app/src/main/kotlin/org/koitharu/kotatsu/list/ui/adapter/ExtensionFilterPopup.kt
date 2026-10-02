package org.koitharu.kotatsu.list.ui.adapter

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isExternalSource
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.ui.image.FaviconView
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.getThemeResId
import org.koitharu.kotatsu.core.util.ext.resolveDp
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.ui.model.ExtensionFilter
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import kotlin.math.ceil
import com.google.android.material.R as materialR
import androidx.appcompat.R as appcompatR

internal object ExtensionFilterPopup {

	private const val VISIBLE_ROWS = 6

	fun show(
		anchor: View,
		filter: ExtensionFilter,
		listener: QuickFilterClickListener,
	) {
		val context = anchor.context
		val popupWidth = resolvePopupWidth(context, filter)
		val rows = ArrayList<Row>(filter.options.size)
		val selectedSourceNames = filter.selectedOptions.mapTo(HashSet()) { it.mangaSource.name }
		val resetButton = createResetButton(context)
		val list = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			for (option in filter.options) {
				val row = createRow(
					context = context,
					option = option,
					selectedSourceNames = selectedSourceNames,
					listener = listener,
					onSelectionChanged = {
						updateResetButton(resetButton, selectedSourceNames.isNotEmpty())
					},
				)
				rows += row.binding
				addView(row.view, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
			}
		}
		// Only the extension list scrolls; the header and Reset stay put. Past VISIBLE_ROWS the list
		// is capped with half of the next row peeking out, so it reads as scrollable.
		val rowHeight = context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_min_height)
		val listScroll = MaxHeightScrollView(context).apply {
			if (filter.options.size > VISIBLE_ROWS) {
				maxHeight = rowHeight * VISIBLE_ROWS + rowHeight / 2
			}
			isVerticalScrollBarEnabled = true
			addView(list, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		}
		val content = LinearLayout(context).apply {
			orientation = LinearLayout.VERTICAL
			addView(createHeader(context), LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
			addView(listScroll, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
			rows.forEach { it.checkBox.isChecked = false }
			updateResetButton(resetButton, isEnabled = false)
			listener.onFilterOptionsCleared(filter.options)
		}
		updateResetButton(resetButton, selectedSourceNames.isNotEmpty())

		PopupWindow(content, popupWidth, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
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

	private fun createRow(
		context: Context,
		option: ListFilterOption.Source,
		selectedSourceNames: MutableSet<String>,
		listener: QuickFilterClickListener,
		onSelectionChanged: () -> Unit,
	): RowView {
		val row = LinearLayout(context).apply {
			orientation = LinearLayout.HORIZONTAL
			gravity = Gravity.CENTER_VERTICAL
			minimumHeight = context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_min_height)
			minimumWidth = context.resources.getDimensionPixelSize(R.dimen.menu_popup_min_width)
			setPadding(
				context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_padding_horizontal),
				0,
				context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_padding_horizontal),
				0,
			)
			background = ContextCompat.getDrawable(context, R.drawable.extension_filter_row_selector)
		}
		val icon = FaviconView(context).apply {
			applyExternalSourceStyle(option.mangaSource.isExternalSource())
			setImageAsync(option.mangaSource)
		}
		val title = TextView(context).apply {
			text = option.getMenuTitle(context)
			setTextAppearanceAttr(appcompatR.attr.textAppearanceLargePopupMenu)
			setTextColor(context.getThemeColor(android.R.attr.textColorPrimary, Color.BLACK))
			isSingleLine = true
			ellipsize = TextUtils.TruncateAt.END
			maxWidth = context.resources.displayMetrics.widthPixels -
				context.resources.resolveDp(144)
		}
		val checkBox = MaterialCheckBox(context).apply {
			isClickable = false
			isFocusable = false
			isChecked = option.mangaSource.name in selectedSourceNames
			minimumWidth = 0
			minimumHeight = 0
			gravity = Gravity.CENTER
			setPadding(0, 0, 0, 0)
			background = null
		}
		row.addView(
			icon,
			LinearLayout.LayoutParams(
				context.resources.getDimensionPixelSize(R.dimen.menu_popup_icon_size),
				context.resources.getDimensionPixelSize(R.dimen.menu_popup_icon_size),
			).apply {
				marginEnd = context.resources.getDimensionPixelSize(R.dimen.menu_icon_text_spacing_extra)
			},
		)
		row.addView(
			title,
			LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
				marginEnd = context.resources.resolveDp(8)
			},
		)
		row.addView(
			checkBox,
			LinearLayout.LayoutParams(
				context.resources.getDimensionPixelSize(R.dimen.menu_popup_icon_size),
				context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_min_height),
			),
		)
		val rowBinding = Row(checkBox)
		row.setOnClickListener {
			val checked = !checkBox.isChecked
			checkBox.isChecked = checked
			if (checked) {
				selectedSourceNames += option.mangaSource.name
			} else {
				selectedSourceNames -= option.mangaSource.name
			}
			listener.onFilterOptionChanged(option, checked)
			onSelectionChanged()
		}
		return RowView(row, rowBinding)
	}

	private fun resolvePopupWidth(context: Context, filter: ExtensionFilter): Int {
		val horizontalPadding = context.resources.getDimensionPixelSize(R.dimen.menu_popup_item_padding_horizontal)
		val iconSize = context.resources.getDimensionPixelSize(R.dimen.menu_popup_icon_size)
		val iconTextGap = context.resources.getDimensionPixelSize(R.dimen.menu_icon_text_spacing_extra)
		val textCheckGap = context.resources.resolveDp(8)
		val menuMinWidth = context.resources.getDimensionPixelSize(R.dimen.menu_popup_min_width)
		val maxAvailableWidth = context.resources.displayMetrics.widthPixels - context.resources.resolveDp(32)
		val widestSourceWidth = filter.options.maxOfOrNull {
			context.measureTextWidth(it.getMenuTitle(context), appcompatR.attr.textAppearanceLargePopupMenu)
		} ?: 0
		val sourceRowWidth = horizontalPadding + iconSize + iconTextGap + widestSourceWidth +
			textCheckGap + iconSize + horizontalPadding
		val headerWidth = horizontalPadding +
			context.measureTextWidth(context.getString(R.string.extension_filters), materialR.attr.textAppearanceTitleMedium) +
			horizontalPadding
		return maxOf(menuMinWidth, sourceRowWidth, headerWidth).coerceAtMost(maxAvailableWidth)
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

	private data class Row(
		val checkBox: MaterialCheckBox,
	)

	private data class RowView(
		val view: View,
		val binding: Row,
	)
}
