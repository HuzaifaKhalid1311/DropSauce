package org.koitharu.kotatsu.reader.ui.tapgrid

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
import org.koitharu.kotatsu.reader.domain.TapGridArea
import kotlin.math.roundToInt

class TapGridDispatcher(
	private val rootView: View,
	private val listener: OnGridTouchListener,
) : GestureDetector.SimpleOnGestureListener() {

	private val detector = GestureDetector(rootView.context, this)
	private var isDispatching = false

	init {
		detector.setIsLongpressEnabled(true)
		detector.setOnDoubleTapListener(this)
	}

	fun dispatchTouchEvent(event: MotionEvent) {
		if (event.actionMasked == MotionEvent.ACTION_DOWN) {
			isDispatching = listener.onProcessTouch(event.rawX.toInt(), event.rawY.toInt())
		}
		detector.onTouchEvent(event)
	}

	override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
		if (!isDispatching) {
			return true
		}
		if (listener.onContentTap(event.rawX, event.rawY)) {
			return true
		}
		val area = getArea(event.rawX, event.rawY) ?: return false
		val location = IntArray(2)
		rootView.getLocationOnScreen(location)
		val horizontalFraction = ((event.rawX - location[0]) / rootView.width.coerceAtLeast(1)).coerceIn(0f, 1f)
		return listener.onGridTouch(area, horizontalFraction)
	}

	override fun onDoubleTapEvent(e: MotionEvent): Boolean {
		isDispatching = false // ignore long press after double tap
		return super.onDoubleTapEvent(e)
	}

	override fun onLongPress(event: MotionEvent) {
		if (isDispatching) {
			if (listener.onContentLongPress(event.rawX, event.rawY)) {
				rootView.hapticFeedback(HapticEffect.LONG_PRESS)
				return
			}
			val area = getArea(event.rawX, event.rawY) ?: return
			if (listener.onGridLongTouch(area)) {
				rootView.hapticFeedback(HapticEffect.LONG_PRESS)
			}
		}
	}

	private fun getArea(rawX: Float, rawY: Float): TapGridArea? {
		val width = rootView.width
		val height = rootView.height
		if (height <= 0 || width <= 0) {
			return null
		}
		// raw coordinates are screen-based; in split-screen or a freeform window the reader isn't at 0,0
		val location = IntArray(2)
		rootView.getLocationOnScreen(location)
		val x = rawX - location[0]
		val y = rawY - location[1]
		val xIndex = (x * 2f / width).roundToInt()
		val yIndex = (y * 2f / height).roundToInt()
		val area = when (xIndex) {
			0 -> when (yIndex) { // LEFT
				0 -> TapGridArea.TOP_LEFT
				1 -> TapGridArea.CENTER_LEFT
				2 -> TapGridArea.BOTTOM_LEFT
				else -> null
			}

			1 -> when (yIndex) { // CENTER
				0 -> TapGridArea.TOP_CENTER
				1 -> TapGridArea.CENTER
				2 -> TapGridArea.BOTTOM_CENTER
				else -> null
			}

			2 -> when (yIndex) { // RIGHT
				0 -> TapGridArea.TOP_RIGHT
				1 -> TapGridArea.CENTER_RIGHT
				2 -> TapGridArea.BOTTOM_RIGHT
				else -> null
			}

			else -> null
		}
		assert(area != null) { "Invalid area ($xIndex, $yIndex)" }
		return area
	}

	interface OnGridTouchListener {

		fun onGridTouch(area: TapGridArea, horizontalFraction: Float): Boolean

		fun onGridLongTouch(area: TapGridArea): Boolean

		fun onProcessTouch(rawX: Int, rawY: Int): Boolean

		/** A tap on something in the page itself, such as a link, which wins over the tap zones. */
		fun onContentTap(rawX: Float, rawY: Float): Boolean = false

		/** A long-press on something in the page itself, such as text, which wins over the tap zones. */
		fun onContentLongPress(rawX: Float, rawY: Float): Boolean = false
	}
}
