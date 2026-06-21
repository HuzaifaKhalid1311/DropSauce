package org.koitharu.kotatsu.backup.local.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.MangaSourceEntity
import org.koitharu.kotatsu.core.db.entity.MangaWithTags
import org.koitharu.kotatsu.core.db.entity.TagEntity
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.favourites.data.FavouriteManga
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.history.data.HistoryWithManga
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.stats.data.StatsEntity

@Serializable
class BackupIndex(
	@SerialName("app_id") val appId: String,
	@SerialName("app_version") val appVersion: Int,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("format_version") val formatVersion: Int = FORMAT_VERSION,
) {

	constructor() : this(
		appId = BuildConfig.APPLICATION_ID,
		appVersion = BuildConfig.VERSION_CODE,
		createdAt = System.currentTimeMillis(),
	)

	companion object {
		const val FORMAT_VERSION = 1
	}
}

@Serializable
class TagBackup(
	@SerialName("id") val id: Long,
	@SerialName("title") val title: String,
	@SerialName("key") val key: String,
	@SerialName("source") val source: String,
	@SerialName("pinned") val isPinned: Boolean = false,
) {

	constructor(entity: TagEntity) : this(
		id = entity.id,
		title = entity.title,
		key = entity.key,
		source = entity.source,
		isPinned = entity.isPinned,
	)

	fun toEntity() = TagEntity(
		id = id,
		title = title,
		key = key,
		source = source,
		isPinned = isPinned,
	)
}

@Serializable
class MangaBackup(
	@SerialName("id") val id: Long,
	@SerialName("title") val title: String,
	@SerialName("alt_title") val altTitles: String? = null,
	@SerialName("url") val url: String,
	@SerialName("public_url") val publicUrl: String,
	@SerialName("rating") val rating: Float = -1f,
	@SerialName("nsfw") val isNsfw: Boolean = false,
	@SerialName("content_rating") val contentRating: String? = null,
	@SerialName("cover_url") val coverUrl: String,
	@SerialName("large_cover_url") val largeCoverUrl: String? = null,
	@SerialName("state") val state: String? = null,
	@SerialName("author") val authors: String? = null,
	@SerialName("source") val source: String,
	@SerialName("source_title") val sourceTitle: String? = null,
	@SerialName("tags") val tags: Set<TagBackup> = emptySet(),
) {

	constructor(entity: MangaWithTags) : this(
		id = entity.manga.id,
		title = entity.manga.title,
		altTitles = entity.manga.altTitles,
		url = entity.manga.url,
		publicUrl = entity.manga.publicUrl,
		rating = entity.manga.rating,
		isNsfw = entity.manga.isNsfw,
		contentRating = entity.manga.contentRating,
		coverUrl = entity.manga.coverUrl,
		largeCoverUrl = entity.manga.largeCoverUrl,
		state = entity.manga.state,
		authors = entity.manga.authors,
		source = entity.manga.source,
		sourceTitle = entity.manga.sourceTitle,
		tags = entity.tags.map(::TagBackup).toSet(),
	)

	fun toEntity() = MangaEntity(
		id = id,
		title = title,
		altTitles = altTitles,
		url = url,
		publicUrl = publicUrl,
		rating = rating,
		isNsfw = isNsfw,
		contentRating = contentRating,
		coverUrl = coverUrl,
		largeCoverUrl = largeCoverUrl,
		state = state,
		authors = authors,
		source = source,
		sourceTitle = sourceTitle,
	)
}

@Serializable
class ChapterBackup(
	@SerialName("chapter_id") val chapterId: Long,
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("name") val title: String,
	@SerialName("number") val number: Float,
	@SerialName("volume") val volume: Int = 0,
	@SerialName("url") val url: String,
	@SerialName("scanlator") val scanlator: String? = null,
	@SerialName("upload_date") val uploadDate: Long = 0L,
	@SerialName("branch") val branch: String? = null,
	@SerialName("source") val source: String,
	@SerialName("index") val index: Int,
) {

	constructor(entity: ChapterEntity) : this(
		chapterId = entity.chapterId,
		mangaId = entity.mangaId,
		title = entity.title,
		number = entity.number,
		volume = entity.volume,
		url = entity.url,
		scanlator = entity.scanlator,
		uploadDate = entity.uploadDate,
		branch = entity.branch,
		source = entity.source,
		index = entity.index,
	)

	fun toEntity() = ChapterEntity(
		chapterId = chapterId,
		mangaId = mangaId,
		title = title,
		number = number,
		volume = volume,
		url = url,
		scanlator = scanlator,
		uploadDate = uploadDate,
		branch = branch,
		source = source,
		index = index,
	)
}

