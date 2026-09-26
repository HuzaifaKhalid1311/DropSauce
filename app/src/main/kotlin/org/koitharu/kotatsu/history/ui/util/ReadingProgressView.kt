package org.koitharu.kotatsu.history.ui.util

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode.CHAPTERS_READ
import androidx.core.content.ContextCompat
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode.NONE
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode.PERCENT_READ
import org.koitharu.kotatsu.list.domain.ReadingProgress
import kotlin.math.roundToInt

/**
 * A small frosted "pill" badge that shows the read percentage (e.g. "29%") over a cover.
 * It auto-sizes to its text, so layouts should give it wrap_content bounds.
 */
class ReadingProgressView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	@AttrRes defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

	private val hPadding = dp(10f)
	private val vPadding = dp(V_PADDING_DP)
	private val textSizeNormal = sp(TEXT_SIZE_NORMAL_SP)
	private val textSizeSmall = sp(TEXT_SIZE_SMALL_SP)

	private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = SCRIM_COLOR
		style = Paint.Style.FILL
	}
	private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
		color = Color.WHITE
		textAlign = Paint.Align.CENTER
		typeface = Typeface.DEFAULT_BOLD
		textSize = textSizeNormal
	}
	private val rectF = RectF()
	private val percentPattern = context.getString(R.string.percent_string_pattern)

	private val checkIcon = ContextCompat.getDrawable(context, R.drawable.ic_check)?.mutate()?.also {
		it.setTint(Color.WHITE)
	}

	private var text: String = ""
	private var isCompleted: Boolean = false

	var progress: ReadingProgress? = null
		set(value) {
			field = value
			val oldCompleted = isCompleted
			isCompleted = value != null && value.mode != NONE && (
				value.isCompleted() || (value.mode == CHAPTERS_READ && value.totalChapters > 0 && (value.percent * value.totalChapters).roundToInt() >= value.totalChapters)
			)
			val newText = when (value?.mode) {
				null, NONE -> ""
				PERCENT_READ -> if (value.percent in 0f..1f) {
					percentPattern.format(ReadingProgress.percentToString(value.percent))
				} else {
					""
				}
				CHAPTERS_READ -> if (value.percent in 0f..1f && value.totalChapters > 0) {
					val read = (value.percent * value.totalChapters).roundToInt()
					"${read}/${value.totalChapters}"
				} else {
					""
				}
			}
			if (newText != text || isCompleted != oldCompleted) {
				text = newText
				requestLayout()
				invalidate()
			}
		}

	init {
		if (isInEditMode) {
			progress = ReadingProgress(0.29f, 20, PERCENT_READ)
		}
	}

	fun setProgress(percent: Float, @Suppress("UNUSED_PARAMETER") animate: Boolean) {
		progress = ReadingProgress(percent, 1, PERCENT_READ)
	}

	fun setProgress(value: ReadingProgress?, @Suppress("UNUSED_PARAMETER") animate: Boolean) {
		progress = value
	}

	/** Picks a smaller text size for compact (small) grid cells. */
	fun setSmall(small: Boolean) {
		val size = if (small) textSizeSmall else textSizeNormal
		if (textPaint.textSize != size) {
			textPaint.textSize = size
			requestLayout()
			invalidate()
		}
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		if (text.isEmpty() && !isCompleted) {
			setMeasuredDimension(0, 0)
			return
		}
		val fm = textPaint.fontMetrics
		val h = (fm.descent - fm.ascent + vPadding * 2f).toInt()
		val w = if (isCompleted) {
			h
		} else {
			(textPaint.measureText(text) + hPadding * 2f).toInt()
		}
		setMeasuredDimension(
			resolveSize(w, widthMeasureSpec),
			resolveSize(h, heightMeasureSpec),
		)
	}

	override fun onDraw(canvas: Canvas) {
		if (text.isEmpty() && !isCompleted) {
			return
		}
		rectF.set(0f, 0f, width.toFloat(), height.toFloat())
		val radius = height / 2f
		canvas.drawRoundRect(rectF, radius, radius, bgPaint)
		if (isCompleted && checkIcon != null) {
			val iconSize = (height * 0.6f).toInt()
			val left = (width - iconSize) / 2
			val top = (height - iconSize) / 2
			checkIcon.setBounds(left, top, left + iconSize, top + iconSize)
			checkIcon.draw(canvas)
		} else {
			val fm = textPaint.fontMetrics
			val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
			canvas.drawText(text, width / 2f, baseline, textPaint)
		}
	}

	private fun dp(value: Float) = TypedValue.applyDimension(
		TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics,
	)

	private fun sp(value: Float) = TypedValue.applyDimension(
		TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics,
	)

	companion object {

		private const val V_PADDING_DP = 5f
		private const val TEXT_SIZE_NORMAL_SP = 12.5f
		private const val TEXT_SIZE_SMALL_SP = 10.5f

		/** The pill's height regardless of its text, so other cover pills can match it. */
		fun pillHeight(resources: Resources, small: Boolean): Int {
			val dm = resources.displayMetrics
			val paint = Paint().apply {
				typeface = Typeface.DEFAULT_BOLD
				textSize = TypedValue.applyDimension(
					TypedValue.COMPLEX_UNIT_SP,
					if (small) TEXT_SIZE_SMALL_SP else TEXT_SIZE_NORMAL_SP,
					dm,
				)
			}
			val fm = paint.fontMetrics
			val vPadding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, V_PADDING_DP, dm)
			return (fm.descent - fm.ascent + vPadding * 2f).toInt()
		}

		// Translucent dark "frosted" scrim — keeps the white text legible over any cover.
		private const val SCRIM_COLOR = 0x99000000.toInt()
	}
}
