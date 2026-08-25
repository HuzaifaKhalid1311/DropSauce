package org.koitharu.kotatsu.widget.common

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.widget.continuereading.ContinueReadingWidget
import org.koitharu.kotatsu.widget.favorites.FavoritesWidget
import org.koitharu.kotatsu.widget.history.HistoryWidget
import org.koitharu.kotatsu.widget.stats.StatsWidget

private val ALL_PROVIDERS = arrayOf(
	ContinueReadingWidget::class.java,
	FavoritesWidget::class.java,
	HistoryWidget::class.java,
	StatsWidget::class.java,
)

/** Asks every pinned instance of [providerClass] to redraw itself. */
fun nudgeWidgets(context: Context, providerClass: Class<*>) {
	val ids = AppWidgetManager.getInstance(context)
		.getAppWidgetIds(ComponentName(context, providerClass))
	if (ids.isEmpty()) return
	context.sendBroadcast(
		Intent(context, providerClass)
			.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
			.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
	)
}

/** Used when something global changed — the color scheme, for instance. */
fun nudgeAllWidgets(context: Context) {
	for (provider in ALL_PROVIDERS) {
		nudgeWidgets(context, provider)
	}
}

/**
 * Repaints every widget when the theme changes. Lives on the Application rather than the settings
 * screen because the onboarding screen writes these keys too, and a widget left on the old palette
 * until its next content update looks broken.
 *
 * Must be held by a strong reference — SharedPreferences keeps listeners weakly.
 */
class WidgetThemeWatcher(private val context: Context) : SharedPreferences.OnSharedPreferenceChangeListener {

	override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
		if (key == AppSettings.KEY_THEME || key == AppSettings.KEY_COLOR_THEME || key == AppSettings.KEY_THEME_AMOLED) {
			WidgetTheme.invalidate()
			nudgeAllWidgets(context)
		}
	}
}
