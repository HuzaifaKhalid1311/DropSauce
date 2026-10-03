package org.koitharu.kotatsu.core.logs

import android.app.Activity
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.core.view.children
import kotlin.math.hypot

private const val MAX_LABEL_CHARS = 60

/**
 * Logs what the user taps and which menu items they pick while a log is being recorded, so a bug
 * report shows the steps that led to the problem. Wraps the activity window's callback: every touch
 * and every options/overflow menu selection passes through it, whatever the activity overrides.
 * Compose content has no per-element views, so its taps are not resolved (settings changes are logged
 * separately).
 */
class UserActionLogger private constructor(
	private val activity: Activity,
	private val base: Window.Callback,
) : Window.Callback by base {

	private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
	private var downX = 0f
	private var downY = 0f
	private var downTime = 0L

	override fun dispatchTouchEvent(event: MotionEvent): Boolean {
		if (AppLogger.isRecording) onTouch(event)
		return base.dispatchTouchEvent(event)
	}

	override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean {
		breadcrumb("Menu") { "${activity.javaClass.simpleName}: ${item.describe()}" }
		return base.onMenuItemSelected(featureId, item)
	}

	private fun onTouch(event: MotionEvent) {
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				downX = event.rawX
				downY = event.rawY
				downTime = event.eventTime
			}

			MotionEvent.ACTION_UP -> {
				// A scroll or fling also ends in ACTION_UP; only a finger that stayed put is a tap.
				if (hypot(event.rawX - downX, event.rawY - downY) > touchSlop) return
				val isLong = event.eventTime - downTime >= ViewConfiguration.getLongPressTimeout()
				val target = activity.window.decorView.findClickableAt(downX.toInt(), downY.toInt()) ?: return
				breadcrumb("Tap") {
					"${activity.javaClass.simpleName}: ${if (isLong) "long-press" else "tap"} ${target.describe()}"
				}
			}
		}
	}

	private fun MenuItem.describe(): String {
		val name = runCatching { activity.resources.getResourceEntryName(itemId) }.getOrNull() ?: "item $itemId"
		return if (title.isNullOrBlank()) name else "$name \"$title\""
	}

	companion object {

		fun install(activity: Activity) {
			val window = activity.window ?: return
			val current = window.callback ?: return
			if (current !is UserActionLogger) {
				window.callback = UserActionLogger(activity, current)
			}
		}
	}
}

/** The topmost visible clickable view under a screen point (later children draw on top). */
private fun View.findClickableAt(x: Int, y: Int): View? {
	if (!isShown) return null
	val location = IntArray(2)
	getLocationOnScreen(location)
	if (x < location[0] || y < location[1] || x >= location[0] + width || y >= location[1] + height) return null
	if (this is ViewGroup) {
		for (i in childCount - 1 downTo 0) {
			getChildAt(i).findClickableAt(x, y)?.let { return it }
		}
	}
	return takeIf { isClickable || isLongClickable }
}

/** e.g. `button_next "Next chapter"`, or `item "One Piece" in recyclerView` for an id-less list row. */
private fun View.describe(): String = buildString {
	append(idName() ?: javaClass.simpleName)
	val label = (contentDescription ?: firstText())?.toString()?.trim()?.replace('\n', ' ')
	if (!label.isNullOrEmpty()) append(" \"").append(label.take(MAX_LABEL_CHARS)).append('"')
	if (id == View.NO_ID) {
		generateSequence(parent as? View) { it.parent as? View }
			.firstNotNullOfOrNull { it.idName() }
			?.let { append(" in ").append(it) }
	}
}

private fun View.idName(): String? = if (id == View.NO_ID) null else runCatching { resources.getResourceEntryName(id) }.getOrNull()

private fun View.firstText(): CharSequence? = when (this) {
	is TextView -> text?.takeIf { it.isNotBlank() }
	is ViewGroup -> children.firstNotNullOfOrNull { it.firstText() }
	else -> null
}
