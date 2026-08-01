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
import okio.source
import org.koitharu.kotatsu.backup.model.MihonBackup
import org.koitharu.kotatsu.backup.model.MihonBackupCategory
import org.koitharu.kotatsu.backup.model.MihonBackupChapter
import org.koitharu.kotatsu.backup.model.MihonBackupManga
import org.koitharu.kotatsu.backup.model.MihonBackupFallback
import org.koitharu.kotatsu.backup.model.MihonBackupExtensionRepo
import org.koitharu.kotatsu.backup.model.MihonBackupPreference
import org.koitharu.kotatsu.backup.model.MihonBackupSource
import org.koitharu.kotatsu.backup.model.MihonBackupSourcePreferences
import org.koitharu.kotatsu.backup.model.MihonBooleanPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonFloatPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonIntPreferenceValue
import org.koitharu.kotatsu.backup.model.MihonLongPreferenceValue
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
import org.koitharu.kotatsu.mihon.MihonExtensionLoader
import org.koitharu.kotatsu.mihon.model.mihonChapterId
import org.koitharu.kotatsu.mihon.model.mihonMangaId
import org.koitharu.kotatsu.parsers.util.longHashCode
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreRecord
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreRegistry
import org.koitharu.kotatsu.settings.sources.catalog.normalizeExtensionStoreUrl
import org.koitharu.kotatsu.settings.sources.catalog.stableExtensionStoreId
import org.koitharu.kotatsu.stats.data.StatsEntity
import org.koitharu.kotatsu.tracker.data.TrackEntity
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import org.koitharu.kotatsu.R

