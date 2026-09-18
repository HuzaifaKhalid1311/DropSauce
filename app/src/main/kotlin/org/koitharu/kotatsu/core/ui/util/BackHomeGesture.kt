package org.koitharu.kotatsu.core.ui.util

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import androidx.core.animation.doOnEnd
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.FragmentManager
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
import org.koitharu.kotatsu.main.ui.MainActivity
import com.google.android.material.R as materialR

/**
 * Press-and-hold on any top bar's back button to jump straight to the home screen.
 *
 * Holding for the system long-press timeout grows the button sideways into a pill and slides a home
 * glyph in next to the arrow, along with a long-press haptic: the button is now *armed*. The arrow
 * stays exactly as it is and the home glyph appears well to its **trailing** side, so nothing changes
 * under the finger that is doing the holding and the new icon is never covered by it. Releasing
 * anywhere on the pill — including on the arrow the finger never left — goes home; sliding off or
 * cancelling shrinks it back, so the gesture is always escapable. A plain tap is untouched: it still
 * navigates up.
 *
 * Attached from [applyTonalNavigationButtonStyle], which is the single place every toolbar in the app
 * routes through, so no screen has to opt in.
 */
fun ImageButton.attachBackHomeGesture() {
	if (getTag(R.id.tag_back_home_gesture) != null) return
	setTag(R.id.tag_back_home_gesture, true)
	val timeout = ViewConfiguration.getLongPressTimeout().toLong()
	var armed = false
	val disarm = {
		if (armed) {
			armed = false
			collapseFromHome()
		}
	}
	val arm = Runnable {
		if (isAttachedToWindow && !armed) {
			armed = true
			hapticFeedback(HapticEffect.LONG_PRESS)
			expandToHome()
		}
	}
	// The toolbar gives its navigation button a "Navigate up" tooltip on long press; swallow it, the
	// pill is the feedback now.
	setOnLongClickListener { true }
	setOnTouchListener { _, event ->
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> postDelayed(arm, timeout)
			MotionEvent.ACTION_MOVE -> if (!pointInView(event.x, event.y)) {
				removeCallbacks(arm)
				disarm()
			}

			MotionEvent.ACTION_UP -> {
				removeCallbacks(arm)
				if (armed) {
					disarm()
					hapticFeedback(HapticEffect.CONFIRM)
					goHome()
					// Consume the release so the view never turns it into an ordinary "navigate up" click.
					return@setOnTouchListener true
				}
			}

			MotionEvent.ACTION_CANCEL -> {
				removeCallbacks(arm)
				disarm()
			}
		}
		false
	}
}

/**
 * True from the moment the pill starts opening until it has finished closing again. The tonal styler
 * checks this and steps aside for the whole of it — its per-layout-pass `updateLayoutSize` would
 * otherwise snap the width back on the very first frame, which is what made the close look instant.
 */
internal val ImageButton.isBackHomeExpanded: Boolean
	get() = getTag(R.id.tag_back_home_expanded) == true

private fun View.pointInView(x: Float, y: Float) = x >= 0 && y >= 0 && x <= width && y <= height

private const val EXPAND_DURATION = 220L
private const val COLLAPSE_DURATION = 160L

/**
 * Grows the button into a pill and reveals a home glyph near its trailing edge.
 *
 * The arrow itself is never touched: it stays the same drawable, the same size and in the same place,
 * because the growth is absorbed by an equal amount of end padding — `FIT_CENTER` therefore keeps
 * centring it in the original square. The home glyph rides in the *background*, pinned to the trailing
 * edge, so it slides out from under the arrow as the pill opens and needs no layout of its own. It
 * sits a full icon's width clear of the arrow, well outside the finger holding it.
 */
private fun ImageButton.expandToHome() {
	val home = context.getDrawable(R.drawable.ic_home_rounded)?.mutate() ?: return
	val base = background ?: return
	val iconSize = resources.getDimensionPixelSize(R.dimen.top_bar_action_icon_size)
	val cellSize = resources.getDimensionPixelSize(R.dimen.top_bar_navigation_button_size)
	val inset = ((cellSize - iconSize) / 2).coerceAtLeast(0)
	val extra = iconSize * 2
	home.setTint(context.getThemeColor(R.attr.colorTopBarIcon))
	home.alpha = 0
	val layers = LayerDrawable(arrayOf(base, home))
	layers.setLayerSize(1, iconSize, iconSize)
	layers.setLayerGravity(1, Gravity.END or Gravity.CENTER_VERTICAL)
	layers.setLayerInsetEnd(1, inset)
	// Marks a still-running close as superseded, so its end callback leaves this one alone.
	if (isBackHomeExpanded) setTag(R.id.tag_back_home_reopened, true)
	setTag(R.id.tag_back_home_expanded, true)
	setTag(R.id.tag_back_home_background, base)
	// Above the title, which is laid out after the navigation button and would otherwise be drawn
	// over the widened pill.
	bringToFront()
	background = layers
	showBackHomeHint()
	animateWidthTo(
		target = cellSize + extra,
		duration = EXPAND_DURATION,
		interpolator = android.R.interpolator.fast_out_slow_in,
		onUpdate = { width ->
			val revealed = width - cellSize
			updatePaddingRelative(end = inset + revealed)
			home.alpha = (255 * revealed / extra.toFloat()).toInt().coerceIn(0, 255)
		},
	)
}

