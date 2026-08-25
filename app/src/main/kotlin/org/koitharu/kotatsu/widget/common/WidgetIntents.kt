package org.koitharu.kotatsu.widget.common

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.reader.ui.ReaderState

object WidgetIntents {

	private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

	/**
	 * Flags applied to every Intent launched from a widget. CLEAR_TASK | NEW_TASK together
	 * drop the user's previous task stack so opening from a widget always feels like a fresh
	 * launch, regardless of whatever was on the back stack from a prior session.
	 */
	const val FRESH_LAUNCH_FLAGS =
		Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

	/** Reader intent resuming at [history], ready to be launched or wrapped in a PendingIntent. */
	fun readerIntent(
		context: Context,
		manga: Manga,
		history: MangaHistory?,
	): Intent {
		val builder = ReaderIntent.Builder(context).manga(manga)
		if (history != null) {
			builder.state(
				ReaderState(
					chapterId = history.chapterId,
					page = history.page,
					scroll = history.scroll,
				),
			)
		}
		return builder.build().intent.addFlags(FRESH_LAUNCH_FLAGS)
	}

	fun continueReading(
		context: Context,
		manga: Manga,
		history: MangaHistory?,
	): PendingIntent {
		val intent = readerIntent(context, manga, history)
		return PendingIntent.getActivity(context, ("read" + manga.id).hashCode(), intent, PI_FLAGS)
	}

	fun openActivity(context: Context, intent: Intent, requestCode: Int): PendingIntent {
		intent.addFlags(FRESH_LAUNCH_FLAGS)
		return PendingIntent.getActivity(context, requestCode, intent, PI_FLAGS)
	}
}
