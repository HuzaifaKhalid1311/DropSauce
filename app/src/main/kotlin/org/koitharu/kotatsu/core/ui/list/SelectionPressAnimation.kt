package org.koitharu.kotatsu.core.ui.list

import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import kotlin.math.max

/** The pressed scale is the aim; the release takes over this far into the dip, carrying its momentum. */
private const val RELEASE_FRACTION = 0.7f
private const val MIN_SCALE = 0.9f
private const val MAX_SCALE = 0.985f

/**
 * Spring-driven dip-and-pop selection feedback, M3 Expressive style.
 *
 * [depthDp] is how far the item's edges travel inwards, so a big list row and a small grid cover
 * dip by the same visible amount instead of the same percentage.
 */
enum class SelectionPress(
	val depthDp: Float,
	val pressStiffness: Float,
	val releaseDamping: Float,
	val releaseStiffness: Float,
) {
	/** A long-press starting selection mode: a soft sink, then a springy pop as the item is picked up. */
	ENTER(8f, 1200f, 0.45f, 450f),

	/** Tapping an item into the selection: a quick dip with a small bounce. */
	SELECT(5f, 3800f, 0.55f, 800f),

	/** Tapping an item out of the selection: a shallow dip that settles without bouncing. */
	DESELECT(3.5f, 3800f, 1f, 800f),
}

/**
 * Plays [press] on the whole list item, even when the touch landed on a child button inside it.
 * Springs are interruptible, so rapid taps flow into each other instead of restarting.
 */
fun View.playSelectionPressAnimation(press: SelectionPress) {
	cancelRipple()
	val item = itemViewOrSelf()
	item.cancelRipple()
	if (ValueAnimator.getDurationScale() == 0f) {
		return
	}
	val spring = item.getTag(R.id.selection_spring) as? SelectionSpring
		?: SelectionSpring(item).also { item.setTag(R.id.selection_spring, it) }
	spring.play(press)
}

private class SelectionSpring(private val item: View) : FloatPropertyCompat<View>("selectionScale") {

	private val animation = SpringAnimation(item, this).apply {
		spring = SpringForce(1f)
		minimumVisibleChange = DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE
		addUpdateListener { _, value, _ -> onUpdate(value) }
	}
	private var pending: SelectionPress? = null
	private var releaseAt = 1f

	fun play(press: SelectionPress) {
		val longSide = max(item.width, item.height).coerceAtLeast(1)
		val depth = press.depthDp * item.resources.displayMetrics.density
		val pressed = (1f - 2f * depth / longSide).coerceIn(MIN_SCALE, MAX_SCALE)
		pending = press
		releaseAt = item.scaleX - (item.scaleX - pressed) * RELEASE_FRACTION
		animation.spring.setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY).setStiffness(press.pressStiffness)
		animation.animateToFinalPosition(pressed)
	}

	private fun onUpdate(value: Float) {
		val press = pending ?: return
		if (value <= releaseAt) {
			pending = null
			animation.spring.setDampingRatio(press.releaseDamping).setStiffness(press.releaseStiffness)
			animation.animateToFinalPosition(1f)
		}
	}

	override fun getValue(view: View): Float = view.scaleX

	override fun setValue(view: View, value: Float) {
		view.scaleX = value
		view.scaleY = value
		// The selection overlay is an item decoration drawn by the RecyclerView, and scaling a child
		// alone never re-records its draw, so it has to be invalidated on every frame to keep up.
		(view.parent as? RecyclerView)?.invalidate()
	}
}

/** The dip replaces the ripple, so stop it from crawling out from under the finger. */
private fun View.cancelRipple() {
	isPressed = false
	jumpDrawablesToCurrentState()
	post {
		isPressed = false
		jumpDrawablesToCurrentState()
	}
}

private fun View.itemViewOrSelf(): View {
	var view = this
	var parent = view.parent
	while (parent is ViewGroup) {
		if (parent is RecyclerView) {
			return view
		}
		view = parent
		parent = view.parent
	}
	return this
}
