package org.koitharu.kotatsu.core.ui.util

import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageButton
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.fragment.app.FragmentManager
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
import org.koitharu.kotatsu.main.ui.MainActivity

// Same as the popups' corner button: 1.3x on M3 Expressive's fast spatial spring.
private const val PRESSED_SCALE = 1.3f
private const val SPRING_DAMPING = 0.6f
private const val SPRING_STIFFNESS = 800f

/**
 * Every top bar's back button magnifies while pressed, like the popups' corner button. Holding it for
 * the long-press timeout gives a firm haptic and turns the arrow into a home icon; releasing after that
 * goes to the home screen, and sliding off cancels. A plain tap still navigates up.
 *
 * Attached from [applyTonalNavigationButtonStyle], which every toolbar in the app routes through.
 */
fun ImageButton.attachBackHomeGesture() {
	if (getTag(R.id.tag_back_home_gesture) != null) return
	setTag(R.id.tag_back_home_gesture, true)
	val timeout = ViewConfiguration.getLongPressTimeout().toLong()
	val scaleX = scaleSpring(DynamicAnimation.SCALE_X)
	val scaleY = scaleSpring(DynamicAnimation.SCALE_Y)
	fun scaleTo(value: Float) {
		scaleX.animateToFinalPosition(value)
		scaleY.animateToFinalPosition(value)
	}
	var armed = false
	var arrow: Drawable? = null
	val arm = Runnable {
		if (isAttachedToWindow) {
			armed = true
			hapticFeedback(HapticEffect.HEAVY_PRESS)
			arrow = drawable
			setImageResource(R.drawable.ic_back_home)
		}
	}
	val cancel = {
		removeCallbacks(arm)
		if (armed) {
			armed = false
			setImageDrawable(arrow)
			arrow = null
		}
		scaleTo(1f)
	}
	// The toolbar gives its navigation button a "Navigate up" tooltip on long press; swallow it.
	setOnLongClickListener { true }
	setOnTouchListener { _, event ->
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				scaleTo(PRESSED_SCALE)
				postDelayed(arm, timeout)
			}

			MotionEvent.ACTION_MOVE -> if (!pointInView(event.x, event.y)) cancel()

			MotionEvent.ACTION_UP -> {
				val wasArmed = armed
				cancel()
				if (wasArmed) {
					goHome()
					// Consume the release so the view never turns it into an ordinary "navigate up" click.
					return@setOnTouchListener true
				}
			}

			MotionEvent.ACTION_CANCEL -> cancel()
		}
		false
	}
}

/** Just the back button's press magnify, for other round buttons. */
fun View.magnifyWhilePressed() {
	setOnTouchListener { _, event ->
		val scale = when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> PRESSED_SCALE
			MotionEvent.ACTION_MOVE -> if (pointInView(event.x, event.y)) PRESSED_SCALE else 1f
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 1f
			else -> null
		}
		if (scale != null) scaleSprings.animateTo(scale)
		false
	}
}

/** Grows in from nothing and shrinks away to nothing instead of popping, on the press magnify's springs. */
fun View.setScaledVisible(isShown: Boolean) {
	if (isShown) {
		if (!isVisible) {
			scaleX = 0f
			scaleY = 0f
			isVisible = true
		}
		scaleSprings.animateTo(1f)
	} else if (isVisible) {
		scaleSprings.animateTo(0f)
	}
}

/** One pair of scale springs per view, so pressing and showing/hiding never fight over the scale. */
private class ScaleSprings(view: View) {

	private val x = view.scaleSpring(DynamicAnimation.SCALE_X)
	private val y = view.scaleSpring(DynamicAnimation.SCALE_Y)

	init {
		x.addEndListener { _, canceled, value, _ ->
			if (!canceled && value == 0f) view.isVisible = false
		}
	}

	fun animateTo(value: Float) {
		// Bouncing past nothing would mirror the view, so shrinking away doesn't overshoot.
		val damping = if (value == 0f) SpringForce.DAMPING_RATIO_NO_BOUNCY else SPRING_DAMPING
		x.spring.dampingRatio = damping
		y.spring.dampingRatio = damping
		x.animateToFinalPosition(value)
		y.animateToFinalPosition(value)
	}
}

private val View.scaleSprings: ScaleSprings
	get() = getTag(R.id.tag_scale_springs) as? ScaleSprings
		?: ScaleSprings(this).also { setTag(R.id.tag_scale_springs, it) }

private fun View.scaleSpring(property: DynamicAnimation.ViewProperty) = SpringAnimation(this, property).apply {
	spring = SpringForce(1f).setDampingRatio(SPRING_DAMPING).setStiffness(SPRING_STIFFNESS)
	minimumVisibleChange = DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE
}

private fun View.pointInView(x: Float, y: Float) = x >= 0 && y >= 0 && x <= width && y <= height

/**
 * Back to the home screen. Everything stacked above it is finished; when home *is* the current
 * activity only its fragment back stack needs clearing, which `CLEAR_TOP` alone would leave behind.
 */
private fun View.goHome() {
	val activity = context.findActivity() ?: return
	if (activity is MainActivity) {
		activity.supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
		return
	}
	activity.startActivity(
		AppRouter.homeIntent(activity)
			.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
	)
}
