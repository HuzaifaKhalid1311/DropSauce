package org.koitharu.kotatsu.reader.ui.epub

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.animation.doOnEnd
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.core.util.ext.getAnimationDuration
import kotlin.math.abs
import kotlin.math.roundToInt

class EpubZoomFrame @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

	private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
	private val gestureDetector = GestureDetector(context, GestureListener())
	private var zoomScale = MIN_SCALE
	private var translationXValue = 0f
	private var translationYValue = 0f
	private var isPanning = false
	private var animator: ValueAnimator? = null

	var isVerticalReadingMode = true
		set(value) {
			if (field == value) return
			field = value
			resetZoom()
		}

	override fun dispatchTouchEvent(event: MotionEvent): Boolean {
		isPanning = false
		gestureDetector.onTouchEvent(event)
		scaleDetector.onTouchEvent(event)
		val handled = super.dispatchTouchEvent(event)
		if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
			isPanning = false
		}
		return handled
	}

	override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
		return scaleDetector.isInProgress ||
			event.pointerCount > 1 ||
			isPanning
	}

	override fun onTouchEvent(event: MotionEvent): Boolean = true

	override fun onViewAdded(child: android.view.View) {
		super.onViewAdded(child)
		applyTransform()
	}

	fun zoomIn() {
		animateScaleTo(zoomScale * ZOOM_IN_FACTOR, width / 2f, height / 2f)
	}

	fun zoomOut() {
		animateScaleTo(zoomScale * ZOOM_OUT_FACTOR, width / 2f, height / 2f)
	}

	private fun resetZoom() {
		animator?.cancel()
		zoomScale = MIN_SCALE
		translationXValue = 0f
		translationYValue = 0f
		applyTransform()
	}

	private fun setScale(target: Float, focusX: Float, focusY: Float) {
		val newScale = target.coerceIn(MIN_SCALE, MAX_SCALE)
		if (newScale == zoomScale) return
		val oldScale = zoomScale
		val ratio = newScale / oldScale
		translationXValue = focusX - (focusX - translationXValue) * ratio
		translationYValue = if (isVerticalReadingMode) {
			0f
		} else {
			focusY - (focusY - translationYValue) * ratio
		}
		zoomScale = newScale
		if (isVerticalReadingMode) {
			val scrollDelta = focusY / oldScale - focusY / newScale
			(getChildAt(0) as? RecyclerView)?.scrollBy(0, scrollDelta.roundToInt())
		}
		constrainTranslation()
		applyTransform()
	}

	private fun panBy(distanceX: Float, distanceY: Float) {
		translationXValue -= distanceX
		if (!isVerticalReadingMode) translationYValue -= distanceY
		constrainTranslation()
		applyTransform()
	}

	private fun constrainTranslation() {
		translationXValue = translationXValue.coerceIn(width * (1f - zoomScale), 0f)
		translationYValue = if (isVerticalReadingMode) {
			0f
		} else {
			translationYValue.coerceIn(height * (1f - zoomScale), 0f)
		}
	}

	private fun applyTransform() {
		val child = getChildAt(0) ?: return
		child.pivotX = 0f
		child.pivotY = 0f
		child.scaleX = zoomScale
		child.scaleY = zoomScale
		child.translationX = translationXValue
		child.translationY = translationYValue
	}

	private fun animateScaleTo(target: Float, focusX: Float, focusY: Float) {
		val newScale = target.coerceIn(MIN_SCALE, MAX_SCALE)
		animator?.cancel()
		animator = ValueAnimator.ofFloat(zoomScale, newScale).apply {
			duration = context.getAnimationDuration(android.R.integer.config_shortAnimTime)
			interpolator = DecelerateInterpolator()
			addUpdateListener { setScale(it.animatedValue as Float, focusX, focusY) }
			doOnEnd {
				if (newScale == MIN_SCALE) resetZoom()
			}
			start()
		}
	}

	private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
		override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
			animator?.cancel()
			return true
		}

		override fun onScale(detector: ScaleGestureDetector): Boolean {
			setScale(zoomScale * detector.scaleFactor, detector.focusX, detector.focusY)
			return true
		}
	}

	private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
		override fun onDown(e: MotionEvent): Boolean = true

		override fun onDoubleTap(e: MotionEvent): Boolean {
			val target = if (zoomScale > MIN_SCALE) MIN_SCALE else DOUBLE_TAP_SCALE
			animateScaleTo(target, e.x, e.y)
			return true
		}

		override fun onScroll(
			e1: MotionEvent?,
			e2: MotionEvent,
			distanceX: Float,
			distanceY: Float,
		): Boolean {
			if (zoomScale <= MIN_SCALE) return false
			isPanning = !isVerticalReadingMode || abs(distanceX) > abs(distanceY)
			if (!isPanning) return false
			panBy(distanceX, distanceY)
			return true
		}
	}

	private companion object {
		const val MIN_SCALE = 1f
		const val MAX_SCALE = 2.5f
		const val DOUBLE_TAP_SCALE = 2f
		const val ZOOM_IN_FACTOR = 1.2f
		const val ZOOM_OUT_FACTOR = 0.8f
	}
}
