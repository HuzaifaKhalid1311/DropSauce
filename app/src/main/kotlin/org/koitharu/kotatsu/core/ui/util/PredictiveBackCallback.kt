package org.koitharu.kotatsu.core.ui.util

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.core.animation.doOnEnd

/**
 * An [OnBackPressedCallback] that previews itself while the back gesture is in progress.
 *
 * Material already animates its own surfaces (bottom/side sheets, the search view, the navigation
 * drawer) and the system animates whole activities, but anything the app handles itself — leaving
 * selection mode, collapsing a toolbar search, returning to the first tab — used to swallow the
 * gesture and then snap. Subclassing this instead of [OnBackPressedCallback] gives those the same
 * follow-the-finger preview: the screen shrinks back and rounds off as you drag, springs back if
 * you let go early, and settles when the action actually runs.
 *
 * Below Android 14 the progress callbacks never fire, so behaviour there is unchanged.
 */
abstract class PredictiveBackCallback(enabled: Boolean) : OnBackPressedCallback(enabled) {

	/**
	 * The view that shrinks during the gesture — normally the whole screen content. Returning
	 * `null` (view already gone, host destroyed) simply skips the preview.
	 */
	protected abstract val backPreviewTarget: View?

	private var transform: PredictiveBackTransform? = null

	/** Runs when the gesture is committed, in place of [handleOnBackPressed]. */
	protected abstract fun onBackConfirmed()

	final override fun handleOnBackStarted(backEvent: BackEventCompat) {
		val target = backPreviewTarget
		// Reuse the transform across gestures: it remembers the view's untouched outline state, and
		// re-capturing that mid-animation would bake our own preview state in as the "original".
		transform?.let { existing ->
			if (existing.view !== target) {
				existing.release()
				transform = null
			}
		}
		if (transform == null && target != null) {
			transform = PredictiveBackTransform(target)
		}
		transform?.start(backEvent)
	}

	final override fun handleOnBackProgressed(backEvent: BackEventCompat) {
		transform?.update(backEvent)
	}

	final override fun handleOnBackCancelled() {
		transform?.settle()
	}

	final override fun handleOnBackPressed() {
		transform?.settle()
		onBackConfirmed()
	}
}

/**
 * The in-app back preview itself: scale the target down, nudge it away from the swiped edge, let it
 * drift with the finger vertically, and round its corners off — the same shape of motion the system
 * uses when a back gesture is about to leave the activity, so in-app and cross-screen back read as
 * one gesture.
 */
private class PredictiveBackTransform(val view: View) {

	private val originalOutlineProvider: ViewOutlineProvider? = view.outlineProvider
	private val originalClipToOutline: Boolean = view.clipToOutline
	private val maxTranslationX = view.dp(MAX_TRANSLATION_X_DP)
	private val maxTranslationY = view.dp(MAX_TRANSLATION_Y_DP)
	private val maxCornerRadius = view.dp(MAX_CORNER_RADIUS_DP)

	private var animator: ValueAnimator? = null
	private var cornerRadius = 0f
	private var startTouchY = 0f
	private var edge = BackEventCompat.EDGE_LEFT

	private val outlineProvider = object : ViewOutlineProvider() {
		override fun getOutline(v: View, outline: Outline) {
			outline.setRoundRect(0, 0, v.width, v.height, cornerRadius)
		}
	}

	fun start(backEvent: BackEventCompat) {
		cancelAnimator()
		edge = backEvent.swipeEdge
		startTouchY = backEvent.touchY
		view.outlineProvider = outlineProvider
		view.clipToOutline = true
	}

	fun update(backEvent: BackEventCompat) {
		edge = backEvent.swipeEdge
		apply(
			progress = INTERPOLATOR.getInterpolation(backEvent.progress.coerceIn(0f, 1f)),
			touchOffsetY = backEvent.touchY - startTouchY,
		)
	}

	/** Eases everything back to rest, whether the gesture was cancelled or committed. */
	fun settle() {
		cancelAnimator()
		val fromScale = view.scaleX
		val fromTranslationX = view.translationX
		val fromTranslationY = view.translationY
		val fromRadius = cornerRadius
		animator = ValueAnimator.ofFloat(1f, 0f).apply {
			duration = SETTLE_DURATION_MS
			addUpdateListener { anim ->
				val fraction = anim.animatedValue as Float
				view.scaleX = 1f + (fromScale - 1f) * fraction
				view.scaleY = view.scaleX
				view.translationX = fromTranslationX * fraction
				view.translationY = fromTranslationY * fraction
				cornerRadius = fromRadius * fraction
				view.invalidateOutline()
			}
			doOnEnd { release() }
			start()
		}
	}

	/** Drops every trace of the preview, restoring the view exactly as it was found. */
	fun release() {
		cancelAnimator()
		view.scaleX = 1f
		view.scaleY = 1f
		view.translationX = 0f
		view.translationY = 0f
		view.pivotX = view.width / 2f
		view.pivotY = view.height / 2f
		cornerRadius = 0f
		view.outlineProvider = originalOutlineProvider
		view.clipToOutline = originalClipToOutline
	}

	// Clears the field before cancelling: cancel() fires doOnEnd, which calls back into release().
	private fun cancelAnimator() {
		val running = animator ?: return
		animator = null
		running.cancel()
	}

	private fun apply(progress: Float, touchOffsetY: Float) {
		val scale = 1f - (1f - MIN_SCALE) * progress
		// Pivot on the swiped edge so the content pulls away from the finger rather than the centre.
		view.pivotX = if (edge == BackEventCompat.EDGE_LEFT) view.width.toFloat() else 0f
		view.pivotY = view.height / 2f
		view.scaleX = scale
		view.scaleY = scale
		val direction = if (edge == BackEventCompat.EDGE_LEFT) 1f else -1f
		view.translationX = direction * maxTranslationX * progress
		// Let the content follow the finger a little, capped so it never drifts far off-centre.
		view.translationY = (touchOffsetY * progress).coerceIn(-maxTranslationY, maxTranslationY)
		cornerRadius = maxCornerRadius * progress
		view.invalidateOutline()
	}

	private fun View.dp(value: Float): Float = value * resources.displayMetrics.density

	private companion object {

		const val MIN_SCALE = 0.9f
		const val MAX_TRANSLATION_X_DP = 8f
		const val MAX_TRANSLATION_Y_DP = 24f
		const val MAX_CORNER_RADIUS_DP = 28f
		const val SETTLE_DURATION_MS = 250L

		// The platform's back-progress easing: most of the movement happens early in the swipe.
		val INTERPOLATOR = PathInterpolator(0.1f, 0.1f, 0f, 1f)
	}
}

/**
 * The content root — everything the activity draws, system-bar padding included — so the preview
 * shrinks the whole screen rather than one fragment inside it.
 */
fun Activity.predictiveBackTarget(): View? = findViewById(android.R.id.content)

/** Same, for content hosted in a dialog (bottom sheets, side sheets). */
fun Dialog.predictiveBackTarget(): View? = findViewById(android.R.id.content)
