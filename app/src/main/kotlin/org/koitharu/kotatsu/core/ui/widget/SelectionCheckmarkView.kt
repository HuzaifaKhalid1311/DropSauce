package org.koitharu.kotatsu.core.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import com.google.android.material.color.MaterialColors
import androidx.appcompat.R as appcompatR
import com.google.android.material.R as materialR

/**
 * Material 3 Expressive selection badge. Renders a 9-sided cookie polygon
 * (the Expressive selection identity) tinted [colorPrimary] with a [colorOnPrimary]
 * checkmark inside.
 *
 * [setActivated] drives a spring scale animation: 1f when activated, 0f when not.
 * The view should be added at the top-right of every selectable list/grid item
 * and bound to the item's selection state.
 */
class SelectionCheckmarkView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

	private val cookiePath: Path = RoundedPolygon(
		numVertices = 9,
		radius = 0.5f,
		centerX = 0.5f,
		centerY = 0.5f,
		rounding = CornerRounding(radius = 0.5f),
	).toPath()

	private val scaledPath = Path()
	private val matrix = Matrix()
	private val checkPath = Path()

	private val cookiePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.FILL
		color = MaterialColors.getColor(this@SelectionCheckmarkView, appcompatR.attr.colorPrimary, Color.BLACK)
	}
	private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		color = MaterialColors.getColor(this@SelectionCheckmarkView, materialR.attr.colorOnPrimary, Color.WHITE)
		strokeWidth = resources.displayMetrics.density * 2f
		strokeCap = Paint.Cap.ROUND
		strokeJoin = Paint.Join.ROUND
	}

	private val springAnim = SpringAnimation(this, DynamicAnimation.SCALE_X).apply {
		spring = SpringForce(0f).apply {
			stiffness = SpringForce.STIFFNESS_LOW
			dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
		}
		addUpdateListener { _, value, _ -> scaleY = value }
	}

	private var lastActivated = false

	init {
		scaleX = 0f
		scaleY = 0f
	}

	override fun drawableStateChanged() {
		super.drawableStateChanged()
		val activated = isActivated || drawableState.any { it == android.R.attr.state_activated || it == android.R.attr.state_selected }
		if (activated == lastActivated) return
		lastActivated = activated
		springAnim.cancel()
		springAnim.spring.finalPosition = if (activated) 1f else 0f
		springAnim.start()
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
		super.onSizeChanged(w, h, oldw, oldh)
		val size = minOf(w, h).toFloat()
		matrix.setScale(size, size)
		matrix.postTranslate((w - size) / 2f, (h - size) / 2f)
		cookiePath.transform(matrix, scaledPath)

		// Build a simple check inside the cookie bounds.
		checkPath.reset()
		val cx = w / 2f
		val cy = h / 2f
		val checkSize = size * 0.4f
		checkPath.moveTo(cx - checkSize / 2f, cy)
		checkPath.lineTo(cx - checkSize / 6f, cy + checkSize / 3f)
		checkPath.lineTo(cx + checkSize / 2f, cy - checkSize / 4f)
	}

	override fun onDraw(canvas: Canvas) {
		canvas.drawPath(scaledPath, cookiePaint)
		canvas.drawPath(checkPath, checkPaint)
	}
}
