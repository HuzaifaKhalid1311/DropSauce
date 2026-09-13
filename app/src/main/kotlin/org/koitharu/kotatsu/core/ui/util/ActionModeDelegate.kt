package org.koitharu.kotatsu.core.ui.util

import android.annotation.SuppressLint
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.ImageView
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.ActionBarContextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.getThemeDimensionPixelOffset

@SuppressLint("RestrictedApi") // intentional access to AppCompat's ActionBarContextView for edge-to-edge handling
class ActionModeDelegate(
	// Resolves the colour the contextual bar paints so it blends with the surface it floats over.
	// Defaults to the window background (regular screens); hosts on a different surface (e.g. a bottom
	// sheet) supply a resolver that returns that surface's actual colour so the bar matches its top bar.
	private val backgroundColorResolver: (Window) -> Int = { it.context.getThemeColor(android.R.attr.colorBackground) },
	// The view the back-gesture preview shrinks while selection mode is being dismissed. Hosts that
	// can supply their content root pass it here; without one the gesture still works, just unpreviewed.
	private val backPreviewTargetProvider: () -> View? = { null },
) : PredictiveBackCallback(false) {

	override val backPreviewTarget: View?
		get() = backPreviewTargetProvider()

	private var activeActionMode: ActionMode? = null
	private var exitingActionMode: ActionMode? = null
	private var listeners: MutableList<ActionModeListener>? = null
	private var actionModePreDraw: Pair<ActionBarContextView, ViewTreeObserver.OnPreDrawListener>? = null
	private var exitCompleteActions: MutableList<() -> Unit>? = null

	val isActionModeStarted: Boolean
		get() = activeActionMode != null

	override fun onBackConfirmed() {
		finishActionMode()
	}

	fun onSupportActionModeStarted(mode: ActionMode, window: Window?) {
		exitingActionMode = null
		exitCompleteActions = null
		activeActionMode = mode
		isEnabled = true
		listeners?.forEach { it.onActionModeStarted(mode) }
		if (window != null) {
			// The contextual bar is drawn as a single continuous surface that extends edge-to-edge
			// behind the (transparent) status bar, so the status region and the icons region are one
			// view that fades in/out as a unit. Painting window.statusBarColor separately created a
			// second surface that snapped in instantly while the bar faded — that mismatch was the
			// "split top bar" that flickered on selection. We keep the status bar transparent and let
			// the bar paint the whole area in the window background, matching every other top bar.
			val actionModeColor = backgroundColorResolver(window)
			// Ignore visibility: with the "hide status bar" setting the rest of the UI still lays out
			// with the status-bar height (see BaseActivity.setContentView), so the bar must too —
			// otherwise it collapses to y=0 and AppCompat's status guard paints over it.
			val statusBarHeight = ViewCompat.getRootWindowInsets(window.decorView)
				?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())?.top ?: 0
			window.decorView.findViewById<ActionBarContextView?>(androidx.appcompat.R.id.action_mode_bar)?.apply {
				applyEdgeToEdgeActionMode(color = actionModeColor, statusBarHeight = statusBarHeight)
				keepActionModeEdgeToEdge(mode, color = actionModeColor, statusBarHeight = statusBarHeight)
				applyTonalActionModeStyle(installLayoutHooks = true)
			}
		}
	}

	fun onSupportActionModeFinished(mode: ActionMode, window: Window?) {
		activeActionMode = null
		exitingActionMode = mode
		isEnabled = false
		listeners?.forEach { it.onActionModeFinished(mode) }
		val actionModeView = actionModePreDraw?.first
		if (actionModeView == null) {
			completeActionModeExit()
		} else {
			actionModeView.postDelayed({
				if (exitingActionMode === mode) {
					completeActionModeExit()
				}
			}, ACTION_MODE_EXIT_CLEANUP_DELAY_MS)
		}
	}

	fun addListener(listener: ActionModeListener) {
		if (listeners == null) {
			listeners = ArrayList()
		}
		checkNotNull(listeners).add(listener)
	}

	fun removeListener(listener: ActionModeListener) {
		listeners?.remove(listener)
	}

	fun addListener(listener: ActionModeListener, owner: LifecycleOwner) {
		addListener(listener)
		owner.lifecycle.addObserver(ListenerLifecycleObserver(listener))
	}

	fun finishActionMode() {
		activeActionMode?.finish()
	}

	fun runAfterActionModeExit(action: () -> Unit) {
		if (exitingActionMode == null) {
			action()
		} else {
			if (exitCompleteActions == null) {
				exitCompleteActions = ArrayList()
			}
			exitCompleteActions?.add(action)
		}
	}

	/**
	 * Forces the contextual bar to span edge-to-edge under the status bar. Returns `true` when it had
	 * to mutate layout-affecting state (content height / padding / margin) — the caller skips drawing
	 * that frame so the bar is never shown in its transient, mis-positioned state (the visible "jump
	 * down" flicker that happened on invalidate and on back-gesture exit).
	 */
	private fun ActionBarContextView.applyEdgeToEdgeActionMode(color: Int, statusBarHeight: Int): Boolean {
		val actionBarHeight = context.getThemeDimensionPixelOffset(
			androidx.appcompat.R.attr.actionBarSize,
			contentHeight,
		)
		val totalHeight = actionBarHeight + statusBarHeight
		if ((background as? ColorDrawable)?.color != color) {
			setBackgroundColor(color)
		}
		var needsLayout = false
		// Inset the bar's content to match the regular top bars: the close "X" gets its start inset from
		// its own margin (which ActionBarContextView honours), but the action pill's end margin is
		// ignored by this view's layout — its end inset has to come from the bar's end padding instead.
		val edgeInset = resources.getDimensionPixelSize(R.dimen.top_bar_navigation_button_margin_start)
		if (paddingTop != statusBarHeight || paddingStart != 0 || paddingEnd != edgeInset) {
			setPaddingRelative(0, statusBarHeight, edgeInset, paddingBottom)
			needsLayout = true
		}
		if (contentHeight != totalHeight) {
			setContentHeight(totalHeight)
			needsLayout = true
		}
		if (minimumHeight != totalHeight) {
			minimumHeight = totalHeight
			needsLayout = true
		}
		val params = layoutParams as? ViewGroup.MarginLayoutParams
		if (params != null && params.topMargin != 0) {
			params.topMargin = 0
			layoutParams = params
			needsLayout = true
		}
		hideStatusGuard(statusBarHeight)
		if (needsLayout) {
			requestLayout()
		}
		return needsLayout
	}

	// AppCompat draws an anonymous status guard above overlaid action modes.
	private fun ActionBarContextView.hideStatusGuard(statusBarHeight: Int) {
		findStatusGuard(statusBarHeight)?.visibility = View.GONE
	}

	private fun ActionBarContextView.findStatusGuard(statusBarHeight: Int): View? {
		if (statusBarHeight == 0) {
			return null
		}
		val parentView = parent as? ViewGroup ?: return null
		return parentView.children.firstOrNull { child ->
			if (child === this || child.id != View.NO_ID) {
				return@firstOrNull false
			}
			val params = child.layoutParams as? ViewGroup.MarginLayoutParams ?: return@firstOrNull false
			params.height == statusBarHeight && params.topMargin == 0
		}
	}

	private fun ActionBarContextView.keepActionModeEdgeToEdge(
		mode: ActionMode,
		color: Int,
		statusBarHeight: Int,
	) {
		clearActionModePreDraw()
		val listener = ViewTreeObserver.OnPreDrawListener {
			when {
				activeActionMode === mode -> {
					applyTonalActionModeStyle(installLayoutHooks = false)
					// Skip drawing this frame if we had to re-fix the layout, so the bar is never
					// painted in its transient (shrunk / shifted) state.
					!applyEdgeToEdgeActionMode(color = color, statusBarHeight = statusBarHeight)
				}
				exitingActionMode === mode && visibility == View.VISIBLE -> {
					applyTonalActionModeStyle(installLayoutHooks = false)
					!applyEdgeToEdgeActionMode(color = color, statusBarHeight = statusBarHeight)
				}
				else -> {
					if (exitingActionMode === mode) {
						completeActionModeExit()
					} else {
						clearActionModePreDraw()
					}
					true
				}
			}
		}
		viewTreeObserver.addOnPreDrawListener(listener)
		actionModePreDraw = this to listener
	}

	private fun ActionBarContextView.applyTonalActionModeStyle(installLayoutHooks: Boolean) {
		// Wrap the close "X" in a tonal circle to match the back buttons in the rest of the app.
		findViewById<ImageView?>(androidx.appcompat.R.id.action_mode_close_button)
			?.applyTonalIconButtonStyle()
		// Group the selection actions into the same tonal pill used by the regular top bars.
		if (installLayoutHooks) {
			applyTonalActionMenuStyle()
		} else {
			applyTonalActionMenuStyleNow()
		}
	}

	private fun clearActionModePreDraw() {
		val (view, listener) = actionModePreDraw ?: return
		if (view.viewTreeObserver.isAlive) {
			view.viewTreeObserver.removeOnPreDrawListener(listener)
		}
		actionModePreDraw = null
	}

	private fun completeActionModeExit() {
		exitingActionMode = null
		clearActionModePreDraw()
		val actions = exitCompleteActions ?: return
		exitCompleteActions = null
		actions.forEach { it() }
	}

	private companion object {
		private const val ACTION_MODE_EXIT_CLEANUP_DELAY_MS = 500L
	}

	private inner class ListenerLifecycleObserver(
		private val listener: ActionModeListener,
	) : DefaultLifecycleObserver {

		override fun onDestroy(owner: LifecycleOwner) {
			super.onDestroy(owner)
			removeListener(listener)
		}
	}
}
