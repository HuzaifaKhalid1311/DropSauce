package org.koitharu.kotatsu.sync.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.backup.local.data.model.BackupPrimitive
import org.koitharu.kotatsu.backup.local.data.model.BookmarkBackup
import org.koitharu.kotatsu.backup.local.data.model.MangaBackup
import org.koitharu.kotatsu.backup.local.data.model.ScrobblingBackup
import org.koitharu.kotatsu.backup.local.data.model.SourceSettingsBackup
import org.koitharu.kotatsu.backup.local.data.model.StatsBackup
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.tracker.data.TrackEntity
import org.koitharu.kotatsu.tracker.data.TrackLogEntity

/**
 * The full payload uploaded to / downloaded from Google Drive's appDataFolder. It always contains
 * the COMPLETE set of rows including soft-deleted tombstones (deleted_at != 0) so deletions can be
 * propagated across devices. Row data is merged per-record by timestamp; [config] is merged as one
 * unit by [SyncConfig.revision] (last-writer-wins).
 */
@Serializable
data class SyncSnapshot(
	@SerialName("schema") val schemaVersion: Int = SCHEMA_VERSION,
	@SerialName("device_id") val deviceId: String = "",
	@SerialName("synced_at") val syncedAt: Long = 0L,
	@SerialName("categories") val categories: List<SyncCategory> = emptyList(),
	@SerialName("favourites") val favourites: List<SyncFavourite> = emptyList(),
	@SerialName("history") val history: List<SyncHistory> = emptyList(),
	@SerialName("bookmarks") val bookmarks: List<BookmarkBackup> = emptyList(),
	@SerialName("scrobblings") val scrobblings: List<ScrobblingBackup> = emptyList(),
	@SerialName("tracks") val tracks: List<SyncTrack> = emptyList(),
	@SerialName("feed") val feed: List<SyncFeedEntry> = emptyList(),
	@SerialName("stats") val stats: List<StatsBackup> = emptyList(),
	@SerialName("config") val config: SyncConfig? = null,
) {

	companion object {

		const val SCHEMA_VERSION = 2
	}
}

/**
 * A visible entry in the new-chapters feed. Local database ids are deliberately not synced because
 * they are auto-generated independently on every device. [mangaId] plus the normalized [chapters]
 * list is the stable cross-device identity used by the merger.
 */
@Serializable
class SyncFeedEntry(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("chapters") val chapters: String,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("unread") val isUnread: Boolean,
	@SerialName("manga") val manga: MangaBackup,
) {

	constructor(entity: TrackLogEntity, manga: MangaBackup) : this(
		mangaId = entity.mangaId,
		chapters = entity.chapters,
		createdAt = entity.createdAt,
		isUnread = entity.isUnread,
		manga = manga,
	)

	fun toEntity(id: Long = 0L) = TrackLogEntity(
		id = id,
		mangaId = mangaId,
		chapters = chapters,
		createdAt = createdAt,
		isUnread = isUnread,
	)
}

@Serializable
class SyncTrack(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("last_chapter_id") val lastChapterId: Long,
	@SerialName("chapters_new") val newChapters: Int,
	@SerialName("last_check_time") val lastCheckTime: Long,
	@SerialName("last_chapter_date") val lastChapterDate: Long,
	@SerialName("last_result") val lastResult: Int,
	@SerialName("last_error") val lastError: String? = null,
	@SerialName("manga") val manga: MangaBackup,
) {

	constructor(entity: TrackEntity, manga: MangaBackup) : this(
		mangaId = entity.mangaId,
		lastChapterId = entity.lastChapterId,
		newChapters = entity.newChapters,
		lastCheckTime = entity.lastCheckTime,
		lastChapterDate = entity.lastChapterDate,
		lastResult = entity.lastResult,
		lastError = entity.lastError,
		manga = manga,
	)

	fun toEntity() = TrackEntity(
		mangaId = mangaId,
		lastChapterId = lastChapterId,
		newChapters = newChapters,
		lastCheckTime = lastCheckTime,
		lastChapterDate = lastChapterDate,
		lastResult = lastResult,
		lastError = lastError,
	)
}

@Serializable
data class SyncCategory(
	@SerialName("category_id") val categoryId: Int,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("sort_key") val sortKey: Int,
	@SerialName("title") val title: String,
	@SerialName("order") val order: String,
	@SerialName("track") val track: Boolean,
	@SerialName("download_new_chapters") val downloadNewChapters: Boolean,
	@SerialName("show_in_lib") val isVisibleInLibrary: Boolean,
	@SerialName("deleted_at") val deletedAt: Long,
) {

	constructor(entity: FavouriteCategoryEntity) : this(
		categoryId = entity.categoryId,
		createdAt = entity.createdAt,
		sortKey = entity.sortKey,
		title = entity.title,
		order = entity.order,
		track = entity.track,
		downloadNewChapters = entity.downloadNewChapters,
		isVisibleInLibrary = entity.isVisibleInLibrary,
		deletedAt = entity.deletedAt,
	)

	fun toEntity() = FavouriteCategoryEntity(
		categoryId = categoryId,
		createdAt = createdAt,
		sortKey = sortKey,
		title = title,
		order = order,
		track = track,
		downloadNewChapters = downloadNewChapters,
		isVisibleInLibrary = isVisibleInLibrary,
		deletedAt = deletedAt,
	)
}

