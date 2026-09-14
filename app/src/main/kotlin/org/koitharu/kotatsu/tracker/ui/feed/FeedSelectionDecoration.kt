package org.koitharu.kotatsu.tracker.ui.feed

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.NO_ID
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.list.decor.AbstractSelectionItemDecoration
import org.koitharu.kotatsu.core.util.ext.getItem
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

// Selection overlay for feed rows. Consecutively selected rows merge into a single box:
// the shared edges lose their rounding and the fill bridges the margin gap between rows,
// same approach as ChaptersSelectionDecoration.
class FeedSelectionDecoration(context: Context) : AbstractSelectionItemDecoration() {

	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val strokeColor = context.getThemeColor(appcompatR.attr.colorPrimary, Color.RED)
	private val fillColor = ColorUtils.setAlphaComponent(
		ColorUtils.blendARGB(strokeColor, context.getThemeColor(materialR.attr.colorSurface), 0.8f),
		0x74,
	)
	private val radius = context.resources.getDimension(R.dimen.list_selector_corner)

	init {
		hasBackground = false
		hasForeground = true
		isIncludeDecorAndMargins = false
		isInsetStrokeVertically = false

		paint.strokeWidth = context.resources.getDimension(R.dimen.selection_stroke_width)
	}

	override fun getItemId(parent: RecyclerView, child: View): Long {
		val holder = parent.getChildViewHolder(child) ?: return NO_ID
		return holder.getItem(FeedItem::class.java)?.id ?: NO_ID
	}

	override fun onDrawForeground(
		canvas: Canvas,
		parent: RecyclerView,
		child: View,
		bounds: RectF,
		state: RecyclerView.State,
	) {
		val position = parent.getChildAdapterPosition(child)
		@Suppress("UNCHECKED_CAST")
		val items = (parent.adapter as? BaseListAdapter<ListModel>)?.items
		fun isSelectedAt(index: Int): Boolean {
			val item = items?.getOrNull(index) as? FeedItem ?: return false
			return item.id in selection
		}

		val hasSelectedAbove = position > 0 && isSelectedAt(position - 1)
		val hasSelectedBelow = position != RecyclerView.NO_POSITION && isSelectedAt(position + 1)

		if (hasSelectedBelow) {
			// bridge the gap so this box meets the next row's box (the row below never extends up)
			bounds.bottom += (child.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
		}

		val topRadius = if (hasSelectedAbove) 0f else radius
		val bottomRadius = if (hasSelectedBelow) 0f else radius
		val radii = floatArrayOf(
			topRadius, topRadius,
			topRadius, topRadius,
			bottomRadius, bottomRadius,
			bottomRadius, bottomRadius,
		)
		val fillPath = Path()
		fillPath.addRoundRect(bounds, radii, Path.Direction.CW)
		paint.color = fillColor
		paint.style = Paint.Style.FILL
		canvas.drawPath(fillPath, paint)

		val strokePath = Path()
		if (!hasSelectedAbove && !hasSelectedBelow) {
			strokePath.addRoundRect(bounds, radius, radius, Path.Direction.CW)
		} else if (!hasSelectedAbove) {
			// rounded top, open bottom
			strokePath.moveTo(bounds.left, bounds.bottom)
			strokePath.lineTo(bounds.left, bounds.top + radius)
			strokePath.arcTo(
				bounds.left, bounds.top, bounds.left + 2 * radius, bounds.top + 2 * radius,
				180f, 90f, false,
			)
			strokePath.lineTo(bounds.right - radius, bounds.top)
			strokePath.arcTo(
				bounds.right - 2 * radius, bounds.top, bounds.right, bounds.top + 2 * radius,
				270f, 90f, false,
			)
			strokePath.lineTo(bounds.right, bounds.bottom)
		} else if (!hasSelectedBelow) {
			// open top, rounded bottom
			strokePath.moveTo(bounds.left, bounds.top)
			strokePath.lineTo(bounds.left, bounds.bottom - radius)
			strokePath.arcTo(
				bounds.left, bounds.bottom - 2 * radius, bounds.left + 2 * radius, bounds.bottom,
				180f, -90f, false,
			)
			strokePath.lineTo(bounds.right - radius, bounds.bottom)
			strokePath.arcTo(
				bounds.right - 2 * radius, bounds.bottom - 2 * radius, bounds.right, bounds.bottom,
				90f, -90f, false,
			)
			strokePath.lineTo(bounds.right, bounds.top)
		} else {
			// middle of a run: just the two side edges
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
