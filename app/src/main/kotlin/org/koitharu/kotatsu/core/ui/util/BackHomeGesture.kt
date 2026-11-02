package org.koitharu.kotatsu.core.ui.util

import android.content.Intent
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageButton
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
