package org.koitharu.kotatsu.core.ui.compose

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Velocity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.NestedScrollingChild3
import androidx.core.view.NestedScrollingChildHelper
import androidx.core.view.ViewCompat
import com.google.android.material.appbar.AppBarLayout
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner

/**
 * Hosts a full-screen [ComposeView] inside `CoordinatorLayout` (`AppBarLayout.ScrollingViewBehavior`)
 * without the drag jitter that hits `rememberNestedScrollInteropConnection()`.
 *
 * Keeps each touch gesture anchored to the window position recorded at `ACTION_DOWN`, accumulates
 * sub-pixel scroll remainders across 120Hz frames so slow drags still move `AppBarLayout`, and
 * coordinates touch/fling nested-scroll lifecycles so catching a fling never snaps the bars mid-hold.
 */
class MovingContainerComposeHost @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : FrameLayout(context, attrs), NestedScrollingChild3 {

	val composeView = ComposeView(context).apply {
		clipChildren = false
		clipToPadding = false
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
	}

	private val childHelper = NestedScrollingChildHelper(this).apply {
		isNestedScrollingEnabled = true
	}

	private val windowLocation = IntArray(2)
	private val consumedBuf = IntArray(2)
	private var downWindowY = 0
	private var preScrollRemainderY = 0f
	private var postScrollRemainderY = 0f
	internal var isGestureActive = false
	internal var isFlingRunning = false

	private val deferredStopTouchScroll = Runnable {
		if (!isGestureActive && !isFlingRunning) {
			stopNestedScroll(ViewCompat.TYPE_TOUCH)
		}
	}

	init {
		clipChildren = false
		clipToPadding = false
		addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
	}

	fun isAppBarFullyExpanded(): Boolean {
		val appBar = (context.findActivity() as? AppBarOwner)?.appBar ?: return true
		val behavior = (appBar.layoutParams as? CoordinatorLayout.LayoutParams)?.behavior as? AppBarLayout.Behavior
		return if (behavior != null) {
			behavior.topAndBottomOffset >= -2
		} else {
			appBar.top >= -2
		}
	}

	override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
		when (ev.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				removeCallbacks(deferredStopTouchScroll)
				getLocationInWindow(windowLocation)
				downWindowY = windowLocation[1]
				preScrollRemainderY = 0f
				postScrollRemainderY = 0f
				isGestureActive = true
				isFlingRunning = false
				return super.dispatchTouchEvent(ev)
			}

			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				val handled = dispatchInDownWindowFrame(ev)
				isGestureActive = false
				// Defer by one message turn: Compose's DragGestureNode dispatches onPreFling right after
				// dispatchTouchEvent returns, so stopping synchronously here would kick off a bar snap
				// 1 frame before the fling takes over.
				removeCallbacks(deferredStopTouchScroll)
				post(deferredStopTouchScroll)
				return handled
			}

			else -> {
				return if (isGestureActive) {
					dispatchInDownWindowFrame(ev)
				} else {
					super.dispatchTouchEvent(ev)
				}
			}
		}
	}

	private fun dispatchInDownWindowFrame(ev: MotionEvent): Boolean {
		getLocationInWindow(windowLocation)
		val dyShift = (windowLocation[1] - downWindowY).toFloat()
		if (dyShift == 0f) {
			return super.dispatchTouchEvent(ev)
		}
		val shifted = MotionEvent.obtain(ev)
		shifted.offsetLocation(0f, dyShift)
		return try {
			super.dispatchTouchEvent(shifted)
		} finally {
			shifted.recycle()
		}
	}

	override fun onDetachedFromWindow() {
		removeCallbacks(deferredStopTouchScroll)
		isGestureActive = false
		isFlingRunning = false
		stopNestedScroll(ViewCompat.TYPE_TOUCH)
		stopNestedScroll(ViewCompat.TYPE_NON_TOUCH)
		childHelper.onDetachedFromWindow()
		super.onDetachedFromWindow()
	}

	// region NestedScrollingChild3

	override fun setNestedScrollingEnabled(enabled: Boolean) {
		childHelper.isNestedScrollingEnabled = enabled
	}

	override fun isNestedScrollingEnabled(): Boolean = childHelper.isNestedScrollingEnabled

	override fun startNestedScroll(axes: Int): Boolean = childHelper.startNestedScroll(axes)

	override fun startNestedScroll(axes: Int, type: Int): Boolean = childHelper.startNestedScroll(axes, type)

	override fun stopNestedScroll() = childHelper.stopNestedScroll()

	override fun stopNestedScroll(type: Int) = childHelper.stopNestedScroll(type)

	override fun hasNestedScrollingParent(): Boolean = childHelper.hasNestedScrollingParent()

	override fun hasNestedScrollingParent(type: Int): Boolean = childHelper.hasNestedScrollingParent(type)

	override fun dispatchNestedScroll(
		dxConsumed: Int,
		dyConsumed: Int,
		dxUnconsumed: Int,
		dyUnconsumed: Int,
		offsetInWindow: IntArray?,
	): Boolean = childHelper.dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow)

	override fun dispatchNestedScroll(
		dxConsumed: Int,
		dyConsumed: Int,
		dxUnconsumed: Int,
		dyUnconsumed: Int,
		offsetInWindow: IntArray?,
		type: Int,
	): Boolean = childHelper.dispatchNestedScroll(
		dxConsumed,
		dyConsumed,
		dxUnconsumed,
		dyUnconsumed,
		offsetInWindow,
		type,
	)

	override fun dispatchNestedScroll(
		dxConsumed: Int,
		dyConsumed: Int,
		dxUnconsumed: Int,
		dyUnconsumed: Int,
		offsetInWindow: IntArray?,
		type: Int,
		consumed: IntArray,
	) = childHelper.dispatchNestedScroll(
		dxConsumed,
		dyConsumed,
		dxUnconsumed,
		dyUnconsumed,
		offsetInWindow,
		type,
		consumed,
	)

	override fun dispatchNestedPreScroll(
		dx: Int,
		dy: Int,
		consumed: IntArray?,
		offsetInWindow: IntArray?,
	): Boolean = childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow)

	override fun dispatchNestedPreScroll(
		dx: Int,
		dy: Int,
		consumed: IntArray?,
		offsetInWindow: IntArray?,
		type: Int,
	): Boolean = childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, type)

	override fun dispatchNestedFling(velocityX: Float, velocityY: Float, consumed: Boolean): Boolean =
		childHelper.dispatchNestedFling(velocityX, velocityY, consumed)

	override fun dispatchNestedPreFling(velocityX: Float, velocityY: Float): Boolean =
		childHelper.dispatchNestedPreFling(velocityX, velocityY)

	// endregion

	internal fun preScrollVertical(availableY: Float, type: Int): Float {
		val totalY = availableY + preScrollRemainderY
		val viewDy = -totalY.toInt()
		if (viewDy == 0) {
			preScrollRemainderY = totalY
			return 0f
		}
		preScrollRemainderY = totalY + viewDy
		if (!hasNestedScrollingParent(type)) {
			startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, type)
		}
		consumedBuf[0] = 0
		consumedBuf[1] = 0
		dispatchNestedPreScroll(0, viewDy, consumedBuf, null, type)
		return (-consumedBuf[1].toFloat()).coerceIn(
			minimumValue = minOf(availableY, 0f),
			maximumValue = maxOf(availableY, 0f),
		)
	}

	internal fun postScrollVertical(consumedY: Float, availableY: Float, type: Int): Float {
		val totalUnconsumedY = availableY + postScrollRemainderY
		val dyConsumed = -consumedY.toInt()
		val dyUnconsumed = -totalUnconsumedY.toInt()
		if (dyConsumed == 0 && dyUnconsumed == 0) {
			postScrollRemainderY = totalUnconsumedY
			return 0f
		}
		postScrollRemainderY = totalUnconsumedY + dyUnconsumed
		if (!hasNestedScrollingParent(type)) {
			startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, type)
		}
		consumedBuf[0] = 0
		consumedBuf[1] = 0
		dispatchNestedScroll(0, dyConsumed, 0, dyUnconsumed, null, type, consumedBuf)
		return (-consumedBuf[1].toFloat()).coerceIn(
			minimumValue = minOf(availableY, 0f),
			maximumValue = maxOf(availableY, 0f),
		)
	}
}

