package org.koitharu.kotatsu.widget.history

import android.content.Context

/**
 * Per-widget-instance style of the recently read widget. Same file-backed approach as
 * `FavoritesWidgetPrefs`: the widget host calls onUpdate often and this has to be readable from
 * whatever thread the RemoteViews factory runs on.
 */
object HistoryWidgetPrefs {

	private const val PREFS_NAME = "history_widget"
	private const val KEY_PREFIX = "style_"
	private const val STYLE_GRID = "grid"
	private const val STYLE_LIST = "list"

	fun isGrid(context: Context, widgetId: Int): Boolean =
		// Icon (grid) is the default style.
		prefs(context).getString(key(widgetId), STYLE_GRID) != STYLE_LIST

	fun setGrid(context: Context, widgetId: Int, isGrid: Boolean) {
		prefs(context).edit()
			.putString(key(widgetId), if (isGrid) STYLE_GRID else STYLE_LIST)
			.apply()
	}

	fun clear(context: Context, widgetId: Int) {
		prefs(context).edit().remove(key(widgetId)).apply()
	}

	private fun prefs(context: Context) =
		context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	private fun key(widgetId: Int) = KEY_PREFIX + widgetId
}
