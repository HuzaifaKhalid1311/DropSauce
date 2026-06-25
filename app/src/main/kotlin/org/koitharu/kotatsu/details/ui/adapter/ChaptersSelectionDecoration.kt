package org.koitharu.kotatsu.details.ui.adapter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.decor.AbstractSelectionItemDecoration
import org.koitharu.kotatsu.core.util.ext.getItem
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

class ChaptersSelectionDecoration(context: Context) : AbstractSelectionItemDecoration() {

	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val checkIcon = ContextCompat.getDrawable(context, materialR.drawable.ic_mtrl_checked_circle)
	private val iconOffset = context.resources.getDimensionPixelOffset(R.dimen.chapter_check_offset)
	private val iconSize = context.resources.getDimensionPixelOffset(R.dimen.chapter_check_size)
	private val strokeColor = context.getThemeColor(appcompatR.attr.colorPrimary, Color.RED)
	private val fillColor = ColorUtils.setAlphaComponent(
		ColorUtils.blendARGB(strokeColor, context.getThemeColor(materialR.attr.colorSurface), 0.8f),
		0x74,
	)
	private val defaultRadius = context.resources.getDimension(R.dimen.list_selector_corner)

	init {
		hasBackground = false
		hasForeground = true
		isIncludeDecorAndMargins = false

		paint.strokeWidth = context.resources.getDimension(R.dimen.selection_stroke_width)
		checkIcon?.setTint(strokeColor)
	}

	override fun getItemId(parent: RecyclerView, child: View): Long {
		val holder = parent.getChildViewHolder(child) ?: return RecyclerView.NO_ID
		val item = holder.getItem(ChapterListItem::class.java) ?: return RecyclerView.NO_ID
		return item.chapter.id
	}

	override fun onDrawForeground(
		canvas: Canvas,
		parent: RecyclerView,
		child: View,
		bounds: RectF,
		state: RecyclerView.State
	) {
		val isCard = child is CardView
		if (isCard) {
			val radius = child.radius
			paint.color = fillColor
			paint.style = Paint.Style.FILL
			canvas.drawRoundRect(bounds, radius, radius, paint)
			paint.color = strokeColor
			paint.style = Paint.Style.STROKE
			canvas.drawRoundRect(bounds, radius, radius, paint)
			checkIcon?.run {
				setBounds(
					(bounds.right - iconSize - iconOffset).toInt(),
					(bounds.top + iconOffset).toInt(),
					(bounds.right - iconOffset).toInt(),
					(bounds.top + iconOffset + iconSize).toInt(),
				)
				draw(canvas)
			}
		} else {
			val position = parent.getChildAdapterPosition(child)
			val adapter = parent.adapter as? ChaptersAdapter
			val items = adapter?.items

			val hasSelectedAbove = if (position > 0 && items != null) {
				val prevItem = items.getOrNull(position - 1)
				prevItem is ChapterListItem && prevItem.chapter.id in selection
			} else false

			val hasSelectedBelow = if (items != null) {
				val nextItem = items.getOrNull(position + 1)
				nextItem is ChapterListItem && nextItem.chapter.id in selection
			} else false

			val horizontalPadding = parent.context.resources.getDimension(R.dimen.grid_spacing_outer_double)
			bounds.left += horizontalPadding
			bounds.right -= horizontalPadding

			val fillPath = Path()
			val topLeft = if (hasSelectedAbove) 0f else defaultRadius
			val topRight = if (hasSelectedAbove) 0f else defaultRadius
			val bottomRight = if (hasSelectedBelow) 0f else defaultRadius
			val bottomLeft = if (hasSelectedBelow) 0f else defaultRadius

			val radii = floatArrayOf(
				topLeft, topLeft,
				topRight, topRight,
				bottomRight, bottomRight,
				bottomLeft, bottomLeft
			)
			fillPath.addRoundRect(bounds, radii, Path.Direction.CW)

			paint.color = fillColor
			paint.style = Paint.Style.FILL
			canvas.drawPath(fillPath, paint)

			val strokePath = Path()
			if (!hasSelectedAbove && !hasSelectedBelow) {
				strokePath.addRoundRect(bounds, defaultRadius, defaultRadius, Path.Direction.CW)
			} else if (!hasSelectedAbove && hasSelectedBelow) {
				strokePath.moveTo(bounds.left, bounds.bottom)
				strokePath.lineTo(bounds.left, bounds.top + defaultRadius)
				strokePath.arcTo(
					bounds.left, bounds.top, bounds.left + 2 * defaultRadius, bounds.top + 2 * defaultRadius,
					180f, 90f, false
				)
				strokePath.lineTo(bounds.right - defaultRadius, bounds.top)
				strokePath.arcTo(
					bounds.right - 2 * defaultRadius, bounds.top, bounds.right, bounds.top + 2 * defaultRadius,
					270f, 90f, false
				)
				strokePath.lineTo(bounds.right, bounds.bottom)
			} else if (hasSelectedAbove && !hasSelectedBelow) {
				strokePath.moveTo(bounds.left, bounds.top)
				strokePath.lineTo(bounds.left, bounds.bottom - defaultRadius)
				strokePath.arcTo(
					bounds.left, bounds.bottom - 2 * defaultRadius, bounds.left + 2 * defaultRadius, bounds.bottom,
					180f, -90f, false
				)
				strokePath.lineTo(bounds.right - defaultRadius, bounds.bottom)
				strokePath.arcTo(
					bounds.right - 2 * defaultRadius, bounds.bottom - 2 * defaultRadius, bounds.right, bounds.bottom,
					90f, -90f, false
				)
				strokePath.lineTo(bounds.right, bounds.top)
			} else {
				strokePath.moveTo(bounds.left, bounds.top)
				strokePath.lineTo(bounds.left, bounds.bottom)
				strokePath.moveTo(bounds.right, bounds.top)
				strokePath.lineTo(bounds.right, bounds.bottom)
			}

			paint.color = strokeColor
			paint.style = Paint.Style.STROKE
			canvas.drawPath(strokePath, paint)
		}
	}
}
