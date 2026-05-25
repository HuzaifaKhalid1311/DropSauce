package org.koitharu.kotatsu.core.util.ext

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.koitharu.kotatsu.core.prefs.AppSettings

/**
 * Material 3 Expressive haptic feedback helpers.
 *
 * Each extension function calls [View.performHapticFeedback] with the
 * Material-recommended constant for that interaction. All calls short-circuit
 * to a no-op when the per-app preference is disabled; the OS-level haptic
 * setting is honoured automatically by [View.performHapticFeedback] itself.
 */

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface HapticEntryPoint {
	val settings: AppSettings
}

private fun View.hapticsEnabled(): Boolean = EntryPointAccessors
	.fromApplication(context.applicationContext, HapticEntryPoint::class.java)
	.settings.isHapticsEnabled

private fun View.feedback(constant: Int) {
	if (hapticsEnabled()) performHapticFeedback(constant)
}

/** Discrete tick - sliders crossing detents, chip tap, tab change. */
fun View.hapticTick() = feedback(HapticFeedbackConstants.CLOCK_TICK)

/** Affirmative action - FAB press, primary button, slider release. */
fun View.hapticConfirm() = feedback(
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
	else HapticFeedbackConstants.VIRTUAL_KEY,
)

/** Negative or "edge" feedback - swipe-past-end, remove. */
fun View.hapticReject() = feedback(
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
	else HapticFeedbackConstants.LONG_PRESS,
)

/** Toggle - switch / checkbox / radio. */
fun View.hapticToggle(on: Boolean) = feedback(
	when {
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && on ->
			HapticFeedbackConstants.TOGGLE_ON
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !on ->
			HapticFeedbackConstants.TOGGLE_OFF
		else -> HapticFeedbackConstants.VIRTUAL_KEY
	},
)

/** Gesture has reached its endpoint - bottom sheet snap, swipe to refresh. */
fun View.hapticGestureEnd() = feedback(
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.GESTURE_END
	else HapticFeedbackConstants.CONTEXT_CLICK,
)

/** Long-press - card long-tap, list-item context menu. */
fun View.hapticLongPress() = feedback(HapticFeedbackConstants.LONG_PRESS)