private fun ImageButton.collapseFromHome() {
	dismissBackHomeHint()
	val base = getTag(R.id.tag_back_home_background) as? Drawable
	val iconSize = resources.getDimensionPixelSize(R.dimen.top_bar_action_icon_size)
	val cellSize = resources.getDimensionPixelSize(R.dimen.top_bar_navigation_button_size)
	val inset = ((cellSize - iconSize) / 2).coerceAtLeast(0)
	val home = (background as? LayerDrawable)?.getDrawable(1)
	val extra = (iconSize * 2).toFloat()
	animateWidthTo(
		target = cellSize,
		duration = COLLAPSE_DURATION,
		interpolator = android.R.interpolator.fast_out_linear_in,
		onUpdate = { width ->
			val revealed = width - cellSize
			updatePaddingRelative(end = inset + revealed)
			home?.alpha = (255 * revealed / extra).toInt().coerceIn(0, 255)
		},
		onEnd = {
			// Cancelling an animator also reports "end", so leave it alone if a new hold already re-armed.
			if (getTag(R.id.tag_back_home_reopened) == null && base != null) {
				background = base
				setTag(R.id.tag_back_home_background, null)
				// Only now may the styler take the button back over.
				setTag(R.id.tag_back_home_expanded, null)
			}
			setTag(R.id.tag_back_home_reopened, null)
		},
	)
}

private fun View.animateWidthTo(
	target: Int,
	duration: Long,
	interpolator: Int,
	onUpdate: (Int) -> Unit,
	onEnd: () -> Unit = {},
) {
	(getTag(R.id.tag_back_home_animator) as? ValueAnimator)?.cancel()
	val animator = ValueAnimator.ofInt(width, target).apply {
		this.duration = duration
		this.interpolator = AnimationUtils.loadInterpolator(context, interpolator)
		addUpdateListener { anim ->
			val value = anim.animatedValue as Int
			val lp = layoutParams ?: return@addUpdateListener
			lp.width = value
			onUpdate(value)
			layoutParams = lp
		}
	}
	animator.doOnEnd { onEnd() }
	setTag(R.id.tag_back_home_animator, animator)
	animator.start()
}

/**
 * A small caption below and to the trailing side of the pill — clear of the thumb — spelling out the
 * two ways the gesture can end. Purely informative:
 * the window is untouchable and unfocusable, so it never steals the touch that is still in progress.
 */
private fun ImageButton.showBackHomeHint() {
	if (getTag(R.id.tag_back_home_hint) != null) return
	val padH = (resources.displayMetrics.density * 12f).toInt()
	val padV = (resources.displayMetrics.density * 6f).toInt()
	val label = TextView(context).apply {
		setText(R.string.back_home_hint)
		TextViewCompat.setTextAppearance(this, materialR.style.TextAppearance_Material3_LabelMedium)
		setTextColor(context.getThemeColor(materialR.attr.colorOnSurfaceInverse))
		setPadding(padH, padV, padH, padV)
		background = GradientDrawable().apply {
			shape = GradientDrawable.RECTANGLE
			cornerRadius = resources.displayMetrics.density * 8f
			setColor(context.getThemeColor(materialR.attr.colorSurfaceInverse))
		}
		alpha = 0f
	}
	val popup = PopupWindow(label, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
		isTouchable = false
		isFocusable = false
		isOutsideTouchable = false
	}
	setTag(R.id.tag_back_home_hint, popup)
	// Down and to the trailing side rather than straight underneath: directly below the button is
	// exactly where the thumb doing the holding sits.
	popup.showAsDropDown(
		this,
		resources.getDimensionPixelSize(R.dimen.top_bar_navigation_button_size),
		(resources.displayMetrics.density * 14f).toInt(),
	)
	label.animate().alpha(1f).setDuration(EXPAND_DURATION).start()
}

private fun ImageButton.dismissBackHomeHint() {
	(getTag(R.id.tag_back_home_hint) as? PopupWindow)?.dismiss()
	setTag(R.id.tag_back_home_hint, null)
}

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
