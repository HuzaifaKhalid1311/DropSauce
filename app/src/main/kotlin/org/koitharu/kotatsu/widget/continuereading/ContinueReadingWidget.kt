package org.koitharu.kotatsu.widget.continuereading

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.util.SizeF
import android.widget.RemoteViews
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.widget.common.WidgetCoverLoader
import org.koitharu.kotatsu.widget.common.WidgetIntents
import org.koitharu.kotatsu.widget.common.nudgeWidgets
import org.koitharu.kotatsu.widget.common.WidgetSizes
import org.koitharu.kotatsu.widget.common.WidgetTheme
import org.koitharu.kotatsu.widget.common.runAsync
import org.koitharu.kotatsu.widget.common.widgetEntryPoint
import kotlin.math.roundToInt

class ContinueReadingWidget : AppWidgetProvider() {

	override fun onUpdate(
		context: Context,
		appWidgetManager: AppWidgetManager,
		appWidgetIds: IntArray,
	) {
		runAsync(context, TAG) { appContext ->
			val entryPoint = appContext.widgetEntryPoint()
			val manga = entryPoint.historyRepository.getLastOrNull()
			val history = manga?.let { entryPoint.historyRepository.getOne(it) }

			// Pass 1: render text-only state immediately so the widget never sits blank.
			for (widgetId in appWidgetIds) {
				val views = if (manga != null) {
					buildContent(appContext, manga, history, wideCover = null)
				} else {
					buildEmpty(appContext)
				}
				appWidgetManager.updateAppWidget(widgetId, views)
			}

			// Pass 2: load cover per widget instance, sized to the actual slot pixels so the
			// rounded corners stay crisp at every resize and the cover fills the height with
			// no wasted padding.
			if (manga != null) {
				for (widgetId in appWidgetIds) {
					val (slotW, slotH) = coverSlotPx(appContext, appWidgetManager, widgetId)
					val wideCover = WidgetCoverLoader.load(
						context = appContext,
						loader = entryPoint.imageLoader,
						manga = manga,
						targetWidth = slotW,
						targetHeight = slotH,
						cornerRadiusPx = WidgetCoverLoader.dpToPx(appContext, 12).toFloat(),
					)
					val compactCover = WidgetCoverLoader.load(
						context = appContext,
						loader = entryPoint.imageLoader,
						manga = manga,
						targetWidth = WidgetCoverLoader.dpToPx(appContext, 48),
						targetHeight = WidgetCoverLoader.dpToPx(appContext, 64),
						cornerRadiusPx = WidgetCoverLoader.dpToPx(appContext, 8).toFloat(),
					)
					if (wideCover != null || compactCover != null) {
						appWidgetManager.updateAppWidget(
							widgetId,
							buildContent(appContext, manga, history, wideCover, compactCover),
						)
					} else {
						Log.w(TAG, "cover bitmap null for manga=${manga.title}")
					}
				}
			}
		}
	}

	/**
	 * Cover slot pixel size. The width is fixed at 92dp to match the layout; the height
	 * follows the widget's current size so the cover bitmap is rendered exactly once at the
	 * right dimensions, keeping the rounded corners crisp.
	 */
	private fun coverSlotPx(
		context: Context,
		mgr: AppWidgetManager,
		widgetId: Int,
	): Pair<Int, Int> {
		val (_, heightDp) = WidgetSizes.currentSizeDp(
			context = context,
			manager = mgr,
			widgetId = widgetId,
			defaultWidth = 0,
			defaultHeight = 110,
		)
		// Outer padding 10dp ×2, header row ~28dp.
		val slotHeightDp = (heightDp - 20 - 28).coerceAtLeast(56)
		return WidgetCoverLoader.dpToPx(context, 92) to
			WidgetCoverLoader.dpToPx(context, slotHeightDp)
	}

	override fun onAppWidgetOptionsChanged(
		context: Context,
		appWidgetManager: AppWidgetManager,
		appWidgetId: Int,
		newOptions: android.os.Bundle?,
	) {
		// Resize → re-render at the new height so the cover bitmap is regenerated.
		onUpdate(context, appWidgetManager, intArrayOf(appWidgetId))
	}

