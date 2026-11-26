package org.koitharu.kotatsu.core.ui.util

import android.animation.ValueAnimator
import android.view.View
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * M3 Expressive enter/exit for a small floating element: it springs in with a little bounce
 * (expressive fast spatial) and fades out without one (fast effects). Springs are interruptible, so
 * a hide that is cut short by a show turns around mid-flight instead of restarting.
 */
class SpringPopAnimator(
	private val view: View,
	private val hiddenScale: Float,
	private val hiddenTranslationY: Float = 0f,
) {

	private val scaleX = spring(DynamicAnimation.SCALE_X)
	private val scaleY = spring(DynamicAnimation.SCALE_Y)
	// Left alone when there is no rise, so a host that positions the view by translation keeps it.
	private val translationY = if (hiddenTranslationY != 0f) spring(DynamicAnimation.TRANSLATION_Y) else null
	private val alpha = spring(DynamicAnimation.ALPHA).apply {
		addEndListener { _, canceled, _, _ ->
			if (!canceled && !isShown) {
				view.isInvisible = true
			}
		}
	}

	var isShown = false
		private set

	fun show() {
		if (isShown && view.isVisible) {
			return
		}
		isShown = true
		if (!view.isVisible) {
			view.alpha = 0f
			view.scaleX = hiddenScale
			view.scaleY = hiddenScale
			if (translationY != null) {
				view.translationY = hiddenTranslationY
			}
			view.isVisible = true
		}
		animateTo(1f, 1f, 0f, FAST_SPATIAL_STIFFNESS, FAST_SPATIAL_DAMPING)
	}

	fun hide() {
		if (!isShown) {
			return
		}
		isShown = false
		animateTo(hiddenScale, 0f, hiddenTranslationY, FAST_EFFECTS_STIFFNESS, SpringForce.DAMPING_RATIO_NO_BOUNCY)
	}

	/** Gone at once, e.g. when the screen that showed it is left. */
	fun hideNow() {
		isShown = false
		cancel()
		view.isInvisible = true
	}

	fun cancel() {
		scaleX.cancel()
		scaleY.cancel()
		translationY?.cancel()
		alpha.cancel()
	}

	private fun animateTo(scale: Float, alphaValue: Float, dy: Float, stiffness: Float, damping: Float) {
		if (!ValueAnimator.areAnimatorsEnabled()) {
			cancel()
			view.scaleX = scale
			view.scaleY = scale
			if (translationY != null) {
				view.translationY = dy
			}
			view.alpha = alphaValue
			view.isInvisible = alphaValue == 0f
			return
		}
		scaleX.go(scale, stiffness, damping)
		scaleY.go(scale, stiffness, damping)
		translationY?.go(dy, stiffness, damping)
		// Opacity never overshoots: effects springs have no bounce.
		alpha.go(alphaValue, FAST_EFFECTS_STIFFNESS, SpringForce.DAMPING_RATIO_NO_BOUNCY)
	}

	private fun spring(property: DynamicAnimation.ViewProperty) = SpringAnimation(view, property).apply {
		spring = SpringForce()
		minimumVisibleChange = when (property) {
			DynamicAnimation.TRANSLATION_Y -> DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS
			DynamicAnimation.ALPHA -> DynamicAnimation.MIN_VISIBLE_CHANGE_ALPHA
			else -> DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE
		}
	}

	private fun SpringAnimation.go(target: Float, stiffness: Float, damping: Float) {
		spring.setStiffness(stiffness).setDampingRatio(damping)
		animateToFinalPosition(target)
	}

	companion object {

		// M3 Expressive motion tokens (m3_sys_motion_expressive_spring_* in material-components)
		const val FAST_SPATIAL_STIFFNESS = 800f
		const val FAST_SPATIAL_DAMPING = 0.6f
		const val FAST_EFFECTS_STIFFNESS = 3800f
	}
}