@Serializable
class MangaWithChaptersBackup(
	@SerialName("manga") val manga: MangaBackup,
	@SerialName("chapters") val chapters: List<ChapterBackup> = emptyList(),
)

@Serializable
class CategoryBackup(
	@SerialName("category_id") val categoryId: Int,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("sort_key") val sortKey: Int,
	@SerialName("title") val title: String,
	@SerialName("order") val order: String = "NEWEST",
	@SerialName("track") val track: Boolean = true,
	@SerialName("download_new_chapters") val downloadNewChapters: Boolean = false,
	@SerialName("show_in_lib") val isVisibleInLibrary: Boolean = true,
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
		deletedAt = 0L,
	)
}

@Serializable
class FavouriteBackup(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("category_id") val categoryId: Long,
	@SerialName("sort_key") val sortKey: Int = 0,
	@SerialName("pinned") val isPinned: Boolean = false,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("manga") val manga: MangaBackup,
) {

	constructor(entity: FavouriteManga) : this(
		mangaId = entity.manga.id,
		categoryId = entity.favourite.categoryId,
		sortKey = entity.favourite.sortKey,
		isPinned = entity.favourite.isPinned,
		createdAt = entity.favourite.createdAt,
		manga = MangaBackup(MangaWithTags(entity.manga, entity.tags)),
	)

	fun toEntity() = FavouriteEntity(
		mangaId = mangaId,
		categoryId = categoryId,
		sortKey = sortKey,
		isPinned = isPinned,
		createdAt = createdAt,
		deletedAt = 0L,
	)
}

@Serializable
class HistoryBackup(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("updated_at") val updatedAt: Long,
	@SerialName("chapter_id") val chapterId: Long,
	@SerialName("page") val page: Int,
	@SerialName("scroll") val scroll: Float,
	@SerialName("percent") val percent: Float = 0f,
	@SerialName("chapters") val chaptersCount: Int = 0,
	@SerialName("manga") val manga: MangaBackup,
) {

	constructor(entity: HistoryWithManga) : this(
		mangaId = entity.manga.id,
		createdAt = entity.history.createdAt,
		updatedAt = entity.history.updatedAt,
		chapterId = entity.history.chapterId,
		page = entity.history.page,
		scroll = entity.history.scroll,
		percent = entity.history.percent,
		chaptersCount = entity.history.chaptersCount,
		manga = MangaBackup(MangaWithTags(entity.manga, entity.tags)),
	)

	fun toEntity() = HistoryEntity(
		mangaId = mangaId,
		createdAt = createdAt,
		updatedAt = updatedAt,
		chapterId = chapterId,
		page = page,
		scroll = scroll,
		percent = percent,
		deletedAt = 0L,
		chaptersCount = chaptersCount,
	)
}

@Serializable
class BookmarkBackup(
	@SerialName("manga") val manga: MangaBackup,
	@SerialName("bookmarks") val bookmarks: List<Item>,
) {

	@Serializable
	class Item(
		@SerialName("manga_id") val mangaId: Long,
		@SerialName("page_id") val pageId: Long,
		@SerialName("chapter_id") val chapterId: Long,
		@SerialName("page") val page: Int,
		@SerialName("scroll") val scroll: Int,
		@SerialName("image_url") val imageUrl: String,
		@SerialName("created_at") val createdAt: Long,
		@SerialName("percent") val percent: Float,
	) {

		constructor(entity: BookmarkEntity) : this(
			mangaId = entity.mangaId,
			pageId = entity.pageId,
			chapterId = entity.chapterId,
			page = entity.page,
			scroll = entity.scroll,
			imageUrl = entity.imageUrl,
			createdAt = entity.createdAt,
			percent = entity.percent,
		)

		fun toEntity() = BookmarkEntity(
			mangaId = mangaId,
			pageId = pageId,
			chapterId = chapterId,
			page = page,
			scroll = scroll,
			imageUrl = imageUrl,
			createdAt = createdAt,
			percent = percent,
		)
	}

	constructor(mangaWithTags: MangaWithTags, entries: List<BookmarkEntity>) : this(
		manga = MangaBackup(mangaWithTags),
		bookmarks = entries.map(::Item),
	)
}

