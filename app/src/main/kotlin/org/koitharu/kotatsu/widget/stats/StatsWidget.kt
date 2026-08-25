package org.koitharu.kotatsu.widget.stats

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.stats.ui.StatsActivity
import org.koitharu.kotatsu.widget.common.WidgetColors
import org.koitharu.kotatsu.widget.common.WidgetCoverLoader
import org.koitharu.kotatsu.widget.common.WidgetIntents
import org.koitharu.kotatsu.widget.common.WidgetSizes
import org.koitharu.kotatsu.widget.common.WidgetTheme
import org.koitharu.kotatsu.widget.common.runAsync
import org.koitharu.kotatsu.widget.common.widgetEntryPoint
import java.util.concurrent.TimeUnit

class StatsWidget : AppWidgetProvider() {

	override fun onUpdate(
		context: Context,
		appWidgetManager: AppWidgetManager,
		appWidgetIds: IntArray,
	) {
		runAsync(context, TAG) { appContext ->
			val entry = appContext.widgetEntryPoint()
			val snapshot = entry.database.loadStatsSnapshot()
			val click = WidgetIntents.openActivity(
				appContext,
				Intent(appContext, StatsActivity::class.java),
				0,
			)
			// Render a fresh chart bitmap per widget instance, sized to that widget's actual
			// width so wide resizes don't get stretched by ImageView's fitXY scaling.
			val colors = WidgetTheme.colors(appContext)
			for (widgetId in appWidgetIds) {
				val (chartW, chartH) = chartSizePx(appContext, appWidgetManager, widgetId)
				val chart = StatsChartRenderer.render(
					daily = snapshot.dailyMillis,
					widthPx = chartW,
					heightPx = chartH,
					trackColor = colors?.surfaceContainerHigh
						?: ContextCompat.getColor(appContext, R.color.kotatsu_surfaceContainerHigh),
					barColor = colors?.primary ?: ContextCompat.getColor(appContext, R.color.kotatsu_primary),
				)
				val views = buildContent(appContext, snapshot, chart, click, colors)
				appWidgetManager.updateAppWidget(widgetId, views)
			}
		}
	}

	override fun onAppWidgetOptionsChanged(
		context: Context,
		appWidgetManager: AppWidgetManager,
		appWidgetId: Int,
		newOptions: android.os.Bundle?,
	) {
		// Resize → re-render so the chart matches the new width.
		onUpdate(context, appWidgetManager, intArrayOf(appWidgetId))
	}

	/**
	 * Chart pixel size derived from the actual widget dimensions. Width = full inner width
	 * (after horizontal padding); height = whatever's left after the header rows.
	 */
	private fun chartSizePx(
		context: Context,
		mgr: AppWidgetManager,
		widgetId: Int,
	): Pair<Int, Int> {
		val (widthDp, heightDp) = WidgetSizes.currentSizeDp(
			context = context,
			manager = mgr,
			widgetId = widgetId,
			defaultWidth = 220,
			defaultHeight = 110,
		)
		// 12dp padding × 2 + header (~28dp) + value row (~32dp) + subtitle (~16dp) + 8dp gap.
		val chartWidthDp = (widthDp - 24).coerceAtLeast(120)
		val chartHeightDp = (heightDp - 24 - 28 - 32 - 16 - 8 - 26).coerceAtLeast(48)
		return WidgetCoverLoader.dpToPx(context, chartWidthDp) to
			WidgetCoverLoader.dpToPx(context, chartHeightDp)
	}