@Serializable
data class SyncFavourite(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("category_id") val categoryId: Long,
	@SerialName("sort_key") val sortKey: Int,
	@SerialName("pinned") val isPinned: Boolean,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("deleted_at") val deletedAt: Long,
	@SerialName("manga") val manga: MangaBackup,
) {

	constructor(entity: FavouriteEntity, manga: MangaBackup) : this(
		mangaId = entity.mangaId,
		categoryId = entity.categoryId,
		sortKey = entity.sortKey,
		isPinned = entity.isPinned,
		createdAt = entity.createdAt,
		deletedAt = entity.deletedAt,
		manga = manga,
	)

	fun toEntity() = FavouriteEntity(
		mangaId = mangaId,
		categoryId = categoryId,
		sortKey = sortKey,
		isPinned = isPinned,
		createdAt = createdAt,
		deletedAt = deletedAt,
	)
}

@Serializable
class SyncHistory(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("updated_at") val updatedAt: Long,
	@SerialName("chapter_id") val chapterId: Long,
	@SerialName("page") val page: Int,
	@SerialName("scroll") val scroll: Float,
	@SerialName("percent") val percent: Float,
	@SerialName("deleted_at") val deletedAt: Long,
	@SerialName("chapters") val chaptersCount: Int,
	@SerialName("manga") val manga: MangaBackup,
) {

	constructor(entity: HistoryEntity, manga: MangaBackup) : this(
		mangaId = entity.mangaId,
		createdAt = entity.createdAt,
		updatedAt = entity.updatedAt,
		chapterId = entity.chapterId,
		page = entity.page,
		scroll = entity.scroll,
		percent = entity.percent,
		deletedAt = entity.deletedAt,
		chaptersCount = entity.chaptersCount,
		manga = manga,
	)

	fun toEntity() = HistoryEntity(
		mangaId = mangaId,
		createdAt = createdAt,
		updatedAt = updatedAt,
		chapterId = chapterId,
		page = page,
		scroll = scroll,
		percent = percent,
		deletedAt = deletedAt,
		chaptersCount = chaptersCount,
	)
}

/**
 * Configuration that has no per-row timestamps (app settings, reader tap-grid, per-source settings,
 * custom covers / per-manga overrides). Merged as one unit: the side with the larger [revision]
 * wins. [revision] is bumped locally whenever the config content changes between syncs.
 */
@Serializable
class SyncConfig(
	@SerialName("revision") val revision: Long = 0L,
	@SerialName("settings") val settings: Map<String, BackupPrimitive> = emptyMap(),
	@SerialName("reader_grid") val readerGrid: Map<String, BackupPrimitive> = emptyMap(),
	@SerialName("source_settings") val sourceSettings: List<SourceSettingsBackup> = emptyList(),
	@SerialName("manga_prefs") val mangaPrefs: List<SyncMangaPrefs> = emptyList(),
)

@Serializable
class SyncMangaPrefs(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("mode") val mode: Int,
	@SerialName("cf_brightness") val cfBrightness: Float,
	@SerialName("cf_contrast") val cfContrast: Float,
	@SerialName("cf_invert") val cfInvert: Boolean,
	@SerialName("cf_grayscale") val cfGrayscale: Boolean,
	@SerialName("cf_book") val cfBookEffect: Boolean,
	@SerialName("title_override") val titleOverride: String? = null,
	@SerialName("description_override") val descriptionOverride: String? = null,
	@SerialName("cover_override") val coverUrlOverride: String? = null,
	@SerialName("cover_data") val coverData: String? = null,
	@SerialName("cover_extension") val coverFileExtension: String? = null,
	@SerialName("content_rating_override") val contentRatingOverride: String? = null,
	@SerialName("merge_scanlators") val mergeScanlators: Boolean = false,
) {

	constructor(
		entity: MangaPrefsEntity,
		coverData: String? = null,
		coverFileExtension: String? = null,
	) : this(
		mangaId = entity.mangaId,
		mode = entity.mode,
		cfBrightness = entity.cfBrightness,
		cfContrast = entity.cfContrast,
		cfInvert = entity.cfInvert,
		cfGrayscale = entity.cfGrayscale,
		cfBookEffect = entity.cfBookEffect,
		titleOverride = entity.titleOverride,
		descriptionOverride = entity.descriptionOverride,
		coverUrlOverride = entity.coverUrlOverride.takeIf { coverData == null },
		coverData = coverData,
		coverFileExtension = coverFileExtension,
		contentRatingOverride = entity.contentRatingOverride,
		mergeScanlators = entity.mergeScanlators,
	)

	fun toEntity(resolvedCoverUrl: String? = coverUrlOverride) = MangaPrefsEntity(
		mangaId = mangaId,
		mode = mode,
		cfBrightness = cfBrightness,
		cfContrast = cfContrast,
		cfInvert = cfInvert,
		cfGrayscale = cfGrayscale,
		cfBookEffect = cfBookEffect,
		titleOverride = titleOverride,
		descriptionOverride = descriptionOverride,
		coverUrlOverride = resolvedCoverUrl,
		contentRatingOverride = contentRatingOverride,
		mergeScanlators = mergeScanlators,
	)
}

/** What the user chose to sync. Each maps to one or more snapshot sections. */
enum class SyncContent(val key: String) {
	FAVOURITES("favourites"),
	HISTORY("history"),
	BOOKMARKS("bookmarks"),
	FEED("feed"),
	TRACKING("tracking"),
	STATS("stats"),
	SETTINGS("settings"),
	CUSTOM_COVERS("custom_covers"),
	;

	companion object {

		val DEFAULT: Set<String> = entries.mapTo(LinkedHashSet()) { it.key }

		fun fromKeys(keys: Set<String>): Set<SyncContent> =
			entries.filterTo(LinkedHashSet()) { it.key in keys }
	}
}
