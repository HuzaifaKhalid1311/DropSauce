package org.koitharu.kotatsu.core.ui.text

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.style.ReplacementSpan
import androidx.annotation.ColorInt

/**
 * Draws its text inside a small rounded box, like an inline chip. Used to make an in-text toggle
 * (e.g. the manga/novel search scope) read as a tappable control instead of plain words.
 */
class ChipBackgroundSpan(
	@ColorInt private val backgroundColor: Int,
	@ColorInt private val textColor: Int,
	private val paddingHorizontal: Float,
	private val paddingVertical: Float,
	private val cornerRadius: Float,
) : ReplacementSpan() {

	private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundColor }
	private val rect = RectF()

	override fun getSize(
		paint: Paint,
		text: CharSequence,
		start: Int,
		end: Int,
		fm: Paint.FontMetricsInt?,
	): Int = (measureText(paint, text, start, end) + paddingHorizontal * 2f).toInt()

	override fun draw(
		canvas: Canvas,
		text: CharSequence,
		start: Int,
		end: Int,
		x: Float,
		top: Int,
		y: Int,
		bottom: Int,
		paint: Paint,
	) {
		val width = measureText(paint, text, start, end)
		rect.set(
			x,
			y + paint.ascent() - paddingVertical,
			x + width + paddingHorizontal * 2f,
			y + paint.descent() + paddingVertical,
		)
		canvas.drawRoundRect(rect, cornerRadius, cornerRadius, boxPaint)
		val prevColor = paint.color
		val prevTypeface = paint.typeface
		paint.color = textColor
		paint.typeface = Typeface.DEFAULT_BOLD
		canvas.drawText(text, start, end, x + paddingHorizontal, y.toFloat(), paint)
		paint.color = prevColor
		paint.typeface = prevTypeface
	}

	private fun measureText(paint: Paint, text: CharSequence, start: Int, end: Int): Float {
		val prevTypeface = paint.typeface
		paint.typeface = Typeface.DEFAULT_BOLD
		val width = paint.measureText(text, start, end)
		paint.typeface = prevTypeface
		return width
	}
}