@Composable
fun rememberCoordinatorBarScrollConnection(host: MovingContainerComposeHost): NestedScrollConnection {
	val connection = remember(host) {
		object : NestedScrollConnection {

			override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
				if (available.y == 0f) return Offset.Zero
				val type = source.toViewScrollType()
				val consumedY = host.preScrollVertical(available.y, type)
				return Offset(0f, consumedY)
			}

			override fun onPostScroll(
				consumed: Offset,
				available: Offset,
				source: NestedScrollSource,
			): Offset {
				val type = source.toViewScrollType()
				val parentConsumedY = host.postScrollVertical(consumed.y, available.y, type)
				return Offset(0f, parentConsumedY)
			}

			override suspend fun onPreFling(available: Velocity): Velocity {
				host.isFlingRunning = true
				host.startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_NON_TOUCH)
				if (host.dispatchNestedPreFling(0f, -available.y)) {
					host.isFlingRunning = false
					if (!host.isGestureActive) {
						host.stopNestedScroll(ViewCompat.TYPE_TOUCH)
						host.stopNestedScroll(ViewCompat.TYPE_NON_TOUCH)
					}
					return Velocity(0f, available.y)
				}
				return Velocity.Zero
			}

			override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
				host.dispatchNestedFling(0f, -consumed.y, consumed.y != 0f)
				host.isFlingRunning = false
				// If a new finger caught this fling mid-flight, keep nested scroll alive under that finger.
				if (!host.isGestureActive) {
					host.stopNestedScroll(ViewCompat.TYPE_TOUCH)
					host.stopNestedScroll(ViewCompat.TYPE_NON_TOUCH)
				}
				return Velocity.Zero
			}
		}
	}
	DisposableEffect(host) {
		onDispose {
			host.isFlingRunning = false
			host.stopNestedScroll(ViewCompat.TYPE_TOUCH)
			host.stopNestedScroll(ViewCompat.TYPE_NON_TOUCH)
		}
	}
	return connection
}

private fun NestedScrollSource.toViewScrollType(): Int = when (this) {
	NestedScrollSource.UserInput -> ViewCompat.TYPE_TOUCH
	else -> ViewCompat.TYPE_NON_TOUCH
}
