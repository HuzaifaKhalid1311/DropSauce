package org.koitharu.kotatsu.backup.local.data

import android.content.Context
import androidx.core.content.edit
import androidx.room.withTransaction
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.serializer
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backup.local.data.model.BackupIndex
import org.koitharu.kotatsu.backup.local.data.model.BackupPrimitive
import org.koitharu.kotatsu.backup.local.data.model.BookmarkBackup
import org.koitharu.kotatsu.backup.local.data.model.CategoryBackup
import org.koitharu.kotatsu.backup.local.data.model.ChapterBackup
import org.koitharu.kotatsu.backup.local.data.model.FavouriteBackup
import org.koitharu.kotatsu.backup.local.data.model.FeedBackup
import org.koitharu.kotatsu.backup.local.data.model.HistoryBackup
import org.koitharu.kotatsu.backup.local.data.model.MangaBackup
import org.koitharu.kotatsu.backup.local.data.model.MangaPrefsBackup
import org.koitharu.kotatsu.backup.local.data.model.MangaWithChaptersBackup
import org.koitharu.kotatsu.backup.local.data.model.ScrobblingBackup
import org.koitharu.kotatsu.backup.local.data.model.SourceBackup
import org.koitharu.kotatsu.backup.local.data.model.SourceSettingsBackup
import org.koitharu.kotatsu.backup.local.data.model.StatsBackup
import org.koitharu.kotatsu.backup.local.domain.BackupSection
import org.koitharu.kotatsu.backup.local.domain.CustomCoverCodec
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.util.CompositeResult
import org.koitharu.kotatsu.core.util.progress.Progress
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.data.TapGridSettings
import org.koitharu.kotatsu.sync.data.model.SyncFeedEntry
import org.koitharu.kotatsu.sync.data.model.SyncMangaPrefs
import org.koitharu.kotatsu.sync.data.model.SyncTrack
import org.koitharu.kotatsu.sync.domain.SyncMerger
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

