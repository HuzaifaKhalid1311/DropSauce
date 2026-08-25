package org.koitharu.kotatsu.widget.favorites

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.widget.common.WidgetCoverLoader
import org.koitharu.kotatsu.widget.common.WidgetIntents
import org.koitharu.kotatsu.widget.common.nudgeWidgets
import org.koitharu.kotatsu.widget.common.WidgetSizes
import org.koitharu.kotatsu.widget.common.WidgetTheme
import org.koitharu.kotatsu.widget.common.runAsync
import org.koitharu.kotatsu.widget.common.widgetEntryPoint

class FavoritesWidget : AppWidgetProvider() {

	override fun onUpdate(
		context: Context,
		appWidgetManager: AppWidgetManager,
		appWidgetIds: IntArray,
	) {
		// A single onReceive dispatch may only call goAsync() once: renderWidget() used to call it
		// per widget id, so 2+ pinned Favorites widgets crashed on the second call. Render the
		// synchronous placeholder pass for every id first, then do the async cover-loading pass for
		// all of them inside one runAsync() block (mirrors StatsWidget/ContinueReadingWidget).
		val pinnedByWidget = appWidgetIds.associateWith { widgetId ->
			val pinnedIds = FavoritesWidgetPrefs.load(context, widgetId)
			appWidgetManager.updateAppWidget(widgetId, buildViews(context, widgetId, pinnedIds, emptyMap()))
			pinnedIds
		}
		val widgetsWithPins = pinnedByWidget.filterValues { it.isNotEmpty() }
		if (widgetsWithPins.isEmpty()) return

		runAsync(context, TAG) { appContext ->
			val entryPoint = appContext.widgetEntryPoint()
			val mangaDao = entryPoint.database.getMangaDao()
			val cornerRadius = WidgetCoverLoader.dpToPx(appContext, 12).toFloat()
			val mgr = AppWidgetManager.getInstance(appContext)
			for ((widgetId, pinnedIds) in widgetsWithPins) {
				val slotSize = computeSlotSizePx(appContext, mgr, widgetId, pinnedIds.size)
				val mangaById = HashMap<Long, Manga>(pinnedIds.size)
				val coversById = HashMap<Long, Bitmap>(pinnedIds.size)
				for (id in pinnedIds) {
					val manga = runCatching { mangaDao.find(id)?.toManga() }.getOrNull() ?: continue
					mangaById[id] = manga
					val cover = WidgetCoverLoader.load(
						context = appContext,
						loader = entryPoint.imageLoader,
						manga = manga,
						targetWidth = slotSize.first,
						targetHeight = slotSize.second,
						cornerRadiusPx = cornerRadius,
					)
					if (cover != null) {
						coversById[id] = cover
					}
				}
				mgr.updateAppWidget(widgetId, buildViews(appContext, widgetId, pinnedIds, coversById, mangaById))
			}
		}
	}

	override fun onAppWidgetOptionsChanged(
		context: Context,
		appWidgetManager: AppWidgetManager,
		appWidgetId: Int,
		newOptions: android.os.Bundle?,
	) {
		// Re-render after a resize so the slot weights adapt.
		renderWidget(context, appWidgetManager, appWidgetId)
	}

