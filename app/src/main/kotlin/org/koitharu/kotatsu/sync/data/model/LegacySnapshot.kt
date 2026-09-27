package org.koitharu.kotatsu.sync.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.backup.local.data.model.BookmarkBackup
import org.koitharu.kotatsu.backup.local.data.model.MangaBackup

/**
 * The single shared `dropsauce_sync.json` written by app versions before replicas (schema 1–2).
 * Only read, never written: it is converted into a pseudo-replica so an updated device picks up what
 * not-yet-updated devices keep writing. Versions come from the rows' own timestamps — the same values
 * the database migration seeded local versions with, so identical rows compare equal.
 * Settings, feed, stats and tracker links from it are ignored (they had no usable timestamps).
 */
@Serializable
class LegacySnapshot(
	@SerialName("schema") val schemaVersion: Int = 0,
	@SerialName("categories") val categories: List<Category> = emptyList(),
	@SerialName("favourites") val favourites: List<Favourite> = emptyList(),
	@SerialName("history") val history: List<History> = emptyList(),
	@SerialName("bookmarks") val bookmarks: List<BookmarkBackup> = emptyList(),
) {

	@Serializable
	class Category(
		@SerialName("category_id") val categoryId: Int,
		@SerialName("created_at") val createdAt: Long,
		@SerialName("sort_key") val sortKey: Int,
		@SerialName("title") val title: String,
		@SerialName("order") val order: String,
		@SerialName("track") val track: Boolean,
		@SerialName("download_new_chapters") val downloadNewChapters: Boolean,
		@SerialName("show_in_lib") val isVisibleInLibrary: Boolean,
		@SerialName("deleted_at") val deletedAt: Long,
	)

	@Serializable
	class Favourite(
		@SerialName("manga_id") val mangaId: Long,
		@SerialName("category_id") val categoryId: Long,
		@SerialName("sort_key") val sortKey: Int,
		@SerialName("pinned") val isPinned: Boolean,
		@SerialName("created_at") val createdAt: Long,
		@SerialName("deleted_at") val deletedAt: Long,
		@SerialName("manga") val manga: MangaBackup,
	)

	@Serializable
	class History(
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
	)

	fun toReplica(): Replica {
		val uids = categories.associate { it.categoryId to "t:" + it.title.trim(' ') }
		val manga = HashMap<Long, MangaBackup>()
		favourites.forEach { manga[it.manga.id] = it.manga }
		history.forEach { manga[it.manga.id] = it.manga }
		bookmarks.forEach { manga[it.manga.id] = it.manga }
		return Replica(
			deviceId = "legacy",
			manga = manga.values.toList(),
			categories = categories.map {
				RCategory(
					uid = uids.getValue(it.categoryId),
					title = it.title,
					sortKey = it.sortKey,
					order = it.order,
					track = it.track,
					downloadNewChapters = it.downloadNewChapters,
					isVisibleInLibrary = it.isVisibleInLibrary,
					createdAt = it.createdAt,
					deletedAt = it.deletedAt,
					v = maxOf(it.createdAt, it.deletedAt),
				)
			},
			favourites = favourites.mapNotNull {
				RFavourite(
					mangaId = it.mangaId,
					categoryUid = uids[it.categoryId.toInt()] ?: return@mapNotNull null,
					sortKey = it.sortKey,
					isPinned = it.isPinned,
					createdAt = it.createdAt,
					deletedAt = it.deletedAt,
					v = maxOf(it.createdAt, it.deletedAt),
				)
			},
			history = history.map {
				RHistory(
					mangaId = it.mangaId,
					createdAt = it.createdAt,
					updatedAt = it.updatedAt,
					chapterId = it.chapterId,
					page = it.page,
					scroll = it.scroll,
					percent = it.percent,
					chaptersCount = it.chaptersCount,
					deletedAt = it.deletedAt,
					v = maxOf(it.updatedAt, it.deletedAt),
				)
			},
			bookmarks = bookmarks.flatMap { group ->
				group.bookmarks.map {
					RBookmark(it.mangaId, it.pageId, it.chapterId, it.page, it.scroll, it.imageUrl, it.createdAt, it.percent, it.createdAt)
				}
			},
		)
	}
}