@Serializable
class StatsBackup(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("started_at") val startedAt: Long,
	@SerialName("duration") val duration: Long,
	@SerialName("pages") val pages: Int,
	@SerialName("chapters") val chapters: Int = 0,
) {

	constructor(entity: StatsEntity) : this(
		mangaId = entity.mangaId,
		startedAt = entity.startedAt,
		duration = entity.duration,
		pages = entity.pages,
		chapters = entity.chapters,
	)

	fun toEntity() = StatsEntity(
		mangaId = mangaId,
		startedAt = startedAt,
		duration = duration,
		pages = pages,
		chapters = chapters,
	)
}

@Serializable
class ScrobblingBackup(
	@SerialName("scrobbler") val scrobbler: Int,
	@SerialName("id") val id: Int,
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("target_id") val targetId: Long,
	@SerialName("status") val status: String? = null,
	@SerialName("chapter") val chapter: Int = 0,
	@SerialName("comment") val comment: String? = null,
	@SerialName("rating") val rating: Float = 0f,
	@SerialName("updated_at") val updatedAt: Long = 0L,
) {

	constructor(entity: ScrobblingEntity) : this(
		scrobbler = entity.scrobbler,
		id = entity.id,
		mangaId = entity.mangaId,
		targetId = entity.targetId,
		status = entity.status,
		chapter = entity.chapter,
		comment = entity.comment,
		rating = entity.rating,
		updatedAt = entity.updatedAt,
	)

	fun toEntity() = ScrobblingEntity(
		scrobbler = scrobbler,
		id = id,
		mangaId = mangaId,
		targetId = targetId,
		status = status,
		chapter = chapter,
		comment = comment,
		rating = rating,
		updatedAt = updatedAt,
	)
}

@Serializable
class SourceBackup(
	@SerialName("source") val source: String,
	@SerialName("sort_key") val sortKey: Int,
	@SerialName("used_at") val lastUsedAt: Long,
	@SerialName("added_in") val addedIn: Int,
	@SerialName("pinned") val isPinned: Boolean = false,
	@SerialName("enabled") val isEnabled: Boolean = true,
	@SerialName("title") val title: String? = null,
) {

	constructor(entity: MangaSourceEntity) : this(
		source = entity.source,
		sortKey = entity.sortKey,
		lastUsedAt = entity.lastUsedAt,
		addedIn = entity.addedIn,
		isPinned = entity.isPinned,
		isEnabled = entity.isEnabled,
		title = entity.title,
	)

	fun toEntity() = MangaSourceEntity(
		source = source,
		isEnabled = isEnabled,
		sortKey = sortKey,
		addedIn = addedIn,
		lastUsedAt = lastUsedAt,
		isPinned = isPinned,
		cfState = 0,
		title = title,
	)
}

@Serializable
class SourceSettingsBackup(
	@SerialName("source") val source: String,
	@SerialName("values") val values: Map<String, BackupPrimitive>,
)

@Serializable
sealed class BackupPrimitive {

	@Serializable
	@SerialName("string")
	data class StringValue(@SerialName("v") val value: String) : BackupPrimitive()

	@Serializable
	@SerialName("bool")
	data class BoolValue(@SerialName("v") val value: Boolean) : BackupPrimitive()

	@Serializable
	@SerialName("int")
	data class IntValue(@SerialName("v") val value: Int) : BackupPrimitive()

	@Serializable
	@SerialName("long")
	data class LongValue(@SerialName("v") val value: Long) : BackupPrimitive()

	@Serializable
	@SerialName("float")
	data class FloatValue(@SerialName("v") val value: Float) : BackupPrimitive()

	@Serializable
	@SerialName("string_set")
	data class StringSetValue(@SerialName("v") val value: Set<String>) : BackupPrimitive()

	companion object {

		fun of(value: Any?): BackupPrimitive? = when (value) {
			is String -> StringValue(value)
			is Boolean -> BoolValue(value)
			is Int -> IntValue(value)
			is Long -> LongValue(value)
			is Float -> FloatValue(value)
			is Set<*> -> StringSetValue(value.filterIsInstance<String>().toSet())
			else -> null
		}
	}

	fun rawValue(): Any = when (this) {
		is StringValue -> value
		is BoolValue -> value
		is IntValue -> value
		is LongValue -> value
		is FloatValue -> value
		is StringSetValue -> value
	}
}