	private fun buildContent(
		context: Context,
		snapshot: StatsSnapshot,
		chart: android.graphics.Bitmap,
		clickPi: PendingIntent,
		colors: WidgetColors?,
	): RemoteViews {
		val todayText = formatDuration(context, snapshot.todayMillis)
		val subtitleText = buildSubtitle(context, snapshot)

		val wide = RemoteViews(context.packageName, R.layout.widget_stats).also {
			it.setTextViewText(R.id.widget_stats_today_value, todayText)
			it.setTextViewText(R.id.widget_stats_subtitle, subtitleText)
			if (snapshot.weekMillis > 0) {
				it.setImageViewBitmap(R.id.widget_stats_chart, chart)
				it.setViewVisibility(R.id.widget_stats_chart, View.VISIBLE)
				it.setViewVisibility(R.id.widget_stats_weekdays, View.VISIBLE)
				it.highlightToday(context, snapshot.todayBucket, colors)
			} else {
				it.setViewVisibility(R.id.widget_stats_chart, View.GONE)
				it.setViewVisibility(R.id.widget_stats_weekdays, View.GONE)
			}
			it.setOnClickPendingIntent(R.id.widget_stats_root, clickPi)
			colors?.let { c -> WidgetTheme.apply(it, c) }
		}
		val compact = RemoteViews(context.packageName, R.layout.widget_stats_compact).also {
			it.setTextViewText(R.id.widget_stats_today_value, todayText)
			it.setTextViewText(R.id.widget_stats_subtitle, subtitleText)
			it.setOnClickPendingIntent(R.id.widget_stats_root, clickPi)
			colors?.let { c -> WidgetTheme.apply(it, c) }
		}
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			RemoteViews(
				mapOf(
					SizeF(160f, 80f) to compact,
					SizeF(220f, 140f) to wide,
				),
			)
		} else {
			wide
		}
	}

	private fun RemoteViews.highlightToday(context: Context, todayBucket: Int, colors: WidgetColors?) {
		val dayIds = intArrayOf(
			R.id.widget_stats_weekday_monday,
			R.id.widget_stats_weekday_tuesday,
			R.id.widget_stats_weekday_wednesday,
			R.id.widget_stats_weekday_thursday,
			R.id.widget_stats_weekday_friday,
			R.id.widget_stats_weekday_saturday,
			R.id.widget_stats_weekday_sunday,
		)
		val normalColor = colors?.onSurface ?: ContextCompat.getColor(context, R.color.kotatsu_onSurface)
		val activeColor = colors?.onPrimaryContainer
			?: ContextCompat.getColor(context, R.color.kotatsu_onPrimaryContainer)
		for (id in dayIds) {
			setInt(id, "setBackgroundColor", Color.TRANSPARENT)
			setTextColor(id, normalColor)
		}
		dayIds.getOrNull(todayBucket)?.let { id ->
			setInt(id, "setBackgroundResource", R.drawable.bg_appwidget_weekday_today)
			setTextColor(id, activeColor)
			if (colors != null) {
				setColorStateList(id, "setBackgroundTintList", ColorStateList.valueOf(colors.primaryContainer))
			}
		}
	}

	private fun buildSubtitle(context: Context, snapshot: StatsSnapshot): String {
		val weekText = formatDuration(context, snapshot.weekMillis)
		return when {
			!snapshot.hasAny -> context.getString(R.string.widget_stats_empty)
			snapshot.streakDays >= 2 -> "${snapshot.streakDays}-day streak · $weekText this week"
			else -> "$weekText this week"
		}
	}

	private fun formatDuration(context: Context, millis: Long): String {
		if (millis <= 0L) return context.getString(R.string.widget_stats_minutes_short, 0)
		val totalMin = TimeUnit.MILLISECONDS.toMinutes(millis).toInt()
		val hours = totalMin / 60
		val minutes = totalMin % 60
		return if (hours > 0) {
			context.getString(R.string.widget_stats_hours_short, hours, minutes)
		} else {
			context.getString(R.string.widget_stats_minutes_short, minutes)
		}
	}

	override fun onReceive(context: Context, intent: Intent) {
		super.onReceive(context, intent)
		when (intent.action) {
			ACTION_REFRESH,
			Intent.ACTION_BOOT_COMPLETED,
			Intent.ACTION_CONFIGURATION_CHANGED,
			Intent.ACTION_MY_PACKAGE_REPLACED -> {
				val mgr = AppWidgetManager.getInstance(context)
				val ids = mgr.getAppWidgetIds(ComponentName(context, StatsWidget::class.java))
				if (ids.isNotEmpty()) onUpdate(context, mgr, ids)
			}
		}
	}

	companion object {
		private const val TAG = "StatsWidget"
		const val ACTION_REFRESH = "org.koitharu.kotatsu.widget.stats.REFRESH"
	}
}
