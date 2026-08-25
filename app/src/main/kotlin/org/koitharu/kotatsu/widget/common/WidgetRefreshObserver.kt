package org.koitharu.kotatsu.widget.common

import android.content.Context
import androidx.room.InvalidationTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.widget.continuereading.ContinueReadingWidget
import org.koitharu.kotatsu.widget.history.HistoryWidget
import org.koitharu.kotatsu.widget.stats.StatsWidget
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches a small set of tables (`history`, `stats`) and nudges any installed widgets whenever
 * something changes — so the widget reflects the last-read manga within a second of finishing
 * a chapter instead of waiting for the 30-minute update window.
 *
 * Coalescing is implicit: Room batches invalidations and AppWidgetManager dedupes broadcasts.
 */
@Singleton
class WidgetRefreshObserver @Inject constructor(
	@ApplicationContext private val context: Context,
) : InvalidationTracker.Observer(WATCHED_TABLES) {

	override fun onInvalidated(tables: Set<String>) {
		if (HISTORY_TABLES.any { it in tables }) {
			nudgeWidgets(context, ContinueReadingWidget::class.java)
			nudgeWidgets(context, HistoryWidget::class.java)
		}
		if (STATS_TABLES.any { it in tables }) {
			nudgeWidgets(context, StatsWidget::class.java)
		}
	}

	companion object {
		private val HISTORY_TABLES = arrayOf("history")
		private val STATS_TABLES = arrayOf("stats")
		private val WATCHED_TABLES = HISTORY_TABLES + STATS_TABLES
	}
}
