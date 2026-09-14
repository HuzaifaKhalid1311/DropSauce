package org.koitharu.kotatsu.core.ui.list.decor

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.collection.LongSet
import androidx.collection.MutableLongSet
import androidx.core.view.children
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.NO_ID
import org.koitharu.kotatsu.R

abstract class AbstractSelectionItemDecoration : RecyclerView.ItemDecoration() {

	private val bounds = Rect()
	private val boundsF = RectF()
	private var halfStrokeWidth = -1f
	protected val selection = MutableLongSet()

	protected var hasBackground: Boolean = true
	protected var hasForeground: Boolean = false
	protected var isIncludeDecorAndMargins: Boolean = true

	/** Off for decorations that merge neighbouring rows into one box and must not leave a seam. */
	protected var isInsetStrokeVertically: Boolean = true

	val checkedItemsCount: Int
		get() = selection.size

	val checkedItemsIds: LongSet
		get() = selection

	fun toggleItemChecked(id: Long) {
		if (!selection.remove(id)) {
			selection.add(id)
		}
	}

	fun setItemIsChecked(id: Long, isChecked: Boolean) {
		if (isChecked) {
			selection.add(id)
		} else {
			selection.remove(id)
		}
	}

	fun checkAll(ids: Collection<Long>) {
		for (id in ids) {
			selection.add(id)
		}
	}

	fun clearSelection() {
		selection.clear()
	}

	override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
		if (hasBackground) {
			doDraw(canvas, parent, state, false)
		} else {
			super.onDraw(canvas, parent, state)
		}
	}

	override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
		if (hasForeground) {
			doDraw(canvas, parent, state, true)
		} else {
			super.onDrawOver(canvas, parent, state)
		}
	}

	private fun doDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State, isOver: Boolean) {
		val checkpoint = canvas.save()
		if (parent.clipToPadding) {
			canvas.clipRect(
				parent.paddingLeft, parent.paddingTop, parent.width - parent.paddingRight,
				parent.height - parent.paddingBottom,
			)
		}

		for (child in parent.children) {
			val itemId = getItemId(parent, child)
			if (itemId != NO_ID && itemId in selection) {
				if (isIncludeDecorAndMargins) {
					parent.getDecoratedBoundsWithMargins(child, bounds)
				} else {
					bounds.set(child.left, child.top, child.right, child.bottom)
				}
				boundsF.set(bounds)
				boundsF.offset(child.translationX, child.translationY)
				boundsF.applyScaleOf(child)
				// the stroke is centered on the bounds, so the outer half of it would be clipped
				// away for items sitting against an edge of the list
				val halfStroke = halfStrokeWidth(parent)
				boundsF.inset(halfStroke, if (isInsetStrokeVertically) halfStroke else 0f)
				if (isOver) {
					onDrawForeground(canvas, parent, child, boundsF, state)
				} else {
					onDrawBackground(canvas, parent, child, boundsF, state)
				}
			}
		}
		canvas.restoreToCount(checkpoint)
	}

	private fun halfStrokeWidth(parent: RecyclerView): Float {
		if (halfStrokeWidth < 0f) {
			halfStrokeWidth = parent.resources.getDimension(R.dimen.selection_stroke_width) / 2f
		}
		return halfStrokeWidth
	}

	/** Follow the selection squish animation instead of hovering at the item's unscaled size. */
	private fun RectF.applyScaleOf(child: View) {
		val scaleX = child.scaleX
		val scaleY = child.scaleY
		if (scaleX == 1f && scaleY == 1f) {
			return
		}
		val pivotX = child.left + child.translationX + child.pivotX
		val pivotY = child.top + child.translationY + child.pivotY
		set(
			pivotX + (left - pivotX) * scaleX,
			pivotY + (top - pivotY) * scaleY,
			pivotX + (right - pivotX) * scaleX,
			pivotY + (bottom - pivotY) * scaleY,
		)
	}

	abstract fun getItemId(parent: RecyclerView, child: View): Long

	protected open fun onDrawBackground(
		canvas: Canvas,
		parent: RecyclerView,
		child: View,
		bounds: RectF,
		state: RecyclerView.State,
	) = Unit

	protected open fun onDrawForeground(
		canvas: Canvas,
		parent: RecyclerView,
		child: View,
		bounds: RectF,
		state: RecyclerView.State,
	) = Unit
}
