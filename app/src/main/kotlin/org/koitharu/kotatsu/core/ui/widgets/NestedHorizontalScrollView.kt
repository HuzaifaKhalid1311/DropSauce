package org.koitharu.kotatsu.core.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import kotlin.math.abs

/**
 * A [HorizontalScrollView] that survives inside a parent which also swipes sideways — a ViewPager2 of
 * tabs, for instance. The pager's own touch slop is small enough that it usually wins the race and
 * eats the drag, which makes a chip row inside it feel impossible to grab.
 *
 * The fix is the one from Google's `NestedScrollableHost` sample: claim the gesture from every
 * ancestor on the way down, then hand it straight back on the first move if it turns out to be
 * vertical, or sideways with no room left to scroll. So the row scrolls while it can, the pager takes
 * over once the row bottoms out, and a vertical drag still reaches the list or sheet behind it.
 */
class NestedHorizontalScrollView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs) {

	/**
	 * Deliberately a fraction of the platform touch slop: the direction has to be settled before any
	 * ancestor reaches its own slop, otherwise the claim is released too late to matter.
	 */
	private val directionSlop = ViewConfiguration.get(context).scaledTouchSlop / 3f

	private var downX = 0f
	private var downY = 0f
	private var isDirectionResolved = false

	override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
		when (e.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				downX = e.x
				downY = e.y
				isDirectionResolved = false
				parent?.requestDisallowInterceptTouchEvent(true)
			}

			MotionEvent.ACTION_MOVE -> if (!isDirectionResolved) {
				val dx = e.x - downX
				val dy = e.y - downY
				// Wait for a move big enough to have a direction at all; a jittery finger on a tap
				// must not release the claim, or the pager grabs the drag that follows.
				if (abs(dx) > directionSlop || abs(dy) > directionSlop) {
					isDirectionResolved = true
					val isOurs = abs(dx) > abs(dy) && canScrollHorizontally(if (dx < 0) 1 else -1)
					if (!isOurs) {
						parent?.requestDisallowInterceptTouchEvent(false)
					}
				}
			}
		}
		return super.onInterceptTouchEvent(e)
	}
}