@Reusable
class LocalBackupRepository @Inject constructor(
	@ApplicationContext private val context: Context,
	private val database: MangaDatabase,
	private val settings: AppSettings,
	private val tapGridSettings: TapGridSettings,
	private val coverCodec: CustomCoverCodec,
) {

	private val json = Json {
		allowSpecialFloatingPointValues = true
		coerceInputValues = true
		encodeDefaults = true
		ignoreUnknownKeys = true
		useAlternativeNames = false
		classDiscriminator = "_t"
	}

	suspend fun createBackup(
		output: ZipOutputStream,
		progress: FlowCollector<Progress>?,
	) {
		val sections = BackupSection.entries
		progress?.emit(Progress.INDETERMINATE)
		var commonProgress = Progress(0, sections.size)
		for (section in sections) {
			when (section) {
				BackupSection.INDEX -> output.writeJsonArray(
					section = BackupSection.INDEX,
					data = flowOf(BackupIndex()),
					serializer = serializer(),
				)

				BackupSection.HISTORY -> output.writeJsonArray(
					section = BackupSection.HISTORY,
					data = database.getHistoryDao().dump().map(::HistoryBackup),
					serializer = serializer(),
				)

				BackupSection.CATEGORIES -> output.writeJsonArray(
					section = BackupSection.CATEGORIES,
					data = database.getFavouriteCategoriesDao().findAll().asFlow().map(::CategoryBackup),
					serializer = serializer(),
				)

				BackupSection.FAVOURITES -> output.writeJsonArray(
					section = BackupSection.FAVOURITES,
					data = database.getFavouritesDao().dump().map(::FavouriteBackup),
					serializer = serializer(),
				)

				BackupSection.BOOKMARKS -> output.writeJsonArray(
					section = BackupSection.BOOKMARKS,
					data = database.getBookmarksDao().dump().map { (manga, bookmarks) ->
						BookmarkBackup(manga, bookmarks)
					},
					serializer = serializer(),
				)

				BackupSection.SETTINGS -> output.writeJsonObject(
					section = BackupSection.SETTINGS,
					data = dumpAppSettings(),
					serializer = serializer(),
				)

				BackupSection.SETTINGS_READER_GRID -> output.writeJsonObject(
					section = BackupSection.SETTINGS_READER_GRID,
					data = dumpReaderGridSettings(),
					serializer = serializer(),
				)

				BackupSection.SOURCES -> output.writeJsonArray(
					section = BackupSection.SOURCES,
					data = database.getSourcesDao().dumpEnabled().map(::SourceBackup),
					serializer = serializer(),
				)

				BackupSection.SOURCE_SETTINGS -> output.writeJsonArray(
					section = BackupSection.SOURCE_SETTINGS,
					data = dumpSourceSettings().asFlow(),
					serializer = serializer(),
				)

				BackupSection.SCROBBLING -> output.writeJsonArray(
					section = BackupSection.SCROBBLING,
					data = database.getScrobblingDao().dumpEnabled().map(::ScrobblingBackup),
					serializer = serializer(),
				)

				BackupSection.STATS -> output.writeJsonArray(
					section = BackupSection.STATS,
					data = database.getStatsDao().dumpEnabled().map(::StatsBackup),
					serializer = serializer(),
				)

				BackupSection.CHAPTERS -> output.writeJsonArray(
					section = BackupSection.CHAPTERS,
					data = dumpMangaChapters(),
					serializer = serializer(),
				)

				BackupSection.FEED -> output.writeJsonObject(
					section = BackupSection.FEED,
					data = dumpFeed(),
					serializer = serializer(),
				)

				BackupSection.MANGA_PREFS -> output.writeJsonArray(
					section = BackupSection.MANGA_PREFS,
					data = dumpMangaPrefs(),
					serializer = serializer(),
				)
			}
			progress?.emit(commonProgress)
			commonProgress++
		}
		progress?.emit(commonProgress)
	}

	suspend fun restoreBackup(
		input: ZipInputStream,
		sections: Set<BackupSection>,
		progress: FlowCollector<Progress>?,
	): CompositeResult {
		progress?.emit(Progress.INDETERMINATE)
		var commonProgress = Progress(0, sections.size)
		var entry = input.nextEntry
		var result = CompositeResult.EMPTY
		while (entry != null) {
			val section = BackupSection.of(entry)
			if (section in sections) {
				result += when (section) {
					BackupSection.INDEX -> CompositeResult.EMPTY

					BackupSection.HISTORY -> input.readJsonArray<HistoryBackup>(serializer()).restoreToDb {
						upsertMangaBackup(it.manga)
						getHistoryDao().upsert(it.toEntity())
					}

					BackupSection.CATEGORIES -> input.readJsonArray<CategoryBackup>(serializer()).restoreToDb {
						getFavouriteCategoriesDao().upsert(it.toEntity())
					}

					BackupSection.FAVOURITES -> input.readJsonArray<FavouriteBackup>(serializer()).restoreToDb {
						upsertMangaBackup(it.manga)
						getFavouritesDao().upsert(it.toEntity())
					}

					BackupSection.BOOKMARKS -> input.readJsonArray<BookmarkBackup>(serializer()).restoreToDb {
						upsertMangaBackup(it.manga)
						if (it.bookmarks.isNotEmpty()) {
							getBookmarksDao().upsert(it.bookmarks.map { b -> b.toEntity() })
						}
					}

					BackupSection.SETTINGS -> restoreAppSettings(input)
					BackupSection.SETTINGS_READER_GRID -> restoreReaderGridSettings(input)

					BackupSection.SOURCES -> input.readJsonArray<SourceBackup>(serializer()).restoreToDb {
						getSourcesDao().upsert(it.toEntity())
					}

					BackupSection.SOURCE_SETTINGS -> restoreSourceSettings(input)

					BackupSection.SCROBBLING -> input.readJsonArray<ScrobblingBackup>(serializer()).restoreToDb {
						getScrobblingDao().upsert(it.toEntity())
					}

					BackupSection.STATS -> input.readJsonArray<StatsBackup>(serializer()).restoreToDb {
						getStatsDao().upsert(it.toEntity())
					}

					BackupSection.CHAPTERS -> input.readJsonArray<MangaWithChaptersBackup>(serializer())
						.restoreToDb {
							upsertMangaBackup(it.manga)
							getChaptersDao().replaceAll(it.manga.id, it.chapters.map { c -> c.toEntity() })
						}

					BackupSection.FEED -> restoreFeed(input)

					BackupSection.MANGA_PREFS -> restoreMangaPrefs(input)

					null -> CompositeResult.EMPTY
				}
				progress?.emit(commonProgress)
				commonProgress++
			}
			input.closeEntry()
			entry = input.nextEntry
		}
		// Run after the whole archive is applied so favourites are already restored and the
		// emptiness check is accurate.
		if (BackupSection.CATEGORIES in sections) {
			removeEmptyReadLaterCategory()
		}
		progress?.emit(commonProgress)
		return result
	}

	private suspend fun <T> ZipOutputStream.writeJsonArray(
		section: BackupSection,
		data: Flow<T>,
		serializer: SerializationStrategy<T>,
	) {
		data.onStart {
			putNextEntry(ZipEntry(section.entryName))
			write("[")
		}.onCompletion { error ->
			if (error == null) {
				write("]")
			}
			closeEntry()
			flush()
		}.collectIndexed { index, value ->
			if (index > 0) {
				write(",")
			}
			json.encodeToStream(serializer, value, this)
		}
	}

	private fun <T> ZipOutputStream.writeJsonObject(
		section: BackupSection,
		data: T,
		serializer: SerializationStrategy<T>,
	) {
		putNextEntry(ZipEntry(section.entryName))
		try {
			json.encodeToStream(serializer, data, this)
		} finally {
			closeEntry()
			flush()
		}
	}

	private fun <T> InputStream.readJsonArray(
		serializer: DeserializationStrategy<T>,
	): Sequence<T> = json.decodeToSequence(this, serializer, DecodeSequenceMode.ARRAY_WRAPPED)

	private fun OutputStream.write(str: String) = write(str.toByteArray())

	private fun dumpAppSettings(): Map<String, BackupPrimitive> {
		val map = settings.getAllValues().toMutableMap()
		(AppSettings.SENSITIVE_BACKUP_KEYS + AppSettings.DEVICE_LOCAL_KEYS).forEach { map.remove(it) }
		return map.mapNotNullToBackupValues()
	}

	private fun dumpReaderGridSettings(): Map<String, BackupPrimitive> {
		return tapGridSettings.getAllValues().mapNotNullToBackupValues()
	}

	private suspend fun dumpSourceSettings(): List<SourceSettingsBackup> {
		val sources = database.getSourcesDao().findAll()
		val result = ArrayList<SourceSettingsBackup>(sources.size)
		for (source in sources) {
			val prefsName = SourceSettings.getStorageName(source.source)
			val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
			val map = prefs.all.mapNotNullToBackupValues()
			if (map.isNotEmpty()) {
				result += SourceSettingsBackup(source = source.source, values = map)
			}
		}
		return result
	}

	private suspend fun dumpMangaChapters(): Flow<MangaWithChaptersBackup> {
		val dao = database.getMangaDao()
		val chaptersDao = database.getChaptersDao()
		val sources = database.getSourcesDao().findAll().map { it.source }
		val seen = HashSet<Long>()
		return kotlinx.coroutines.flow.flow {
			for (source in sources) {
				val items = dao.findAllBySource(source)
				for (item in items) {
					if (!seen.add(item.manga.id)) continue
					val chapters = chaptersDao.findAll(item.manga.id)
					if (chapters.isEmpty()) continue
					emit(
						MangaWithChaptersBackup(
							manga = MangaBackup(item),
							chapters = chapters.map(::ChapterBackup),
						),
					)
				}
			}
		}
	}

	private suspend fun dumpFeed(): FeedBackup {
		val mangaDao = database.getMangaDao()
		val mangaCache = HashMap<Long, MangaBackup?>()
		suspend fun mangaOf(id: Long): MangaBackup? =
			mangaCache.getOrPut(id) { mangaDao.find(id)?.let(::MangaBackup) }
		val tracks = database.getTracksDao().findAllForSync().mapNotNull { entity ->
			mangaOf(entity.mangaId)?.let { SyncTrack(entity, it) }
		}
		val logs = database.getTrackLogsDao().findAllForSync().mapNotNull { entity ->
			mangaOf(entity.mangaId)?.let { SyncFeedEntry(entity, it) }
		}
		return FeedBackup(tracks = tracks, logs = logs)
	}

	private fun dumpMangaPrefs(): Flow<MangaPrefsBackup> = flow {
		val mangaDao = database.getMangaDao()
		for (entity in database.getPreferencesDao().getOverrides()) {
			val manga = mangaDao.find(entity.mangaId) ?: continue
			val cover = coverCodec.read(entity.coverUrlOverride)
			emit(
				MangaPrefsBackup(
					manga = MangaBackup(manga),
					prefs = SyncMangaPrefs(
						entity = entity,
						coverData = cover?.data,
						coverFileExtension = cover?.extension,
					),
				),
			)
		}
	}

	private suspend fun restoreFeed(input: InputStream): CompositeResult {
		return runCatchingCancellable {
			val backup = json.decodeFromString<FeedBackup>(input.readBytes().decodeToString())
			val logsDao = database.getTrackLogsDao()
			// Local log ids are per-device autoincrement, so match by the stable cross-device
			// identity (manga id + chapter titles) to keep repeated restores from duplicating the feed.
			val existing = logsDao.findAllForSync()
				.mapTo(HashSet()) { SyncMerger.feedIdentity(it.mangaId, it.chapters) }
			database.withTransaction {
				for (track in backup.tracks) {
					database.upsertMangaBackup(track.manga)
					database.getTracksDao().upsert(track.toEntity())
				}
				for (log in backup.logs) {
					if (!existing.add(SyncMerger.feedIdentity(log))) {
						continue
					}
					database.upsertMangaBackup(log.manga)
					logsDao.insert(log.toEntity())
				}
			}
		}.let { CompositeResult.EMPTY + it }
	}

	private suspend fun restoreMangaPrefs(input: InputStream): CompositeResult {
		val items = input.readJsonArray<MangaPrefsBackup>(serializer()).toList()
		return items.fold(CompositeResult.EMPTY) { acc, item ->
			acc + runCatchingCancellable {
				val prefs = item.prefs
				val currentCover = database.getPreferencesDao().find(prefs.mangaId)?.coverUrlOverride
				val resolvedCover = when {
					prefs.coverData != null -> coverCodec.materialize(
						mangaId = prefs.mangaId,
						coverData = prefs.coverData,
						coverFileExtension = prefs.coverFileExtension,
						previousUrl = currentCover,
					) ?: currentCover

					// A local file path from another device is unusable here — keep what we have.
					coverCodec.isPortableCoverUrl(prefs.coverUrlOverride) -> prefs.coverUrlOverride
					else -> currentCover
				}
				database.withTransaction {
					database.upsertMangaBackup(item.manga)
					database.getPreferencesDao().upsert(prefs.toEntity(resolvedCover))
				}
			}
		}
	}

	// The built-in "Read later" category is pre-populated on DB creation and dumped into every
	// backup (even when empty), so a restore always brings it back. An empty read-later carries no
	// data worth keeping, so drop it after restore; a populated one is left untouched.
	private suspend fun removeEmptyReadLaterCategory() {
		runCatchingCancellable {
			val readLaterTitle = context.getString(R.string.read_later)
			val dao = database.getFavouriteCategoriesDao()
			val readLater = dao.findAll().firstOrNull { it.title == readLaterTitle } ?: return
			if (database.getFavouritesDao().findAll(readLater.categoryId.toLong()).isEmpty()) {
				dao.delete(readLater.categoryId.toLong())
			}
		}
	}

	private suspend fun MangaDatabase.upsertMangaBackup(manga: MangaBackup) {
		val tags = manga.tags.map { it.toEntity() }
		if (tags.isNotEmpty()) {
			getTagsDao().upsert(tags)
		}
		getMangaDao().upsert(manga.toEntity(), tags)
	}

	private suspend inline fun <T> Sequence<T>.restoreToDb(
		crossinline block: suspend MangaDatabase.(T) -> Unit,
	): CompositeResult = fold(CompositeResult.EMPTY) { acc, item ->
		acc + runCatchingCancellable {
			database.withTransaction { database.block(item) }
		}
	}

	private fun restoreAppSettings(input: InputStream): CompositeResult {
		return runCatchingCancellable {
			val map = json.decodeFromString<Map<String, BackupPrimitive>>(input.readBytes().decodeToString())
				.toMutableMap()
			// Older backups may still carry app-lock state or per-install onboarding ids; drop them
			// on restore so a backup never brings an app lock or the welcome screen to this device.
			(AppSettings.SENSITIVE_BACKUP_KEYS + AppSettings.DEVICE_LOCAL_KEYS).forEach { map.remove(it) }
			settings.upsertAll(map.toRawMap())
		}.let { CompositeResult.EMPTY + it }
	}

	private fun restoreReaderGridSettings(input: InputStream): CompositeResult {
		return runCatchingCancellable {
			val map = json.decodeFromString<Map<String, BackupPrimitive>>(input.readBytes().decodeToString())
			tapGridSettings.upsertAll(map.toRawMap())
		}.let { CompositeResult.EMPTY + it }
	}

	private fun restoreSourceSettings(input: InputStream): CompositeResult {
		val list = input.readJsonArray<SourceSettingsBackup>(serializer()).toList()
		return list.fold(CompositeResult.EMPTY) { acc, entry ->
			acc + runCatchingCancellable {
				val prefsName = SourceSettings.getStorageName(entry.source)
				val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
				prefs.edit {
					entry.values.forEach { (key, primitive) ->
						when (primitive) {
							is BackupPrimitive.StringValue -> putString(key, primitive.value)
							is BackupPrimitive.BoolValue -> putBoolean(key, primitive.value)
							is BackupPrimitive.IntValue -> putInt(key, primitive.value)
							is BackupPrimitive.LongValue -> putLong(key, primitive.value)
							is BackupPrimitive.FloatValue -> putFloat(key, primitive.value)
							is BackupPrimitive.StringSetValue -> putStringSet(key, primitive.value)
						}
					}
				}
			}
		}
	}

	private fun Map<String, *>.mapNotNullToBackupValues(): Map<String, BackupPrimitive> {
		val out = LinkedHashMap<String, BackupPrimitive>(size)
		for ((key, value) in this) {
			BackupPrimitive.of(value)?.let { out[key] = it }
		}
		return out
	}

	private fun Map<String, BackupPrimitive>.toRawMap(): Map<String, Any?> {
		val out = LinkedHashMap<String, Any?>(size)
		for ((key, value) in this) {
			out[key] = value.rawValue()
		}
		return out
	}
}
