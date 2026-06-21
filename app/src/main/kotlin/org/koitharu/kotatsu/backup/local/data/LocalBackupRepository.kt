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
import org.koitharu.kotatsu.backup.local.data.model.BackupIndex
import org.koitharu.kotatsu.backup.local.data.model.BackupPrimitive
import org.koitharu.kotatsu.backup.local.data.model.BookmarkBackup
import org.koitharu.kotatsu.backup.local.data.model.CategoryBackup
import org.koitharu.kotatsu.backup.local.data.model.ChapterBackup
import org.koitharu.kotatsu.backup.local.data.model.FavouriteBackup
import org.koitharu.kotatsu.backup.local.data.model.HistoryBackup
import org.koitharu.kotatsu.backup.local.data.model.MangaBackup
import org.koitharu.kotatsu.backup.local.data.model.MangaWithChaptersBackup
import org.koitharu.kotatsu.backup.local.data.model.ScrobblingBackup
import org.koitharu.kotatsu.backup.local.data.model.SourceBackup
import org.koitharu.kotatsu.backup.local.data.model.SourceSettingsBackup
import org.koitharu.kotatsu.backup.local.data.model.StatsBackup
import org.koitharu.kotatsu.backup.local.domain.BackupSection
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.util.CompositeResult
import org.koitharu.kotatsu.core.util.progress.Progress
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.data.TapGridSettings
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
						val backupEntity = it.toEntity()
						val existing = getHistoryDao().findRaw(backupEntity.mangaId)
						if (existing == null) {
							getHistoryDao().upsert(backupEntity)
						} else {
							val existingTime = maxOf(existing.updatedAt, existing.deletedAt)
							if (backupEntity.updatedAt > existingTime) {
								getHistoryDao().upsert(backupEntity)
							}
						}
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

					null -> CompositeResult.EMPTY
				}
				progress?.emit(commonProgress)
				commonProgress++
			}
			input.closeEntry()
			entry = input.nextEntry
		}
		progress?.emit(commonProgress)
		return result
	}

	suspend fun readIndex(input: ZipInputStream): BackupIndex? {
		var entry = input.nextEntry
		while (entry != null) {
			if (BackupSection.of(entry) == BackupSection.INDEX) {
				val items = input.readJsonArray<BackupIndex>(serializer()).toList()
				return items.firstOrNull()
			}
			input.closeEntry()
			entry = input.nextEntry
		}
		return null
	}

	suspend fun readAvailableSections(input: ZipInputStream): Set<BackupSection> {
		val result = HashSet<BackupSection>()
		var entry = input.nextEntry
		while (entry != null) {
			BackupSection.of(entry)?.let(result::add)
			input.closeEntry()
			entry = input.nextEntry
		}
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
		map.remove(AppSettings.KEY_APP_PASSWORD)
		map.remove(AppSettings.KEY_APP_PASSWORD_NUMERIC)
		map.remove(AppSettings.KEY_PROXY_PASSWORD)
		map.remove(AppSettings.KEY_PROXY_LOGIN)
		map.remove(AppSettings.KEY_INCOGNITO_MODE)
		// Onboarding state is tied to a per-install id; carrying it across devices makes a
		// restored backup look "not onboarded" and re-shows the welcome screen. Never back it up.
		map.remove(AppSettings.KEY_ONBOARDING_COMPLETED)
		map.remove(AppSettings.KEY_ONBOARDING_INSTALL_ID)
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
			// Older backups may still carry the foreign per-install onboarding state; drop it on
			// restore so the current install's onboarding status (and thus no welcome screen) is kept.
			map.remove(AppSettings.KEY_ONBOARDING_COMPLETED)
			map.remove(AppSettings.KEY_ONBOARDING_INSTALL_ID)
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
