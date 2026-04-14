package org.koitharu.kotatsu.backup

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import okio.buffer
import okio.gzip
import okio.sink
import okio.source
import org.koitharu.kotatsu.backup.model.MihonBackup
import org.koitharu.kotatsu.backup.model.MihonBackupFallback
import org.koitharu.kotatsu.backup.model.MihonBackupCategory
import org.koitharu.kotatsu.backup.model.MihonBackupChapter
import org.koitharu.kotatsu.backup.model.MihonBackupExtensionRepo
import org.koitharu.kotatsu.backup.model.MihonBackupHistory
import org.koitharu.kotatsu.backup.model.MihonBackupManga
import org.koitharu.kotatsu.backup.model.MihonBackupPreference
import org.koitharu.kotatsu.backup.model.MihonBackupSource
import org.koitharu.kotatsu.backup.model.MihonBackupSourcePreferences
import org.koitharu.kotatsu.backup.model.MihonBackupTracking
import org.koitharu.kotatsu.backup.model.MihonBooleanPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonFloatPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonIntPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonLongPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonStringPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonStringSetPreferenceValue
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.TagEntity
import org.koitharu.kotatsu.core.exceptions.BadBackupFormatException
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.parsers.util.longHashCode
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.stats.data.StatsEntity
import java.net.URI
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MihonBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: MangaDatabase,
    private val settings: AppSettings,
    private val mihonExtensionManager: MihonExtensionManager,
) {

  data class Options(
    val libraryEntries: Boolean = true,
    val appSettings: Boolean = true,
    val sourceSettings: Boolean = true,
    val extensionRepoSettings: Boolean = true,
    val tracking: Boolean = true,
  )

  data class RestoreReport(
    val restoredMangaCount: Int,
    val restoredTrackingCount: Int,
    val missingSources: List<String>,
    val missingTrackers: List<Int>,
    val skippedItems: List<String>,
  )

  private class RestoreAccumulator(
    missingSources: List<String>,
    missingTrackers: List<Int>,
  ) {
    val missingSources = missingSources.toMutableSet()
    val missingTrackers = missingTrackers.toMutableSet()
    val skippedItems = mutableListOf<String>()
    var restoredMangaCount = 0
    var restoredTrackingCount = 0

    fun toReport() = RestoreReport(
      restoredMangaCount = restoredMangaCount,
      restoredTrackingCount = restoredTrackingCount,
      missingSources = missingSources.sorted(),
      missingTrackers = missingTrackers.sorted(),
      skippedItems = skippedItems.toList(),
    )
  }

  private data class PendingRestore(
    val manga: MangaEntity,
    val tags: List<TagEntity>,
    val chapters: List<ChapterEntity>,
    val favourites: List<FavouriteEntity>,
    val history: HistoryEntity?,
    val stats: StatsEntity?,
    val bookmarks: List<BookmarkEntity>,
    val scrobblings: List<ScrobblingEntity>,
  )

  private data class CategoryRestoreMapping(
    val defaultCategoryId: Long,
  )

    private val proto = ProtoBuf

    suspend fun analyzeBackup(uri: Uri, options: Options = Options()): RestoreReport = withContext(Dispatchers.IO) {
        val backup = decode(uri)
        buildDiagnostics(backup, options).toReport()
    }

    suspend fun restoreBackup(uri: Uri, options: Options = Options()): RestoreReport {
        return withContext(Dispatchers.IO) {
            val backup = decode(uri)
            val diagnostics = buildDiagnostics(backup, options)
            db.withTransaction {
                val restoredCategories = if (options.libraryEntries) {
                    restoreCategories()
                } else {
                    null
                }
                if (options.libraryEntries) {
                    restoreManga(backup, options, diagnostics, restoredCategories)
                }
                if (options.appSettings) {
                    restorePreferences(backup.backupPreferences)
                }
                if (options.sourceSettings) {
                    restoreSourcePreferences(backup.backupSourcePreferences, diagnostics)
                }
                if (options.extensionRepoSettings) {
                    restoreExtensionRepo(backup.backupExtensionRepo)
                }
            }
            diagnostics.toReport()
        }
    }

  private fun buildDiagnostics(backup: MihonBackup, options: Options): RestoreAccumulator {
    val missingSources = if (options.libraryEntries) {
      backup.backupSources
        .filter { it.sourceId > 0L && mihonExtensionManager.getMihonMangaSourceById(it.sourceId) == null }
        .map { source -> source.name.ifBlank { source.sourceId.toString() } }
    } else {
      emptyList()
    }
    val supportedTrackerIds = setOf(1, 2, 3, 4)
    val missingTrackers = if (options.tracking) {
      backup.backupManga
        .asSequence()
        .flatMap { it.tracking.asSequence() }
        .map { it.syncId }
        .filter { it !in supportedTrackerIds }
        .distinct()
        .toList()
    } else {
      emptyList()
    }
    return RestoreAccumulator(
      missingSources = missingSources,
      missingTrackers = missingTrackers,
    )
  }

    private fun decode(uri: Uri): MihonBackup {
        val payload = context.contentResolver.openInputStream(uri)?.use { input ->
            val source = input.source().buffer()
            val peeked = source.peek().apply { require(2) }
            val signature = peeked.readShort().toInt()
            when (signature) {
                0x1f8b -> source.gzip().buffer().readByteArray()
                else -> source.readByteArray()
            }
        } ?: throw BadBackupFormatException(null)

        return try {
            proto.decodeFromByteArray(MihonBackup.serializer(), payload)
        } catch (strictError: SerializationException) {
            runCatching {
                decodeFallback(payload)
            }.getOrElse { fallbackError ->
                strictError.addSuppressed(fallbackError)
                throw BadBackupFormatException(strictError)
            }
        }
    }

    private fun decodeFallback(payload: ByteArray): MihonBackup {
        val fallback = proto.decodeFromByteArray(MihonBackupFallback.serializer(), payload)
        return MihonBackup(
            backupManga = fallback.backupManga,
            backupCategories = fallback.backupCategories,
            backupSources = fallback.backupSources,
            backupPreferences = emptyList(),
            backupSourcePreferences = emptyList(),
            backupExtensionRepo = fallback.backupExtensionRepo,
        )
    }

    private suspend fun restoreCategories(): CategoryRestoreMapping {
        val dao = db.getFavouriteCategoriesDao()
        val defaultCategoryId = dao.insert(
            FavouriteCategoryEntity(
                categoryId = 0,
                createdAt = System.currentTimeMillis(),
                sortKey = dao.getNextSortKey(),
                title = "mihon",
                order = "NEWEST",
                track = true,
                isVisibleInLibrary = true,
                deletedAt = 0,
            ),
        )
        return CategoryRestoreMapping(
            defaultCategoryId = defaultCategoryId,
        )
    }

    private suspend fun restoreManga(
        backup: MihonBackup,
        options: Options,
        diagnostics: RestoreAccumulator,
        categoryMapping: CategoryRestoreMapping?,
    ) {
        val defaultCategoryId = categoryMapping?.defaultCategoryId
        val supportedTrackerIds = setOf(1, 2, 3, 4)
        val now = System.currentTimeMillis()

        val pending = backup.backupManga.map { item ->
            val sourceName = resolveStoredSourceName(item.source, backup.backupSources)
            val mangaId = "$sourceName:${item.url}".longHashCode()
            val tags = item.genre.mapNotNull { title ->
                val clean = title.trim()
                if (clean.isBlank()) {
                    null
                } else {
                    TagEntity(
                        id = "${clean.lowercase(Locale.ROOT)}:$sourceName".longHashCode(),
                        title = clean,
                        key = clean.lowercase(Locale.ROOT),
                        source = sourceName,
                        isPinned = false,
                    )
                }
            }
            val chapters = item.chapters.mapIndexed { index, chapter ->
                ChapterEntity(
                    chapterId = "$mangaId:${chapter.url}".longHashCode(),
                    mangaId = mangaId,
                    title = chapter.name,
                    number = chapter.chapterNumber,
                    volume = 0,
                    url = chapter.url,
                    scanlator = chapter.scanlator,
                    uploadDate = chapter.dateUpload,
                    branch = null,
                    source = sourceName,
                    index = chapter.sourceOrder.toInt().takeIf { it >= 0 } ?: index,
                )
            }
            val categoryIds = if (item.favorite) {
                listOfNotNull(defaultCategoryId)
            } else {
                emptyList()
            }
            val favourites = categoryIds.mapIndexed { sortIndex, categoryId ->
                FavouriteEntity(
                    mangaId = mangaId,
                    categoryId = categoryId,
                    sortKey = sortIndex,
                    isPinned = false,
                    createdAt = item.dateAdded.takeIf { it > 0 } ?: now,
                    deletedAt = 0,
                )
            }
            val chapterByUrl = chapters.associateBy { it.url }
            val bookmarks = item.chapters.asSequence()
                .filter { it.bookmark }
                .mapNotNull { chapter ->
                    val chapterEntity = chapterByUrl[chapter.url] ?: return@mapNotNull null
                    val page = chapter.lastPageRead.toInt().coerceAtLeast(0)
                    BookmarkEntity(
                        mangaId = mangaId,
                        pageId = "$mangaId:${chapter.url}:$page".longHashCode(),
                        chapterId = chapterEntity.chapterId,
                        page = page,
                        scroll = 0,
                        imageUrl = "",
                        createdAt = now,
                        percent = 0f,
                    )
                }
                .toList()

            val historyItem = item.history.maxByOrNull { it.lastRead }
            val fallbackReadChapter = item.chapters.withIndex().lastOrNull { it.value.read }
            val historyChapterUrl = historyItem?.url ?: fallbackReadChapter?.value?.url
            val historyChapter = historyChapterUrl?.let(chapterByUrl::get)
                ?: fallbackReadChapter?.let { chapters.getOrNull(it.index) }
            val history = if (historyChapter != null) {
                val backupChapter = item.chapters.firstOrNull { it.url == historyChapter.url }
                val restoredPage = backupChapter?.lastPageRead?.toInt()?.coerceAtLeast(0) ?: 0
                val chapterIndex = chapters.indexOfFirst { it.chapterId == historyChapter.chapterId }
                    .takeIf { it >= 0 }
                    ?: 0
                val updatedAt = historyItem?.lastRead?.takeIf { it > 0 }
                    ?: item.dateAdded.takeIf { it > 0 }
                    ?: now
                HistoryEntity(
                    mangaId = mangaId,
                    createdAt = updatedAt,
                    updatedAt = updatedAt,
                    chapterId = historyChapter.chapterId,
                    page = restoredPage,
                    scroll = 0f,
                    percent = computeHistoryPercent(
                        chapterIndex = chapterIndex,
                        chaptersCount = chapters.size,
                        isChapterMarkedRead = backupChapter?.read == true,
                        lastPageRead = restoredPage,
                    ),
                    deletedAt = 0,
                    chaptersCount = chapters.size,
                )
            } else {
                null
            }
            val stats = if ((historyItem?.readDuration ?: 0L) > 0L && history != null) {
                StatsEntity(
                    mangaId = mangaId,
                    startedAt = (history.updatedAt - historyItem!!.readDuration).coerceAtLeast(0L),
                    duration = historyItem.readDuration,
                    pages = (history.page + 1).coerceAtLeast(1),
                )
            } else {
                null
            }
            val scrobblings = if (options.tracking) {
                item.tracking.mapNotNull { tracking ->
                    if (tracking.syncId !in supportedTrackerIds) {
                        diagnostics.missingTrackers += tracking.syncId
                        return@mapNotNull null
                    }
                    val targetId = tracking.mediaId.takeIf { it > 0 }
                        ?: tracking.mediaIdInt.toLong().takeIf { it > 0 }
                        ?: return@mapNotNull null
                    val remoteEntryId = tracking.libraryId.toInt().takeIf { it > 0 }
                        ?: targetId.toInt().takeIf { it > 0 }
                        ?: return@mapNotNull null
                    ScrobblingEntity(
                        scrobbler = tracking.syncId,
                        id = remoteEntryId,
                        mangaId = mangaId,
                        targetId = targetId,
                        status = decodeTrackingStatus(tracking.status),
                        chapter = tracking.lastChapterRead.toInt().coerceAtLeast(0),
                        comment = null,
                        rating = tracking.score,
                    )
                }
            } else {
                emptyList()
            }

            PendingRestore(
                manga = MangaEntity(
                    id = mangaId,
                    title = item.title,
                    altTitles = null,
                    url = item.url,
                    publicUrl = item.url,
                    rating = -1f,
                    isNsfw = false,
                    contentRating = null,
                    coverUrl = item.thumbnailUrl.orEmpty(),
                    largeCoverUrl = null,
                    state = null,
                    authors = item.author,
                    source = sourceName,
                    sourceTitle = resolveSourceTitle(item.source, backup.backupSources),
                ),
                tags = tags,
                chapters = chapters,
                favourites = favourites,
                history = history,
                stats = stats,
                bookmarks = bookmarks,
                scrobblings = scrobblings,
            )
        }

        pending.flatMapTo(linkedSetOf()) { it.tags }
            .takeIf { it.isNotEmpty() }
            ?.let { db.getTagsDao().upsert(it.toList()) }
        pending.forEach { item -> db.getMangaDao().upsert(item.manga, item.tags) }
        pending.forEach { item -> item.favourites.forEach { db.getFavouritesDao().upsert(it) } }
        pending.forEach { item -> db.getChaptersDao().replaceAll(item.manga.id, item.chapters) }
        pending.forEach { item -> item.history?.let { db.getHistoryDao().upsert(it) } }
        pending.forEach { item -> item.stats?.let { db.getStatsDao().upsert(it) } }
        pending.forEach { item ->
            if (item.bookmarks.isNotEmpty()) {
                db.getBookmarksDao().upsert(item.bookmarks)
            }
        }
        pending.forEach { item ->
            item.scrobblings.forEach {
                db.getScrobblingDao().upsert(it)
                diagnostics.restoredTrackingCount += 1
            }
        }

        diagnostics.restoredMangaCount += pending.size
    }

    private fun restorePreferences(preferences: List<MihonBackupPreference>) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit {
            preferences.forEach { pref ->
                when (val value = pref.value) {
                    is MihonBooleanPreferenceValue -> putBoolean(pref.key, value.value)
                    is MihonFloatPreferenceValue -> putFloat(pref.key, value.value)
                    is MihonIntPreferenceValue -> putInt(pref.key, value.value)
                    is MihonLongPreferenceValue -> putLong(pref.key, value.value)
                    is MihonStringPreferenceValue -> putString(pref.key, value.value)
                    is MihonStringSetPreferenceValue -> putStringSet(pref.key, value.value)
                }
            }
        }
    }

    private fun restoreSourcePreferences(preferences: List<MihonBackupSourcePreferences>, @Suppress("UNUSED_PARAMETER") diagnostics: RestoreAccumulator) {
        preferences.forEach { sourcePreferences ->
            val sourceId = parseSourceIdFromPreferenceKey(sourcePreferences.sourceKey)
                ?: return@forEach
            val sourceName = resolveStoredSourceName(sourceId, emptyList())
            val prefs = context.getSharedPreferences(SourceSettings.getStorageName(sourceName), Context.MODE_PRIVATE)
            prefs.edit {
                sourcePreferences.prefs.forEach { pref ->
                    when (val value = pref.value) {
                        is MihonBooleanPreferenceValue -> putBoolean(pref.key, value.value)
                        is MihonFloatPreferenceValue -> putFloat(pref.key, value.value)
                        is MihonIntPreferenceValue -> putInt(pref.key, value.value)
                        is MihonLongPreferenceValue -> putLong(pref.key, value.value)
                        is MihonStringPreferenceValue -> putString(pref.key, value.value)
                        is MihonStringSetPreferenceValue -> putStringSet(pref.key, value.value)
                    }
                }
            }
        }
    }

    private fun restoreExtensionRepo(repos: List<MihonBackupExtensionRepo>) {
        settings.externalExtensionsRepoUrl = repos.firstOrNull()?.baseUrl
    }

    private fun resolveStoredSourceName(sourceId: Long, backupSources: List<MihonBackupSource>): String {
        val source = mihonExtensionManager.getMihonMangaSourceById(sourceId)
        if (source != null) {
            return source.name
        }
        return if (sourceId > 0) "MIHON_$sourceId" else "UNKNOWN"
    }


    private fun resolveSourceTitle(sourceId: Long, backupSources: List<MihonBackupSource>): String? {
        val installed = mihonExtensionManager.getMihonMangaSourceById(sourceId)
        return installed?.displayName ?: backupSources.firstOrNull { it.sourceId == sourceId }?.name
    }

    private fun parseSourceIdFromPreferenceKey(key: String): Long? {
        return key.substringAfterLast('_').toLongOrNull()
            ?: key.removePrefix("source_").substringBefore(':').toLongOrNull()
    }

  private fun encodeTrackingStatus(status: String?): Int {
    return when (status?.lowercase(Locale.ROOT)) {
      "planned", "plan_to_read" -> 1
      "reading", "current" -> 2
      "completed" -> 3
      "on_hold", "onhold", "paused" -> 4
      "dropped" -> 5
      "re_reading", "rereading", "re-reading" -> 6
      else -> 0
    }
  }

  private fun decodeTrackingStatus(status: Int): String? {
    return when (status) {
      1 -> "planned"
      2 -> "reading"
      3 -> "completed"
      4 -> "on_hold"
      5 -> "dropped"
      6 -> "re_reading"
      else -> null
    }
  }

  private fun computeHistoryPercent(
    chapterIndex: Int,
    chaptersCount: Int,
    isChapterMarkedRead: Boolean,
    lastPageRead: Int,
  ): Float {
    if (chaptersCount <= 0) return 0f
    val normalizedIndex = chapterIndex.coerceIn(0, chaptersCount - 1)
    val inChapterProgress = when {
      isChapterMarkedRead -> 1f
      lastPageRead > 0 -> 0.5f
      else -> 0f
    }
    val completed = (normalizedIndex + inChapterProgress).coerceAtMost(chaptersCount.toFloat())
    return completed / chaptersCount.toFloat()
  }
}



