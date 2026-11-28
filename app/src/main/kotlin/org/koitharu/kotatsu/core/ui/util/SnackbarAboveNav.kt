package org.koitharu.kotatsu.core.ui.util

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.google.android.material.snackbar.Snackbar
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.main.ui.owners.BottomNavOwner

/**
 * Puts the snackbar right above the nav bar's resting spot, so the bar sliding back in can't cover it
 * (anchoring to the bar itself used wherever the bar happened to be mid-slide), and lets it be swiped down.
 */
fun Snackbar.anchorAboveNav(): Snackbar {
	(context.findActivity() as? BottomNavOwner)?.snackbarAnchor?.let { anchorView = it }
	enableSwipeDownDismiss()
	return this
}

@SuppressLint("ClickableViewAccessibility")
private fun Snackbar.enableSwipeDownDismiss() {
	val slop = ViewConfiguration.get(context).scaledTouchSlop
	var startY = 0f
	var isDragging = false
	// The action button handles its own touches; this only gets touches on the rest of the bar.
	view.setOnTouchListener { v, event ->
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				startY = event.rawY
				isDragging = false
			}

			MotionEvent.ACTION_MOVE -> {
				val dy = event.rawY - startY
				if (!isDragging && dy > slop) {
					isDragging = true
				}
				if (isDragging) {
					v.translationY = dy.coerceAtLeast(0f)
				}
			}

			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				if (isDragging && event.actionMasked == MotionEvent.ACTION_UP && v.translationY > v.height / 3f) {
					// The slide-out restarts from the resting spot (a jump back up); fading leaves it where it was dragged.
					animationMode = Snackbar.ANIMATION_MODE_FADE
					dismiss()
				} else {
					v.animate().translationY(0f).start()
				}
				isDragging = false
			}
		}
		true
	}
}
