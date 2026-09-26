package org.koitharu.kotatsu.core.ui.list.decor

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.animation.AnimationUtils
import androidx.collection.LongSet
import androidx.collection.MutableLongLongMap
import androidx.collection.MutableLongSet
import androidx.core.view.children
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.NO_ID
import org.koitharu.kotatsu.R

private const val FADE_DURATION = 150L
private const val NO_FADE = Long.MIN_VALUE / 2

abstract class AbstractSelectionItemDecoration : RecyclerView.ItemDecoration() {

	private val bounds = Rect()
	private val boundsF = RectF()
	private var halfStrokeWidth = -1f
	protected val selection = MutableLongSet()

	/** Item id -> start time of its overlay fading in (if selected) or out (if not). */
	private val fades = MutableLongLongMap()
	private val fadeInterpolator = LinearOutSlowInInterpolator()

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
		startFade(id)
	}

	fun setItemIsChecked(id: Long, isChecked: Boolean) {
		val isChanged = if (isChecked) selection.add(id) else selection.remove(id)
		if (isChanged) {
			startFade(id)
		}
	}

	fun checkAll(ids: Collection<Long>) {
		for (id in ids) {
			if (selection.add(id)) {
				startFade(id)
			}
		}
	}

	fun clearSelection() {
		selection.forEach { startFade(it) }
		selection.clear()
	}

	/** Reversing mid-fade continues from the current opacity instead of jumping. */
	private fun startFade(id: Long) {
		val duration = fadeDuration()
		if (duration <= 0L) {
			return
		}
		val now = AnimationUtils.currentAnimationTimeMillis()
		val elapsed = now - fades.getOrDefault(id, NO_FADE)
		fades[id] = if (elapsed < duration) now - (duration - elapsed) else now
	}

	private fun fadeDuration() = (FADE_DURATION * ValueAnimator.getDurationScale()).toLong()

	private fun overlayAlpha(id: Long, now: Long, duration: Long): Float {
		val isSelected = id in selection
		val start = fades.getOrDefault(id, NO_FADE)
		if (now - start >= duration) {
			return if (isSelected) 1f else 0f
		}
		val progress = fadeInterpolator.getInterpolation((now - start).toFloat() / duration)
		return if (isSelected) progress else 1f - progress
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

		val now = AnimationUtils.currentAnimationTimeMillis()
		val duration = fadeDuration()
		for (child in parent.children) {
			val itemId = getItemId(parent, child)
			val alpha = if (itemId == NO_ID) 0f else overlayAlpha(itemId, now, duration)
			if (alpha > 0f) {
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
				val layer = if (alpha < 1f) {
					canvas.saveLayerAlpha(
						boundsF.left - halfStroke, boundsF.top - halfStroke,
						boundsF.right + halfStroke, boundsF.bottom + halfStroke,
						(alpha * 255).toInt(),
					)
				} else {
					-1
				}
				if (isOver) {
					onDrawForeground(canvas, parent, child, boundsF, state)
				} else {
					onDrawBackground(canvas, parent, child, boundsF, state)
				}
				if (layer != -1) {
					canvas.restoreToCount(layer)
				}
			}
		}
		canvas.restoreToCount(checkpoint)
		fades.removeIf { _, start -> now - start >= duration }
		if (fades.isNotEmpty()) {
			parent.postInvalidateOnAnimation()
		}
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