	private fun buildContent(
		context: Context,
		manga: Manga,
		history: MangaHistory?,
		wideCover: Bitmap?,
		compactCover: Bitmap? = wideCover,
	): RemoteViews {
		val percentFloat = (history?.percent ?: 0f).coerceIn(0f, 1f)
		val percent = (percentFloat * 100f).roundToInt()
		val subtitle = context.getString(R.string.widget_progress_percent, percent)
		val totalChapters = history?.chaptersCount ?: 0
		val chapterText = if (totalChapters > 0) {
			val current = (percentFloat * totalChapters).toInt().coerceAtLeast(if (percent > 0) 1 else 0)
			context.getString(R.string.widget_chapter_position, current, totalChapters)
		} else {
			null
		}

		val colors = WidgetTheme.colors(context)
		val wide = RemoteViews(context.packageName, R.layout.widget_continue_reading).also {
			applyWide(it, manga.title, subtitle, chapterText, percent, wideCover)
			wirePendingIntents(context, it, manga, history, wide = true)
			colors?.let { c -> WidgetTheme.apply(it, c) }
		}
		val small = RemoteViews(context.packageName, R.layout.widget_continue_reading_small).also {
			applySmall(it, manga.title, percent, compactCover)
			wirePendingIntents(context, it, manga, history, wide = false)
			colors?.let { c -> WidgetTheme.apply(it, c) }
		}
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			RemoteViews(
				mapOf(
					SizeF(180f, 70f) to small,
					SizeF(220f, 110f) to wide,
				),
			)
		} else {
			wide
		}
	}

	private fun applyWide(
		views: RemoteViews,
		title: String,
		subtitle: String,
		chapterText: String?,
		percent: Int,
		cover: Bitmap?,
	) {
		views.setTextViewText(R.id.widget_title, title)
		views.setTextViewText(R.id.widget_subtitle, subtitle)
		if (chapterText != null) {
			views.setTextViewText(R.id.widget_chapter, chapterText)
			views.setViewVisibility(R.id.widget_chapter, android.view.View.VISIBLE)
		} else {
			views.setViewVisibility(R.id.widget_chapter, android.view.View.GONE)
		}
		views.setProgressBar(R.id.widget_progress, 100, percent, false)
		if (cover != null) {
			views.setImageViewBitmap(R.id.widget_cover, cover)
		} else {
			views.setImageViewResource(R.id.widget_cover, R.drawable.ic_widget_cover_placeholder)
		}
	}

	private fun applySmall(
		views: RemoteViews,
		title: String,
		percent: Int,
		cover: Bitmap?,
	) {
		views.setTextViewText(R.id.widget_title, title)
		views.setProgressBar(R.id.widget_progress, 100, percent, false)
		if (cover != null) {
			views.setImageViewBitmap(R.id.widget_cover, cover)
		} else {
			views.setImageViewResource(R.id.widget_cover, R.drawable.ic_widget_cover_placeholder)
		}
	}

	private fun wirePendingIntents(
		context: Context,
		views: RemoteViews,
		manga: Manga,
		history: MangaHistory?,
		@Suppress("UNUSED_PARAMETER") wide: Boolean,
	) {
		// Only the play button is tappable — the rest of the widget is decorative,
		// so a misclick never opens the reader unexpectedly.
		val readPi = WidgetIntents.continueReading(context, manga, history)
		// The wide layout's button is `widget_cta`, the compact one's is the icon itself; setting
		// both is harmless because a missing id is skipped.
		views.setOnClickPendingIntent(R.id.widget_cta, readPi)
		views.setOnClickPendingIntent(R.id.widget_cta_icon, readPi)
	}

	private fun buildEmpty(context: Context): RemoteViews {
		val views = RemoteViews(context.packageName, R.layout.widget_continue_reading_empty)
		val pi = WidgetIntents.openActivity(
			context,
			AppRouter.homeIntent(context),
			0,
		)
		views.setOnClickPendingIntent(R.id.widget_continue_reading_empty_root, pi)
		WidgetTheme.apply(context, views)
		return views
	}

	override fun onReceive(context: Context, intent: Intent) {
		super.onReceive(context, intent)
		when (intent.action) {
			ACTION_REFRESH,
			Intent.ACTION_BOOT_COMPLETED,
			Intent.ACTION_CONFIGURATION_CHANGED,
			Intent.ACTION_MY_PACKAGE_REPLACED -> nudgeAll(context)
		}
	}

	companion object {
		private const val TAG = "ContinueReadingWidget"
		const val ACTION_REFRESH = "org.koitharu.kotatsu.widget.continuereading.REFRESH"

		fun nudgeAll(context: Context) = nudgeWidgets(context, ContinueReadingWidget::class.java)
	}
}
