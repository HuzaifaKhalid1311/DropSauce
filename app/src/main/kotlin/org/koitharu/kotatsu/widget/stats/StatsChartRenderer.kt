package org.koitharu.kotatsu.widget.stats

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

object StatsChartRenderer {

	fun render(
		daily: LongArray,
		widthPx: Int,
		heightPx: Int,
		trackColor: Int,
		barColor: Int,
	): Bitmap {
		val w = widthPx.coerceAtLeast(64)
		val h = heightPx.coerceAtLeast(48)
		val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		val values = LongArray(7) { i -> daily.getOrNull(i) ?: 0L }
		val barCount = values.size
		val spacing = w * 0.06f / (barCount - 1).coerceAtLeast(1)
		val totalSpacing = spacing * (barCount - 1)
		val barWidth = (w - totalSpacing) / barCount
		val maxValue = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
		val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = trackColor
		}
		val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = barColor
		}
		val chartTop = 0f
		val chartBottom = h.toFloat()
		val baselineHeight = (chartBottom - chartTop) * 0.06f
		val corner = (barWidth / 2f).coerceAtMost((chartBottom - chartTop) / 4f)
		for (i in 0 until barCount) {
			val x = i * (barWidth + spacing)
			val trackRect = RectF(x, chartTop, x + barWidth, chartBottom - baselineHeight)
			canvas.drawRoundRect(trackRect, corner, corner, trackPaint)
			val value = values[i]
			if (value <= 0) {
				continue
			}
			val ratio = value.toFloat() / maxValue.toFloat()
			val barH = (chartBottom - baselineHeight) * ratio
			val top = chartBottom - baselineHeight - barH
			val rectActive = RectF(x, top, x + barWidth, chartBottom - baselineHeight)
			canvas.drawRoundRect(rectActive, corner, corner, activePaint)
		}
		return bitmap
	}
}