@Singleton
class MihonBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: MangaDatabase,
    private val settings: AppSettings,
    private val mihonExtensionManager: MihonExtensionManager,
    private val mihonExtensionLoader: MihonExtensionLoader,
    private val extensionStoreRegistry: ExtensionStoreRegistry,
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
  )

  private class RestoreAccumulator(
    missingSources: List<String>,
    missingTrackers: List<Int>,
  ) {
    val missingSources = missingSources.toMutableSet()
    val missingTrackers = missingTrackers.toMutableSet()
    var restoredMangaCount = 0
    var restoredTrackingCount = 0

    fun toReport() = RestoreReport(
      restoredMangaCount = restoredMangaCount,
      restoredTrackingCount = restoredTrackingCount,
      missingSources = missingSources.sorted(),
      missingTrackers = missingTrackers.sorted(),
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
    val track: TrackEntity?,
  )

  /**
   * Maps Mihon backup categories onto Kotatsu favourite categories.
   *
   * In a Mihon backup each manga references its categories by their [MihonBackupCategory.order]
   * value (see Mihon's `MangaBackupCreator`), so we key the lookup by order. Categories are reused
   * by title when one already exists, and favorited manga that belong to no category fall back to a
   * single [DEFAULT_CATEGORY_TITLE] category.
   */
  private inner class CategoryResolver(
    private val backupCategories: List<MihonBackupCategory>,
  ) {
    private val dao = db.getFavouriteCategoriesDao()
    private val idByOrder = HashMap<Long, Long>()
    private val idByTitle = HashMap<String, Long>()
    private var defaultCategoryId: Long? = null

    suspend fun prepare(manga: List<MihonBackupManga>) {
      dao.findAll().forEach { idByTitle[it.title] = it.categoryId.toLong() }
      backupCategories.sortedBy { it.order }.forEach { category ->
        val title = category.name.trim()
        if (title.isNotEmpty()) {
          idByOrder[category.order] = categoryIdForTitle(title)
        }
      }
      val anyUncategorized = manga.any { item ->
        item.favorite && item.categories.none { idByOrder.containsKey(it) }
      }
      if (anyUncategorized) {
        defaultCategoryId = categoryIdForTitle(DEFAULT_CATEGORY_TITLE)
      }
    }

    fun resolve(orders: List<Long>): List<Long> {
      val ids = orders.mapNotNull { idByOrder[it] }.distinct()
      return ids.ifEmpty { listOfNotNull(defaultCategoryId) }
    }

    private suspend fun categoryIdForTitle(title: String): Long {
      idByTitle[title]?.let { return it }
      val id = dao.insert(
        FavouriteCategoryEntity(
          categoryId = 0,
          createdAt = System.currentTimeMillis(),
          sortKey = dao.getNextSortKey(),
          title = title,
          order = "NEWEST",
          track = true,
          downloadNewChapters = false,
          isVisibleInLibrary = true,
          deletedAt = 0,
        ),
      )
      idByTitle[title] = id
      return id
    }
  }

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
                if (options.libraryEntries) {
                    val categoryResolver = CategoryResolver(backup.backupCategories)
                    categoryResolver.prepare(backup.backupManga)
                    restoreManga(backup, options, diagnostics, categoryResolver)
                    removeEmptyReadLaterCategory()
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

  // The built-in "Read later" category is pre-populated on DB creation and a Mihon backup never
  // carries it, so it lingers empty after a restore. Drop it when empty; a populated one stays.
  private suspend fun removeEmptyReadLaterCategory() {
    val readLaterTitle = context.getString(R.string.read_later)
    val dao = db.getFavouriteCategoriesDao()
    val readLater = dao.findAll().firstOrNull { it.title == readLaterTitle } ?: return
    if (db.getFavouritesDao().findAll(readLater.categoryId.toLong()).isEmpty()) {
      dao.delete(readLater.categoryId.toLong())
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
    val missingTrackers = if (options.tracking) {
      backup.backupManga
        .asSequence()
        .flatMap { it.tracking.asSequence() }
        .map { it.syncId }
        .filter { mihonTrackerToScrobblerId(it) == null }
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

    private suspend fun restoreManga(
        backup: MihonBackup,
        options: Options,
        diagnostics: RestoreAccumulator,
        categoryResolver: CategoryResolver,
    ) {
        val now = System.currentTimeMillis()

        val pending = backup.backupManga.map { item ->
            val sourceName = resolveStoredSourceName(item.source, backup.backupSources)
            // Use the same identities as the live Mihon adapter. Otherwise the first network
            // refresh replaces every restored chapter ID, losing the reading branch/checkpoint.
            val mangaId = mihonMangaId(sourceName, item.url)
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
            // Mihon assigns sourceOrder 0 to the newest chapter (sources list newest-first), whereas
            // Kotatsu reads chapters in ascending `index` order (oldest first). Reverse the order so
            // chapter ordering — and therefore reading progress — comes out right.
            val orderedBackupChapters = item.chapters.sortedWith(
                compareByDescending<MihonBackupChapter> { it.sourceOrder }.thenBy { it.chapterNumber },
            )
            val chapters = orderedBackupChapters.mapIndexed { index, chapter ->
                ChapterEntity(
                    chapterId = mihonChapterId(sourceName, chapter.url),
                    mangaId = mangaId,
                    title = chapter.name,
                    number = chapter.chapterNumber,
                    volume = 0,
                    url = chapter.url,
                    scanlator = chapter.scanlator,
                    uploadDate = chapter.dateUpload,
                    branch = chapter.scanlator?.trim()?.takeIf { it.isNotEmpty() },
                    source = sourceName,
                    index = index,
                )
            }
            val chapterByUrl = chapters.associateBy { it.url }
            val backupChapterByUrl = orderedBackupChapters.associateBy { it.url }
            val categoryIds = if (item.favorite) {
                categoryResolver.resolve(item.categories)
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
            val bookmarks = orderedBackupChapters.asSequence()
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

            // Mihon's positive history is its Continue Reading position. Chapter flags are only a
            // fallback for backups without history, so sampling a later chapter does not jump progress.
            val progressedChapter = chapters.lastOrNull { chapterEntity ->
                val backupChapter = backupChapterByUrl[chapterEntity.url]
                backupChapter?.let { it.read || it.lastPageRead > 0 } == true
            }
            val latestHistory = item.history
                .filter { it.lastRead > 0 }
                .maxByOrNull { it.lastRead }
            val currentChapter = latestHistory?.url?.let(chapterByUrl::get) ?: progressedChapter
            val currentHistory = currentChapter?.url?.let { url ->
                item.history.filter { it.url == url && it.lastRead > 0 }.maxByOrNull { it.lastRead }
            }
            val history = if (currentChapter != null) {
                val backupChapter = backupChapterByUrl[currentChapter.url]
                val restoredPage = backupChapter?.lastPageRead?.toInt()?.coerceAtLeast(0) ?: 0
                val updatedAt = currentHistory?.lastRead
                    ?: item.lastModifiedAt.takeIf { it > 0 }
                    ?: item.dateAdded.takeIf { it > 0 }
                    ?: now
                HistoryEntity(
                    mangaId = mangaId,
                    createdAt = updatedAt,
                    updatedAt = updatedAt,
                    chapterId = currentChapter.chapterId,
                    page = restoredPage,
                    scroll = 0f,
                    percent = computeHistoryPercent(
                        chapterIndex = currentChapter.index,
                        chaptersCount = chapters.size,
                    ),
                    deletedAt = 0,
                    chaptersCount = chapters.size,
                )
            } else {
                null
            }
            // Seed update detection from the newest chapter in the stream the user was reading.
            // Without this, a restored manga starts with an empty track and the first refresh
            // silently treats chapters released since the backup as an already-known baseline.
            val preferredBranch = currentChapter?.branch
                ?: chapters.groupBy { it.branch }.maxByOrNull { it.value.size }?.key
            val lastTrackedChapter = chapters.lastOrNull { it.branch == preferredBranch }
            val track = lastTrackedChapter?.let { chapter ->
                TrackEntity(
                    mangaId = mangaId,
                    lastChapterId = chapter.chapterId,
                    newChapters = 0,
                    lastCheckTime = 0L,
                    lastChapterDate = chapter.uploadDate,
                    lastResult = TrackEntity.RESULT_NONE,
                    lastError = null,
                )
            }
            val stats = if ((currentHistory?.readDuration ?: 0L) > 0L && history != null) {
                StatsEntity(
                    mangaId = mangaId,
                    startedAt = (history.updatedAt - currentHistory!!.readDuration).coerceAtLeast(0L),
                    duration = currentHistory.readDuration,
                    pages = (history.page + 1).coerceAtLeast(1),
                )
            } else {
                null
            }
            val scrobblings = if (options.tracking) {
                item.tracking.mapNotNull { tracking ->
                    val scrobblerId = mihonTrackerToScrobblerId(tracking.syncId)
                    if (scrobblerId == null) {
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
                        scrobbler = scrobblerId,
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
                    description = item.description,
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
                track = track,
            )
        }

        pending.flatMapTo(linkedSetOf()) { it.tags }
            .takeIf { it.isNotEmpty() }
            ?.let { db.getTagsDao().upsert(it.toList()) }
        pending.forEach { item -> db.getMangaDao().upsert(item.manga, item.tags) }
        pending.forEach { item -> item.track?.let { db.getTracksDao().upsert(it) } }
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
        extensionStoreRegistry.ensureMigrated(
            systemPackages = mihonExtensionLoader.getInstalledExtensions(context, privateMode = false)
                .mapTo(HashSet()) { it.pkgName },
            sandboxPackages = mihonExtensionLoader.getInstalledExtensions(context, privateMode = true)
                .mapTo(HashSet()) { it.pkgName },
        )
        extensionStoreRegistry.importStores(
            repos.map { repo ->
                val url = normalizeExtensionStoreUrl(repo.baseUrl)
                ExtensionStoreRecord(
                    id = stableExtensionStoreId(url),
                    indexUrl = url,
                    name = repo.name.ifBlank { repo.baseUrl },
                    shortName = repo.shortName,
                    fingerprint = repo.signingKeyFingerprint.takeIf(String::isNotBlank),
                )
            },
        )
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

  /**
   * Translates a Mihon tracker `syncId` (see Mihon's `TrackerManager`) into the matching Kotatsu
   * [org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService] id. The two apps number
   * their services differently, so copying the id verbatim points entries at the wrong service.
   * Returns `null` for trackers Kotatsu doesn't support (Bangumi, Komga, MangaUpdates, Kavita…).
   */
  private fun mihonTrackerToScrobblerId(syncId: Int): Int? = when (syncId) {
    1 -> 3 // MyAnimeList -> MAL
    2 -> 2 // AniList
    3 -> 4 // Kitsu
    4 -> 1 // Shikimori
    else -> null
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
  ): Float {
    if (chaptersCount <= 0) return 0f
    val normalizedIndex = chapterIndex.coerceIn(0, chaptersCount - 1)
    return (normalizedIndex + 1) / chaptersCount.toFloat()
  }

  private companion object {
    const val DEFAULT_CATEGORY_TITLE = "Default"
  }
}



