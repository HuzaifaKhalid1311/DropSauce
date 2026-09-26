package org.koitharu.kotatsu.widget.stats

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.stats.ui.StatsActivity
import org.koitharu.kotatsu.widget.common.WidgetCoverLoader
import org.koitharu.kotatsu.widget.common.WidgetIntents
import org.koitharu.kotatsu.widget.common.WidgetTheme
import org.koitharu.kotatsu.widget.common.runAsync
import org.koitharu.kotatsu.widget.common.widgetEntryPoint

/**
 * Duolingo-style 2×2 streak widget: a hero flame (see [StreakFlameRenderer]) over the streak count,
 * with the personal best in a corner chip.
 */
class StreakWidget : AppWidgetProvider() {

	override fun onUpdate(
		context: Context,
		appWidgetManager: AppWidgetManager,
		appWidgetIds: IntArray,
	) {
		runAsync(context, TAG) { appContext ->
			val snapshot = appContext.widgetEntryPoint().database.loadStatsSnapshot()
			val colors = WidgetTheme.colors(appContext)
			val views = RemoteViews(appContext.packageName, R.layout.widget_streak)
			views.setTextViewText(R.id.widget_streak_value, snapshot.streakDays.toString())
			if (snapshot.longestStreakDays > 0) {
				views.setTextViewText(R.id.widget_streak_best_value, snapshot.longestStreakDays.toString())
				views.setViewVisibility(R.id.widget_streak_best, View.VISIBLE)
			} else {
				views.setViewVisibility(R.id.widget_streak_best, View.GONE)
			}
			fun color(value: Int?, fallback: Int) = value ?: ContextCompat.getColor(appContext, fallback)
			val hero = StreakFlameRenderer.render(
				context = appContext,
				sizePx = WidgetCoverLoader.dpToPx(appContext, HERO_SIZE_DP),
				lit = snapshot.todayMillis > 0,
				primary = color(colors?.primary, R.color.kotatsu_primary),
				tertiary = color(colors?.tertiary, R.color.kotatsu_tertiary),
				// M3's "disabled" treatment: onSurface at 38%.
				dim = ColorUtils.setAlphaComponent(color(colors?.onSurface, R.color.kotatsu_onSurface), 0x61),
			)
			// The layout's padding only sizes the bare flame in the launcher preview.
			views.setViewPadding(R.id.widget_streak_flame, 0, 0, 0, 0)
			views.setImageViewBitmap(R.id.widget_streak_flame, hero)
			views.setOnClickPendingIntent(
				R.id.widget_streak_root,
				WidgetIntents.openActivity(appContext, Intent(appContext, StatsActivity::class.java), 0),
			)
			colors?.let { c -> WidgetTheme.apply(views, c) }
			appWidgetManager.updateAppWidget(appWidgetIds, views)
		}
	}

	override fun onReceive(context: Context, intent: Intent) {
		super.onReceive(context, intent)
		when (intent.action) {
			Intent.ACTION_BOOT_COMPLETED,
			Intent.ACTION_CONFIGURATION_CHANGED,
			Intent.ACTION_MY_PACKAGE_REPLACED -> {
				val mgr = AppWidgetManager.getInstance(context)
				val ids = mgr.getAppWidgetIds(ComponentName(context, StreakWidget::class.java))
				if (ids.isNotEmpty()) onUpdate(context, mgr, ids)
			}
		}
	}

	private companion object {
		const val TAG = "StreakWidget"
		const val HERO_SIZE_DP = 120
	}
}
