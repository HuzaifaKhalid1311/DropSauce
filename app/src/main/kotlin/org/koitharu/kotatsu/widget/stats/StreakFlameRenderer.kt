package org.koitharu.kotatsu.widget.stats

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import org.koitharu.kotatsu.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * The streak widget's hero: a gradient flame inside a ring, with a soft glow and a few sparkles
 * once today's reading is done.
 */
object StreakFlameRenderer {

	// (angle in degrees, 0 = right, clockwise; distance and size as a fraction of the bitmap).
	// None in the top-right corner, where the best-streak chip sits.
	private val SPARKLES = arrayOf(
		floatArrayOf(-140f, 0.44f, 0.05f),
		floatArrayOf(170f, 0.45f, 0.03f),
		floatArrayOf(25f, 0.45f, 0.04f),
		floatArrayOf(125f, 0.45f, 0.028f),
	)

	fun render(
		context: Context,
		sizePx: Int,
		lit: Boolean,
		primary: Int,
		tertiary: Int,
		dim: Int,
	): Bitmap {
		val s = sizePx.toFloat()
		val c = s / 2f
		val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		val paint = Paint(Paint.ANTI_ALIAS_FLAG)

		if (lit) {
			paint.shader = RadialGradient(
				c, c, c,
				intArrayOf(ColorUtils.setAlphaComponent(primary, 0x55), ColorUtils.setAlphaComponent(primary, 0x14), Color.TRANSPARENT),
				floatArrayOf(0f, 0.6f, 1f),
				Shader.TileMode.CLAMP,
			)
			canvas.drawCircle(c, c, c, paint)
			paint.shader = null
		}

		// Ring: a seamless gradient loop when lit, the same grey as the flame otherwise.
		paint.style = Paint.Style.STROKE
		paint.strokeWidth = s * 0.04f
		if (lit) {
			paint.shader = SweepGradient(c, c, intArrayOf(tertiary, primary, tertiary), null)
		} else {
			paint.color = dim
		}
		canvas.drawCircle(c, c, s * 0.32f, paint)
		paint.shader = null
		paint.style = Paint.Style.FILL

		// Flame: the icon used as a mask for a vertical gradient.
		val flameSize = s * 0.38f
		val left = c - flameSize / 2f
		val top = c - flameSize / 2f
		val layer = canvas.saveLayer(null, null)
		ContextCompat.getDrawable(context, R.drawable.ic_streak_flame)?.mutate()?.run {
			setTint(Color.WHITE)
			setBounds(left.toInt(), top.toInt(), (left + flameSize).toInt(), (top + flameSize).toInt())
			draw(canvas)
		}
		paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
		if (lit) {
			paint.shader = LinearGradient(0f, top, 0f, top + flameSize, tertiary, primary, Shader.TileMode.CLAMP)
		} else {
			paint.color = dim
		}
		canvas.drawRect(left, top, left + flameSize, top + flameSize, paint)
		canvas.restoreToCount(layer)
		paint.xfermode = null
		paint.shader = null

		if (lit) {
			val path = Path()
			SPARKLES.forEachIndexed { i, (angle, distance, size) ->
				val rad = Math.toRadians(angle.toDouble())
				val x = c + (cos(rad) * distance * s).toFloat()
				val y = c + (sin(rad) * distance * s).toFloat()
				val h = size * s
				path.reset()
				path.moveTo(x, y - h)
				path.quadTo(x, y, x + h, y)
				path.quadTo(x, y, x, y + h)
				path.quadTo(x, y, x - h, y)
				path.quadTo(x, y, x, y - h)
				paint.color = if (i % 2 == 0) primary else tertiary
				canvas.drawPath(path, paint)
			}
		}
		return bitmap
	}
}
