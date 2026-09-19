package org.koitharu.kotatsu.core.ui.util

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import org.koitharu.kotatsu.core.util.ext.getThemeColor

/**
 * Outline of a speech bubble: a rounded rectangle whose top edge opens into a triangular tail.
 * Body and tail are one continuous stroked path, so it reads as a single shape rather than a
 * triangle parked on top of a card.
 *
 * The tail is placed with [alignTailTo], which points it at whichever control sits above the
 * bubble — that keeps it right across layout variants and RTL without hard-coded offsets.
 */
class BubbleOutlineDrawable(
	strokeColor: Int,
	private val strokeWidth: Float,
	private val cornerRadius: Float,
	private val tailWidth: Float,
	val tailHeight: Float,
) : Drawable() {

	private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeJoin = Paint.Join.ROUND
		strokeCap = Paint.Cap.ROUND
		color = strokeColor
		strokeWidth = this@BubbleOutlineDrawable.strokeWidth
	}
	private val path = Path()
	private val corner = RectF()

	/** Tail tip, in px from the drawable's left edge. Null centres it. */
	private var tailCenterX: Float? = null

	/** Points the tail at [target]'s horizontal centre, [target] and the bubble sharing a window. */
	fun alignTailTo(target: View) {
		val self = callback as? View ?: return
		val targetPos = IntArray(2)
		val selfPos = IntArray(2)
		target.getLocationInWindow(targetPos)
		self.getLocationInWindow(selfPos)
		val center = targetPos[0] - selfPos[0] + target.width / 2f
		if (tailCenterX != center) {
			tailCenterX = center
			rebuildPath()
			invalidateSelf()
		}
	}

	override fun onBoundsChange(bounds: Rect) = rebuildPath()

	private fun rebuildPath() {
		val bounds = bounds
		if (bounds.isEmpty) {
			return
		}
		val inset = strokeWidth / 2f
		val left = bounds.left + inset
		val right = bounds.right - inset
		val top = bounds.top + tailHeight + inset
		val bottom = bounds.bottom - inset
		// Never let the corners eat more than the box can give, or the arcs cross over each other.
		val r = minOf(cornerRadius, (right - left) / 2f, (bottom - top) / 2f).coerceAtLeast(0f)
		val halfTail = tailWidth / 2f
		// Keep the tail clear of both corner arcs, so its base never cuts into one.
		val tipX = (tailCenterX ?: bounds.exactCenterX())
			.coerceIn(left + r + halfTail, (right - r - halfTail).coerceAtLeast(left + r + halfTail))

		path.rewind()
		path.moveTo(left + r, top)
		path.lineTo(tipX - halfTail, top)
		path.lineTo(tipX, bounds.top + inset)
		path.lineTo(tipX + halfTail, top)
		path.lineTo(right - r, top)
		corner.set(right - 2 * r, top, right, top + 2 * r)
		path.arcTo(corner, -90f, 90f)
		path.lineTo(right, bottom - r)
		corner.set(right - 2 * r, bottom - 2 * r, right, bottom)
		path.arcTo(corner, 0f, 90f)
		path.lineTo(left + r, bottom)
		corner.set(left, bottom - 2 * r, left + 2 * r, bottom)
		path.arcTo(corner, 90f, 90f)
		path.lineTo(left, top + r)
		corner.set(left, top, left + 2 * r, top + 2 * r)
		path.arcTo(corner, 180f, 90f)
		path.close()
	}

	override fun draw(canvas: Canvas) {
		canvas.drawPath(path, paint)
	}

	override fun setAlpha(alpha: Int) {
		paint.alpha = alpha
	}

	override fun setColorFilter(colorFilter: ColorFilter?) {
		paint.colorFilter = colorFilter
	}

	@Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
	override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

	companion object {

		/**
		 * Makes [view] a bubble and reserves the tail's height above its content. The view keeps its
		 * own horizontal and bottom padding from the layout.
		 */
		fun apply(view: View, @AttrRes strokeColorAttr: Int): BubbleOutlineDrawable {
			fun dp(value: Float) = TypedValue.applyDimension(
				TypedValue.COMPLEX_UNIT_DIP,
				value,
				view.resources.displayMetrics,
			)
			val drawable = BubbleOutlineDrawable(
				strokeColor = view.context.getThemeColor(strokeColorAttr),
				strokeWidth = dp(1f),
				cornerRadius = dp(20f),
				tailWidth = dp(18f),
				tailHeight = dp(9f),
			)
			view.background = drawable
			view.setPadding(
				view.paddingLeft,
				(drawable.tailHeight + dp(8f)).toInt(),
				view.paddingRight,
				view.paddingBottom,
			)
			return drawable
		}
	}
}
