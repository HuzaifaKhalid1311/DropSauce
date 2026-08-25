package org.koitharu.kotatsu.widget.history

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koitharu.kotatsu.R

/**
 * Style picker for the recently read widget: shown when a widget is added and reachable again
 * through the host's "reconfigure" action.
 */
class HistoryWidgetConfigActivity : AppCompatActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val widgetId = intent.getIntExtra(
			AppWidgetManager.EXTRA_APPWIDGET_ID,
			AppWidgetManager.INVALID_APPWIDGET_ID,
		)
		// Cancel by default: the host drops a freshly added widget if the user backs out. The
		// default style is already stored implicitly, so a cancel still leaves a usable widget.
		setResult(Activity.RESULT_CANCELED, resultIntent(widgetId))
		if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
			finish()
			return
		}
		var checked = if (HistoryWidgetPrefs.isGrid(this, widgetId)) 0 else 1
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.widget_history_style)
			.setSingleChoiceItems(
				arrayOf(
					getString(R.string.widget_history_style_icon),
					getString(R.string.widget_history_style_list),
				),
				checked,
			) { _, which -> checked = which }
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.save) { _, _ ->
				HistoryWidgetPrefs.setGrid(this, widgetId, isGrid = checked == 0)
				setResult(Activity.RESULT_OK, resultIntent(widgetId))
				HistoryWidget.nudge(this, widgetId)
			}
			.setOnDismissListener { finish() }
			.show()
	}

	private fun resultIntent(widgetId: Int) = Intent()
		.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
}
