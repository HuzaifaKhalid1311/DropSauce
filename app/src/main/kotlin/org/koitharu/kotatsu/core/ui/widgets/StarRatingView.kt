package org.koitharu.kotatsu.core.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.material.R as materialR
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import kotlin.math.ceil

/**
 * Five-star rating row drawn from [R.drawable.ic_star_rate], editable by tap or drag in half-star
 * steps. Replaces the framework `RatingBar`, whose stars can't be swapped for a vector.
 */
class StarRatingView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

	private val star: Drawable = checkNotNull(ContextCompat.getDrawable(context, R.drawable.ic_star_rate)).mutate()
	private val starSize = resources.getDimensionPixelSize(R.dimen.rating_star_size)
	private val emptyColor = context.getThemeColor(materialR.attr.colorOutlineVariant)
	private val filledColor = ContextCompat.getColor(context, R.color.common_yellow)

	/** Current rating in stars, 0f..[MAX_RATING]. Setting it does not notify the listener. */
	var rating: Float = 0f
		set(value) {
			val coerced = value.coerceIn(0f, MAX_RATING)
			if (field != coerced) {
				field = coerced
				invalidate()
			}
		}

	/** Called only for user-driven changes, like `RatingBar`'s `fromUser` callback. */
	var onRatingChangeListener: ((Float) -> Unit)? = null

	init {
		isClickable = true
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		setMeasuredDimension(
			resolveSize(starSize * STAR_COUNT + paddingLeft + paddingRight, widthMeasureSpec),
			resolveSize(starSize + paddingTop + paddingBottom, heightMeasureSpec),
		)
	}

	override fun onDraw(canvas: Canvas) {
		for (index in 0 until STAR_COUNT) {
			val left = paddingLeft + index * starSize
			star.setBounds(left, paddingTop, left + starSize, paddingTop + starSize)
			DrawableCompat.setTint(star, emptyColor)
			star.draw(canvas)
			val fraction = (rating - index).coerceIn(0f, 1f)
			if (fraction > 0f) {
				// A half star is the filled star clipped to the covered fraction.
				val checkpoint = canvas.save()
				canvas.clipRect(
					left.toFloat(),
					0f,
					left + starSize * fraction,
					height.toFloat(),
				)
				DrawableCompat.setTint(star, filledColor)
				star.draw(canvas)
				canvas.restoreToCount(checkpoint)
			}
		}
	}

	override fun onTouchEvent(event: MotionEvent): Boolean {
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				// Claim the gesture so the sheet doesn't scroll away mid-drag.
				parent?.requestDisallowInterceptTouchEvent(true)
				updateFromTouch(event.x)
			}

			MotionEvent.ACTION_MOVE -> updateFromTouch(event.x)

			MotionEvent.ACTION_UP,
			MotionEvent.ACTION_CANCEL,
			-> parent?.requestDisallowInterceptTouchEvent(false)

			else -> return super.onTouchEvent(event)
		}
		return true
	}

	private fun updateFromTouch(x: Float) {
		val rowWidth = (starSize * STAR_COUNT).toFloat()
		val stars = (x - paddingLeft) / rowWidth * MAX_RATING
		// Round up to the next half star so the star under the finger reads as filled.
		val value = (ceil(stars * 2f) / 2f).coerceIn(0.5f, MAX_RATING)
		if (value != rating) {
			rating = value
			onRatingChangeListener?.invoke(value)
		}
	}

	companion object {

		const val STAR_COUNT = 5
		const val MAX_RATING = 5f
	}
}
