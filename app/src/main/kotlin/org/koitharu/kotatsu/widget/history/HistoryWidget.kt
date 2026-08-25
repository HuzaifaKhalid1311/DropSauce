package org.koitharu.kotatsu.widget.history

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.widget.common.WidgetIntents
import org.koitharu.kotatsu.widget.common.runAsync
import org.koitharu.kotatsu.widget.common.widgetEntryPoint

/**
 * The last read titles, in one of two styles picked per widget instance: a cover grid (default) or
 * a list of rows. Items are served by [HistoryWidgetService]; taps are routed back here as a
 * broadcast because collection children can only carry fill-in intents, not their own
 * PendingIntents.
 */
class HistoryWidget : AppWidgetProvider() {

	override fun onUpdate(
		context: Context,
		appWidgetManager: AppWidgetManager,
		appWidgetIds: IntArray,
	) {
		for (widgetId in appWidgetIds) {
			val isGrid = HistoryWidgetPrefs.isGrid(context, widgetId)
			appWidgetManager.updateAppWidget(widgetId, buildViews(context, widgetId, isGrid))
			appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, collectionId(isGrid))
		}
	}

	override fun onDeleted(context: Context, appWidgetIds: IntArray) {
		super.onDeleted(context, appWidgetIds)
		for (widgetId in appWidgetIds) {
			HistoryWidgetPrefs.clear(context, widgetId)
		}
	}

	override fun onReceive(context: Context, intent: Intent) {
		super.onReceive(context, intent)
		when (intent.action) {
			ACTION_CLICK -> openItem(
				context = context,
				mangaId = intent.getLongExtra(EXTRA_MANGA_ID, 0L),
				play = intent.getBooleanExtra(EXTRA_PLAY, false),
			)

			ACTION_REFRESH,
			Intent.ACTION_BOOT_COMPLETED,
			Intent.ACTION_CONFIGURATION_CHANGED,
			Intent.ACTION_MY_PACKAGE_REPLACED -> nudgeAll(context)
		}
	}

	private fun openItem(context: Context, mangaId: Long, play: Boolean) {
		if (mangaId == 0L) return
		runAsync(context, TAG) { appContext ->
			val entryPoint = appContext.widgetEntryPoint()
			val manga = runCatching {
				entryPoint.database.getMangaDao().find(mangaId)?.toManga()
			}.getOrNull()
			val target = when {
				manga == null -> AppRouter.detailsIntent(appContext, mangaId)
					.addFlags(WidgetIntents.FRESH_LAUNCH_FLAGS)

				play -> WidgetIntents.readerIntent(
					context = appContext,
					manga = manga,
					history = entryPoint.historyRepository.getOne(manga),
				)

				else -> AppRouter.detailsIntent(appContext, manga)
					.addFlags(WidgetIntents.FRESH_LAUNCH_FLAGS)
			}
			appContext.startActivity(target)
		}
	}

	private fun buildViews(context: Context, widgetId: Int, isGrid: Boolean): RemoteViews {
		val layout = if (isGrid) R.layout.widget_history_grid else R.layout.widget_history
		val collectionId = collectionId(isGrid)
		val views = RemoteViews(context.packageName, layout)
		val adapterIntent = Intent(context, HistoryWidgetService::class.java)
			.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
			// A distinct data uri per widget keeps the framework from sharing one factory
			// instance (and thus one stale item set) between several pinned widgets; the style is
			// part of it so switching styles rebuilds the items instead of reusing the old ones.
			.setData(Uri.parse("kotatsu://widget/history/$widgetId/${if (isGrid) "grid" else "list"}"))
		views.setRemoteAdapter(collectionId, adapterIntent)
		views.setEmptyView(collectionId, R.id.widget_empty)
		views.setPendingIntentTemplate(collectionId, clickTemplate(context, widgetId))
		return views
	}

	private fun collectionId(isGrid: Boolean) = if (isGrid) R.id.widget_grid else R.id.widget_list

	private fun clickTemplate(context: Context, widgetId: Int): PendingIntent {
		val intent = Intent(context, HistoryWidget::class.java).setAction(ACTION_CLICK)
		return PendingIntent.getBroadcast(
			context,
			widgetId,
			intent,
			// MUTABLE is required: the item's fill-in intent supplies the manga id and the
			// play/open flag on top of this template.
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
		)
	}

	companion object {
		private const val TAG = "HistoryWidget"
		const val ACTION_REFRESH = "org.koitharu.kotatsu.widget.history.REFRESH"
		const val ACTION_CLICK = "org.koitharu.kotatsu.widget.history.CLICK"
		const val EXTRA_MANGA_ID = "manga_id"
		const val EXTRA_PLAY = "play"

		fun nudge(context: Context, widgetId: Int) {
			val broadcast = Intent(context, HistoryWidget::class.java)
				.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
				.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
			context.sendBroadcast(broadcast)
		}

		fun nudgeAll(context: Context) {
			val mgr = AppWidgetManager.getInstance(context)
			val ids = mgr.getAppWidgetIds(ComponentName(context, HistoryWidget::class.java))
			if (ids.isEmpty()) return
			val broadcast = Intent(context, HistoryWidget::class.java)
				.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
				.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
			context.sendBroadcast(broadcast)
		}
	}
}