	override fun onDeleted(context: Context, appWidgetIds: IntArray) {
		for (id in appWidgetIds) {
			FavoritesWidgetPrefs.clear(context, id)
		}
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

	private fun renderWidget(
		context: Context,
		appWidgetManager: AppWidgetManager,
		widgetId: Int,
	) {
		val pinnedIds = FavoritesWidgetPrefs.load(context, widgetId)

		// First pass: render text-only / placeholders so the widget never sits blank.
		appWidgetManager.updateAppWidget(widgetId, buildViews(context, widgetId, pinnedIds, emptyMap()))

		if (pinnedIds.isEmpty()) return

		// Compute the actual pixel size of each cover slot so the bitmap is rendered at that
		// exact size — no centerCrop scaling, so the baked-in rounded corners stay visible
		// no matter how the user resizes the widget.
		val slotSize = computeSlotSizePx(context, appWidgetManager, widgetId, pinnedIds.size)

		// Second pass: load manga + covers asynchronously, then refresh.
		runAsync(context, TAG) { appContext ->
			val entryPoint = appContext.widgetEntryPoint()
			val mangaById = HashMap<Long, Manga>(pinnedIds.size)
			val coversById = HashMap<Long, Bitmap>(pinnedIds.size)
			val mangaDao = entryPoint.database.getMangaDao()
			// Fixed dp radius matches `bg_appwidget_cover`'s 12dp corners so the curve looks
			// identical whether the slot is small (3 pins, narrow) or large (1 pin, wide).
			val cornerRadius = WidgetCoverLoader.dpToPx(appContext, 12).toFloat()
			for (id in pinnedIds) {
				val manga = runCatching { mangaDao.find(id)?.toManga() }.getOrNull() ?: continue
				mangaById[id] = manga
				val cover = WidgetCoverLoader.load(
					context = appContext,
					loader = entryPoint.imageLoader,
					manga = manga,
					targetWidth = slotSize.first,
					targetHeight = slotSize.second,
					cornerRadiusPx = cornerRadius,
				)
				if (cover != null) {
					coversById[id] = cover
				}
			}
			val mgr = AppWidgetManager.getInstance(appContext)
			mgr.updateAppWidget(widgetId, buildViews(appContext, widgetId, pinnedIds, coversById, mangaById))
		}
	}

	/**
	 * Returns the pixel `(width, height)` of a single cover slot. We read `getAppWidgetOptions`
	 * to honor the user's current resize and divide the available width by the slot count, so
	 * each cover gets a bitmap that matches its on-screen footprint exactly.
	 */
	private fun computeSlotSizePx(
		context: Context,
		mgr: AppWidgetManager,
		widgetId: Int,
		pinCount: Int,
	): Pair<Int, Int> {
		val (widthDp, heightDp) = WidgetSizes.currentSizeDp(
			context = context,
			manager = mgr,
			widgetId = widgetId,
			defaultWidth = 250,
			defaultHeight = 110,
		)
		// Subtract widget padding (12dp top + 12dp bottom) and the actual header chrome:
		// header row ~26dp tall (settings icon dominates at 22dp + 2dp padding × 2) plus its
		// 8dp paddingBottom. The fallbacks keep us safe if options aren't populated yet.
		val innerWidthDp = (widthDp - 24).coerceAtLeast(48)
		val gapsDp = 12 * (pinCount - 1).coerceAtLeast(0)
		val slotWidthDp = ((innerWidthDp - gapsDp) / pinCount.coerceAtLeast(1)).coerceAtLeast(48)
		val headerDp = 26 + 8
		val slotHeightDp = (heightDp - 24 - headerDp).coerceAtLeast(48)
		return WidgetCoverLoader.dpToPx(context, slotWidthDp) to
			WidgetCoverLoader.dpToPx(context, slotHeightDp)
	}

	private fun buildViews(
		context: Context,
		widgetId: Int,
		pinnedIds: List<Long>,
		covers: Map<Long, Bitmap>,
		mangaById: Map<Long, Manga> = emptyMap(),
	): RemoteViews {
		val views = RemoteViews(context.packageName, R.layout.widget_favorites)
		views.setOnClickPendingIntent(R.id.widget_settings, configIntent(context, widgetId))
		views.setOnClickPendingIntent(R.id.widget_header, configIntent(context, widgetId))
		if (WidgetTheme.isSupported) {
			WidgetTheme.apply(context, views)
		} else {
			views.setInt(R.id.widget_settings, "setColorFilter", context.widgetSettingsTint())
		}

		val slotIds = intArrayOf(R.id.widget_slot_1, R.id.widget_slot_2, R.id.widget_slot_3)
		val coverIds = intArrayOf(R.id.widget_cover_1, R.id.widget_cover_2, R.id.widget_cover_3)
		val placeholderIds = intArrayOf(R.id.widget_placeholder_1, R.id.widget_placeholder_2, R.id.widget_placeholder_3)

		if (pinnedIds.isEmpty()) {
			// No pins yet — show all 3 slots as "+" placeholders. Tap any → open config.
			views.setViewVisibility(R.id.widget_empty_hint, View.VISIBLE)
			for (i in slotIds.indices) {
				views.setViewVisibility(slotIds[i], View.VISIBLE)
				views.setViewVisibility(coverIds[i], View.GONE)
				views.setViewVisibility(placeholderIds[i], View.VISIBLE)
				views.setOnClickPendingIntent(slotIds[i], configIntent(context, widgetId))
			}
			return views
		}

		views.setViewVisibility(R.id.widget_empty_hint, View.GONE)
		for (i in slotIds.indices) {
			val id = pinnedIds.getOrNull(i)
			if (id == null) {
				// Slot unused — hide entirely so the remaining covers expand to fill.
				views.setViewVisibility(slotIds[i], View.GONE)
				continue
			}
			views.setViewVisibility(slotIds[i], View.VISIBLE)
			val cover = covers[id]
			if (cover != null) {
				views.setImageViewBitmap(coverIds[i], cover)
				views.setViewVisibility(coverIds[i], View.VISIBLE)
				views.setViewVisibility(placeholderIds[i], View.GONE)
			} else {
				views.setImageViewResource(coverIds[i], R.drawable.ic_widget_cover_placeholder)
				views.setViewVisibility(coverIds[i], View.VISIBLE)
				views.setViewVisibility(placeholderIds[i], View.GONE)
			}
			views.setOnClickPendingIntent(slotIds[i], openMangaIntent(context, id, mangaById[id]))
		}
		return views
	}

	private fun openMangaIntent(context: Context, mangaId: Long, manga: Manga?): PendingIntent {
		val intent = if (manga != null) {
			AppRouter.detailsIntent(context, manga)
		} else {
			AppRouter.detailsIntent(context, mangaId)
		}
		intent.addFlags(WidgetIntents.FRESH_LAUNCH_FLAGS)
		return PendingIntent.getActivity(
			context,
			("fav$mangaId").hashCode(),
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
		)
	}

	private fun configIntent(context: Context, widgetId: Int): PendingIntent {
		val intent = Intent(context, FavoritesWidgetConfigActivity::class.java)
			.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
			.putExtra(FavoritesWidgetConfigActivity.EXTRA_RECONFIGURE, true)
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
		return PendingIntent.getActivity(
			context,
			("cfg$widgetId").hashCode(),
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
		)
	}

	companion object {
		private const val TAG = "FavoritesWidget"
		const val ACTION_REFRESH = "org.koitharu.kotatsu.widget.favorites.REFRESH"

		fun nudgeAll(context: Context) = nudgeWidgets(context, FavoritesWidget::class.java)
	}
}

private fun Context.widgetSettingsTint(): Int {
	val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
	return if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
		Color.rgb(199, 198, 202)
	} else {
		Color.BLACK
	}
}
