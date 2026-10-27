package org.koitharu.kotatsu.core.os

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ShortcutManager
import android.os.PersistableBundle
import androidx.annotation.VisibleForTesting
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.room.InvalidationTracker
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Scale
import coil3.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.db.TABLE_HISTORY
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.favicon.faviconUri
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.image.ThumbnailTransformation
import org.koitharu.kotatsu.core.util.ext.getDrawableOrThrow
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.core.util.ext.stableMangaCoverKey
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

private const val EXTRA_ICON_COVER = "icon_cover"

@Singleton
class AppShortcutManager @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val coil: ImageLoader,
	private val historyRepository: HistoryRepository,
	private val mangaRepository: MangaDataRepository,
	private val settings: AppSettings,
) : InvalidationTracker.Observer(TABLE_HISTORY), SharedPreferences.OnSharedPreferenceChangeListener {

	private val iconSize by lazy {
		Size(ShortcutManagerCompat.getIconMaxWidth(context), ShortcutManagerCompat.getIconMaxHeight(context))
	}
	private var shortcutsUpdateJob: Job? = null

	init {
		settings.subscribe(this)
	}

	override fun onInvalidated(tables: Set<String>) {
		if (!settings.isDynamicShortcutsEnabled) {
			return
		}
		val prevJob = shortcutsUpdateJob
		shortcutsUpdateJob = processLifecycleScope.launch(Dispatchers.Default) {
			prevJob?.join()
			updateShortcutsImpl()
		}
	}

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		if (key == AppSettings.KEY_SHORTCUTS) {
			if (settings.isDynamicShortcutsEnabled) {
				onInvalidated(emptySet())
			} else {
				clearShortcuts()
			}
		}
	}

	suspend fun requestPinShortcut(manga: Manga): Boolean = try {
		ShortcutManagerCompat.requestPinShortcut(context, buildShortcutInfo(manga), null)
	} catch (e: IllegalStateException) {
		e.printStackTraceDebug()
		false
	}

	suspend fun requestPinShortcut(source: MangaSource): Boolean = try {
		ShortcutManagerCompat.requestPinShortcut(context, buildShortcutInfo(source), null)
	} catch (e: IllegalStateException) {
		e.printStackTraceDebug()
		false
	}

	fun getMangaShortcuts(): Set<Long> {
		val shortcuts = ShortcutManagerCompat.getShortcuts(
			context,
			ShortcutManagerCompat.FLAG_MATCH_CACHED or ShortcutManagerCompat.FLAG_MATCH_PINNED or ShortcutManagerCompat.FLAG_MATCH_DYNAMIC,
		)
		return shortcuts.mapNotNullToSet { it.id.toLongOrNull() }
	}

	@VisibleForTesting
	suspend fun await(): Boolean {
		return shortcutsUpdateJob?.join() != null
	}

	fun notifyMangaOpened(mangaId: Long) {
		ShortcutManagerCompat.reportShortcutUsed(context, mangaId.toString())
	}

	fun isDynamicShortcutsAvailable(): Boolean {
		return context.getSystemService(ShortcutManager::class.java).maxShortcutCountPerActivity > 0
	}

	/**
	 * Android keeps every icon it is sent as a file until the next reboot (Android 14+), and the reader
	 * rewrites the history every few seconds, so icons are only sent for new shortcuts or changed covers.
	 */
	private suspend fun updateShortcutsImpl() = runCatchingCancellable {
		val maxShortcuts = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtLeast(5)
		val mangaList = historyRepository.getList(0, maxShortcuts)
			.filter { x -> x.title.isNotEmpty() }
		val published = ShortcutManagerCompat.getDynamicShortcuts(context).sortedBy { it.rank }
		val iconCovers = published.associate { it.id to it.extras?.getString(EXTRA_ICON_COVER) }
		val needsIcon = mangaList.filter {
			val id = it.id.toString()
			!iconCovers.containsKey(id) || iconCovers[id] != it.coverUrl?.nullIfEmpty()
		}
		if (needsIcon.isEmpty() &&
			published.map { it.id to it.shortLabel.toString() } == mangaList.map { it.id.toString() to it.shortcutTitle() }
		) {
			return@runCatchingCancellable
		}
		val ids = mangaList.mapTo(HashSet()) { it.id.toString() }
		val stale = iconCovers.keys.filterNot { it in ids }
		if (stale.isNotEmpty()) {
			ShortcutManagerCompat.removeDynamicShortcuts(context, stale)
		}
		val added = needsIcon.map { buildShortcutInfo(it) }
		if (added.isNotEmpty()) {
			ShortcutManagerCompat.addDynamicShortcuts(context, added)
		}
		val covers = iconCovers + added.associate { it.id to it.extras?.getString(EXTRA_ICON_COVER) }
		// Every shortcut gets its rank, so the order follows the history whatever was added or removed
		ShortcutManagerCompat.updateShortcuts(
			context,
			mangaList.mapIndexed { rank, manga ->
				mangaShortcutBuilder(manga, covers[manga.id.toString()]).setRank(rank).build()
			},
		)
	}.onFailure {
		it.printStackTraceDebug()
	}

	private fun clearShortcuts() {
		try {
			ShortcutManagerCompat.removeAllDynamicShortcuts(context)
		} catch (_: IllegalStateException) {
		}
	}

	/** Sends the new picture after a pull-to-refresh replaced the cover behind the same link. */
	suspend fun onCoverRefreshed(manga: Manga) {
		val id = manga.id.toString()
		val shortcut = ShortcutManagerCompat.getShortcuts(
			context,
			ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_PINNED,
		).find { it.id == id } ?: return
		ShortcutManagerCompat.updateShortcuts(context, listOf(buildShortcutInfo(manga, shortcut.rank)))
	}

	private suspend fun buildShortcutInfo(manga: Manga, rank: Int = 0): ShortcutInfoCompat = withContext(Dispatchers.Default) {
		val coverUrl = manga.coverUrl?.nullIfEmpty()
		val cover = if (coverUrl != null) {
			runCatchingCancellable {
				coil.execute(
					ImageRequest.Builder(context)
						.data(coverUrl)
						.size(iconSize)
						.mangaSourceExtra(manga.source)
						.stableMangaCoverKey(manga, coverUrl)
						.scale(Scale.FILL)
						.transformations(ThumbnailTransformation())
						.build(),
				).getDrawableOrThrow().toBitmap()
			}.getOrNull()
		} else {
			null
		}
		mangaRepository.storeManga(manga, replaceExisting = true)
		// A placeholder icon records no cover, so the next update tries the cover again
		mangaShortcutBuilder(manga, if (cover != null) coverUrl else null)
			.setRank(rank)
			.setIcon(
				if (cover != null) {
					IconCompat.createWithAdaptiveBitmap(cover)
				} else {
					IconCompat.createWithResource(context, R.drawable.ic_shortcut_default)
				},
			).build()
	}

	/** [iconCover] is the cover the shortcut's icon was made from. */
	private fun mangaShortcutBuilder(manga: Manga, iconCover: String?): ShortcutInfoCompat.Builder {
		val title = manga.shortcutTitle()
		return ShortcutInfoCompat.Builder(context, manga.id.toString())
			.setShortLabel(title)
			.setLongLabel(title)
			.setLongLived(true)
			.setExtras(PersistableBundle(1).apply { putString(EXTRA_ICON_COVER, iconCover) })
			.setIntent(
				ReaderIntent.Builder(context)
					.mangaId(manga.id)
					.build()
					.intent,
			)
	}

	private fun Manga.shortcutTitle(): String = title.ifEmpty {
		altTitles.firstOrNull()
	}.ifNullOrEmpty {
		context.getString(R.string.unknown)
	}

	private suspend fun buildShortcutInfo(source: MangaSource): ShortcutInfoCompat = withContext(Dispatchers.Default) {
		val icon = runCatchingCancellable {
			coil.execute(
				ImageRequest.Builder(context)
					.data(source.faviconUri())
					.mangaSourceExtra(source)
					.size(iconSize)
					.scale(Scale.FIT)
					.build(),
			).getDrawableOrThrow().toBitmap()
		}.fold(
			onSuccess = { IconCompat.createWithAdaptiveBitmap(it) },
			onFailure = { IconCompat.createWithResource(context, R.drawable.ic_shortcut_default) },
		)
		val title = source.getTitle(context)
		ShortcutInfoCompat.Builder(context, source.name)
			.setShortLabel(title)
			.setLongLabel(title)
			.setIcon(icon)
			.setLongLived(true)
			.setIntent(AppRouter.listIntent(context, source, null, null))
			.build()
	}
}
