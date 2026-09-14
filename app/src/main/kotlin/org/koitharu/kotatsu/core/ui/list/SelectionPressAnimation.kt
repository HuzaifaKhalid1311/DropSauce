package org.koitharu.kotatsu.core.ui.list

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.interpolator.view.animation.FastOutLinearInInterpolator
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.core.util.ext.applySystemAnimatorScale

private const val DEEP_SCALE = 0.85f
private const val DEEP_PRESS_DURATION = 150L
private const val DEEP_RELEASE_DURATION = 400L
private const val DEEP_TENSION = 3.6f

private const val LIGHT_SCALE = 0.90f
private const val LIGHT_PRESS_DURATION = 100L
private const val LIGHT_RELEASE_DURATION = 290L
private const val LIGHT_TENSION = 3.0f

/**
 * Squish-and-bounce feedback for selection, M3 Expressive style.
 *
 * [isDeep] — a deeper, slower press with a bigger bounce, for entering selection mode.
 * Otherwise a quick nudge, for toggling items while already in selection mode.
 *
 * The animation always plays on the whole list item, even when the touch landed on a child
 * button inside it.
 */
fun View.playSelectionPressAnimation(isDeep: Boolean) {
	cancelRipple()
	with(itemViewOrSelf()) {
		cancelRipple()
		playSquish(isDeep)
	}
}

private fun View.playSquish(isDeep: Boolean) {
	val scale = if (isDeep) DEEP_SCALE else LIGHT_SCALE
	// The selection overlay is an item decoration drawn by the RecyclerView, and scaling a child
	// alone never re-records its draw, so it has to be invalidated on every frame to keep up.
	val listView = parent as? RecyclerView
	val invalidateList = ValueAnimator.AnimatorUpdateListener { listView?.invalidate() }
	animate().cancel()
	scaleX = 1f
	scaleY = 1f
	animate()
		.scaleX(scale)
		.scaleY(scale)
		.setInterpolator(FastOutLinearInInterpolator())
		.setDuration(if (isDeep) DEEP_PRESS_DURATION else LIGHT_PRESS_DURATION)
		.applySystemAnimatorScale(context)
		.setUpdateListener(invalidateList)
		.withEndAction {
			animate()
				.scaleX(1f)
				.scaleY(1f)
				.setInterpolator(OvershootInterpolator(if (isDeep) DEEP_TENSION else LIGHT_TENSION))
				.setDuration(if (isDeep) DEEP_RELEASE_DURATION else LIGHT_RELEASE_DURATION)
				.applySystemAnimatorScale(context)
				.setUpdateListener(invalidateList)
				.setListener(ResetScaleListener(listView, this))
				.start()
		}.start()
}

/** The squish replaces the ripple, so stop it from crawling out from under the finger. */
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

/** A recycled or detached view must never be left squished, so reset on cancel as well as on end. */
private class ResetScaleListener(
	private val listView: RecyclerView?,
	private val view: View,
) : AnimatorListenerAdapter() {

	override fun onAnimationEnd(animation: Animator) {
		view.scaleX = 1f
		view.scaleY = 1f
		view.animate().setListener(null).setUpdateListener(null)
		listView?.invalidate()
	}
}
