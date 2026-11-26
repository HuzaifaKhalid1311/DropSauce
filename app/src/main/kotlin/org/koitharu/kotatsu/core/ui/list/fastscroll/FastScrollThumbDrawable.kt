package org.koitharu.kotatsu.core.ui.list.fastscroll

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import org.koitharu.kotatsu.core.ui.util.SpringPopAnimator
import kotlin.math.ceil

/**
 * A fully rounded thumb that swells from a thin line into a fat, easy-to-hold handle while it is
 * grabbed, on an M3 Expressive spring so the grab lands with a small bounce.
 */
internal class FastScrollThumbDrawable(
	private val thinWidth: Float,
	private val thickWidth: Float,
	private val height: Int,
) : Drawable() {

	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val rect = RectF()

	/** 0 = resting thin line, 1 = grabbed handle. Overshoots a little while springing. */
	private var expansion = 0f
		set(value) {
			field = value
			invalidateSelf()
		}

	private val expansionSpring = SpringAnimation(
		this,
		object : FloatPropertyCompat<FastScrollThumbDrawable>("expansion") {
			override fun getValue(d: FastScrollThumbDrawable) = d.expansion
			override fun setValue(d: FastScrollThumbDrawable, value: Float) {
				d.expansion = value
			}
		},
	).apply {
		spring = SpringForce()
			.setStiffness(SpringPopAnimator.FAST_SPATIAL_STIFFNESS)
			.setDampingRatio(SpringPopAnimator.FAST_SPATIAL_DAMPING)
		minimumVisibleChange = 1f / 64f
	}

	fun setGrabbed(grabbed: Boolean) {
		expansionSpring.animateToFinalPosition(if (grabbed) 1f else 0f)
	}

	override fun draw(canvas: Canvas) {
		val width = (thinWidth + (thickWidth - thinWidth) * expansion).coerceAtLeast(0f)
		val centerX = bounds.exactCenterX()
		rect.set(centerX - width / 2f, bounds.top.toFloat(), centerX + width / 2f, bounds.bottom.toFloat())
		canvas.drawRoundRect(rect, width / 2f, width / 2f, paint)
	}

	// Room for the spring's overshoot, so the bounce is never clipped.
	override fun getIntrinsicWidth() = ceil(thickWidth * 1.25f).toInt()

	override fun getIntrinsicHeight() = height

	override fun setTintList(tint: ColorStateList?) {
		paint.color = tint?.defaultColor ?: 0
		invalidateSelf()
	}

	override fun setAlpha(alpha: Int) {
		paint.alpha = alpha
		invalidateSelf()
	}

	override fun setColorFilter(colorFilter: ColorFilter?) {
		paint.colorFilter = colorFilter
		invalidateSelf()
	}

	@Deprecated("Deprecated in Java")
	override fun getOpacity() = PixelFormat.TRANSLUCENT
}
