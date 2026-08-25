package org.koitharu.kotatsu.widget.history

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import kotlinx.coroutines.runBlocking
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.widget.common.WidgetCoverLoader
import org.koitharu.kotatsu.widget.common.WidgetEntryPoint
import org.koitharu.kotatsu.widget.common.WidgetTheme
import org.koitharu.kotatsu.widget.common.widgetEntryPoint
import kotlin.math.roundToInt

private const val MAX_ITEMS = 10
private const val ROW_COVER_WIDTH_DP = 40
private const val ROW_COVER_HEIGHT_DP = 54
private const val TILE_COVER_WIDTH_DP = 120
private const val TILE_COVER_HEIGHT_DP = 168

class HistoryWidgetService : RemoteViewsService() {

	override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
		val widgetId = intent.getIntExtra(
			AppWidgetManager.EXTRA_APPWIDGET_ID,
			AppWidgetManager.INVALID_APPWIDGET_ID,
		)
		return HistoryWidgetFactory(
			context = applicationContext,
			isGrid = HistoryWidgetPrefs.isGrid(applicationContext, widgetId),
		)
	}
}

/**
 * Serves the widget's items in either style. Every callback except [onCreate] runs on a binder
 * thread, so the repository reads and cover decoding can block — no second render pass needed.
 */
private class HistoryWidgetFactory(
	private val context: Context,
	private val isGrid: Boolean,
) : RemoteViewsService.RemoteViewsFactory {

	private var items: List<Item> = emptyList()

	override fun onCreate() = Unit

	override fun onDataSetChanged() {
		items = runCatching { loadItems() }.getOrDefault(emptyList())
	}

	override fun onDestroy() {
		items = emptyList()
	}

	override fun getCount() = items.size

	override fun getViewAt(position: Int): RemoteViews {
		val item = items.getOrNull(position) ?: return getLoadingView()
		val views = RemoteViews(context.packageName, itemLayout)
		if (isGrid) {
			views.setContentDescription(R.id.widget_cover, item.title)
		} else {
			views.setTextViewText(R.id.widget_title, item.title)
			views.setTextViewText(
				R.id.widget_subtitle,
				context.getString(R.string.widget_progress_percent, item.percent),
			)
			views.setProgressBar(R.id.widget_progress, 100, item.percent, false)
		}
		if (item.cover != null) {
			views.setImageViewBitmap(R.id.widget_cover, item.cover)
		} else {
			views.setImageViewResource(R.id.widget_cover, R.drawable.ic_widget_cover_placeholder)
		}
		views.setOnClickFillInIntent(R.id.widget_item_body, fillIn(item.mangaId, play = false))
		views.setOnClickFillInIntent(R.id.widget_play, fillIn(item.mangaId, play = true))
		WidgetTheme.apply(context, views)
		return views
	}

	private val itemLayout
		get() = if (isGrid) R.layout.item_widget_history_grid else R.layout.item_widget_history

	private fun fillIn(mangaId: Long, play: Boolean) = Intent()
		.putExtra(HistoryWidget.EXTRA_MANGA_ID, mangaId)
		.putExtra(HistoryWidget.EXTRA_PLAY, play)

	override fun getLoadingView(): RemoteViews = RemoteViews(context.packageName, itemLayout).also { views ->
		WidgetTheme.apply(context, views)
	}

	override fun getViewTypeCount() = 1

	override fun getItemId(position: Int) = items.getOrNull(position)?.mangaId ?: position.toLong()

	override fun hasStableIds() = true

	private fun loadItems(): List<Item> = runBlocking {
		val entryPoint = context.widgetEntryPoint()
		val coverWidth = WidgetCoverLoader.dpToPx(
			context,
			if (isGrid) TILE_COVER_WIDTH_DP else ROW_COVER_WIDTH_DP,
		)
		val coverHeight = WidgetCoverLoader.dpToPx(
			context,
			if (isGrid) TILE_COVER_HEIGHT_DP else ROW_COVER_HEIGHT_DP,
		)
		val cornerRadius = WidgetCoverLoader.dpToPx(context, if (isGrid) 14 else 8).toFloat()
		entryPoint.historyRepository.getList(0, MAX_ITEMS).map { manga ->
			Item(
				mangaId = manga.id,
				title = manga.title,
				percent = if (isGrid) 0 else percentOf(manga, entryPoint),
				cover = WidgetCoverLoader.load(
					context = context,
					loader = entryPoint.imageLoader,
					manga = manga,
					targetWidth = coverWidth,
					targetHeight = coverHeight,
					cornerRadiusPx = cornerRadius,
				),
			)
		}
	}

	private suspend fun percentOf(
		manga: Manga,
		entryPoint: WidgetEntryPoint,
	): Int {
		val percent = entryPoint.historyRepository.getOne(manga)?.percent ?: 0f
		return (percent.coerceIn(0f, 1f) * 100f).roundToInt()
	}

	private class Item(
		val mangaId: Long,
		val title: String,
		val percent: Int,
		val cover: Bitmap?,
	)
}
