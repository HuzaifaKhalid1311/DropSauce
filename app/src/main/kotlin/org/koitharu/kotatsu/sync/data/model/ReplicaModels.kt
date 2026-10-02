package org.koitharu.kotatsu.sync.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.backup.local.data.model.BackupPrimitive
import org.koitharu.kotatsu.backup.local.data.model.MangaBackup

/**
 * One device's published state (`replica_<device>.json.gz`). Every record carries its version [v];
 * the merger keeps, per [SyncRecord.key], the record with the highest one. Manga metadata is stored
 * once in [manga] and referenced by id everywhere else.
 */
@Serializable
class Replica(
	@SerialName("schema") val schema: Int = SCHEMA,
	@SerialName("device") val deviceId: String = "",
	@SerialName("content") val content: Set<String> = emptySet(),
	@SerialName("manga") val manga: List<MangaBackup> = emptyList(),
	@SerialName("categories") val categories: List<RCategory> = emptyList(),
	@SerialName("favourites") val favourites: List<RFavourite> = emptyList(),
	@SerialName("history") val history: List<RHistory> = emptyList(),
	@SerialName("bookmarks") val bookmarks: List<RBookmark> = emptyList(),
	@SerialName("scrobblings") val scrobblings: List<RScrobbling> = emptyList(),
	@SerialName("stats") val stats: List<RStats> = emptyList(),
	@SerialName("feed") val feed: List<RFeed> = emptyList(),
	@SerialName("manga_prefs") val mangaPrefs: List<RMangaPrefs> = emptyList(),
	@SerialName("prefs") val prefs: List<RPref> = emptyList(),
	@SerialName("tombstones") val tombstones: List<RTombstone> = emptyList(),
) {

	companion object {

		const val SCHEMA = 3
	}
}

interface SyncRecord {
	val key: String
	val v: Long
}

@Serializable
data class RCategory(
	@SerialName("uid") val uid: String,
	@SerialName("title") val title: String,
	@SerialName("sort_key") val sortKey: Int,
	@SerialName("order") val order: String,
	@SerialName("track") val track: Boolean,
	@SerialName("download_new_chapters") val downloadNewChapters: Boolean,
	@SerialName("show_in_lib") val isVisibleInLibrary: Boolean,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("deleted_at") val deletedAt: Long,
	@SerialName("v") override val v: Long,
) : SyncRecord {
	override val key get() = uid
}

@Serializable
data class RFavourite(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("category") val categoryUid: String,
	@SerialName("sort_key") val sortKey: Int,
	@SerialName("pinned") val isPinned: Boolean,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("deleted_at") val deletedAt: Long,
	@SerialName("v") override val v: Long,
) : SyncRecord {
	override val key get() = "$mangaId:$categoryUid"
}

@Serializable
data class RHistory(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("updated_at") val updatedAt: Long,
	@SerialName("chapter_id") val chapterId: Long,
	@SerialName("page") val page: Int,
	@SerialName("scroll") val scroll: Float,
	@SerialName("percent") val percent: Float,
	@SerialName("chapters") val chaptersCount: Int,
	@SerialName("deleted_at") val deletedAt: Long,
	@SerialName("v") override val v: Long,
) : SyncRecord {
	override val key get() = mangaId.toString()
}

@Serializable
data class RBookmark(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("page_id") val pageId: Long,
	@SerialName("chapter_id") val chapterId: Long,
	@SerialName("page") val page: Int,
	@SerialName("scroll") val scroll: Int,
	@SerialName("image") val imageUrl: String,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("percent") val percent: Float,
	@SerialName("v") override val v: Long,
) : SyncRecord {
	override val key get() = "$mangaId:$pageId"
}

@Serializable
data class RScrobbling(
	@SerialName("scrobbler") val scrobbler: Int,
	@SerialName("id") val id: Int,
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("target_id") val targetId: Long,
	@SerialName("status") val status: String? = null,
	@SerialName("chapter") val chapter: Int,
	@SerialName("comment") val comment: String? = null,
	@SerialName("rating") val rating: Float,
	@SerialName("v") override val v: Long,
) : SyncRecord {
	override val key get() = "$scrobbler:$id:$mangaId"
}

@Serializable
data class RStats(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("started_at") val startedAt: Long,
	@SerialName("duration") val duration: Long,
	@SerialName("pages") val pages: Int,
	@SerialName("chapters") val chapters: Int,
	@SerialName("v") override val v: Long,
) : SyncRecord {
	override val key get() = "$mangaId:$startedAt"
}

@Serializable
data class RFeed(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("chapters") val chapters: String,
	@SerialName("chapter_ids") val chapterIds: String,
	@SerialName("created_at") val createdAt: Long,
	@SerialName("unread") val isUnread: Boolean,
	@SerialName("v") override val v: Long,
) : SyncRecord {
	override val key get() = FeedKeys.of(mangaId, chapterIds, chapters)
}

@Serializable
data class RMangaPrefs(
	@SerialName("manga_id") val mangaId: Long,
	@SerialName("mode") val mode: Int,
	@SerialName("cf_brightness") val cfBrightness: Float,
	@SerialName("cf_contrast") val cfContrast: Float,
	@SerialName("cf_invert") val cfInvert: Boolean,
	@SerialName("cf_grayscale") val cfGrayscale: Boolean,
	@SerialName("cf_book") val cfBookEffect: Boolean,
	@SerialName("title_override") val titleOverride: String? = null,
	@SerialName("description_override") val descriptionOverride: String? = null,
	/** A portable (remote) cover url; local custom covers travel as [coverHash] instead. */
	@SerialName("cover_url") val coverUrl: String? = null,
	@SerialName("cover_hash") val coverHash: String? = null,
	@SerialName("cover_ext") val coverExtension: String? = null,
	@SerialName("content_rating_override") val contentRatingOverride: String? = null,
	@SerialName("merge_scanlators") val mergeScanlators: Boolean = false,
	@SerialName("merged_scanlators") val mergedScanlators: String? = null,
	@SerialName("v") override val v: Long,
) : SyncRecord {
	override val key get() = mangaId.toString()
}

/** One SharedPreferences key; a null [value] means the key was removed. */
@Serializable
data class RPref(
	@SerialName("file") val file: String,
	@SerialName("key") val prefKey: String,
	@SerialName("value") val value: BackupPrimitive? = null,
	@SerialName("v") override val v: Long,
) : SyncRecord {
	override val key get() = "$file/$prefKey"
}

/** A deleted row of [table], identified by the same [key] its live record would have. */
@Serializable
data class RTombstone(
	@SerialName("table") val table: String,
	@SerialName("key") val recordKey: String,
	@SerialName("v") override val v: Long,
) : SyncRecord {
	override val key get() = recordKey
}

object FeedKeys {

	/** Chapter ids when known, normalized titles for entries written before ids were stored. */
	fun of(mangaId: Long, chapterIds: String, chapters: String): String {
		val ids = chapterIds.lineSequence().map(String::trim).filter(String::isNotEmpty).toSortedSet()
		return if (ids.isNotEmpty()) {
			"$mangaId:" + ids.joinToString(",")
		} else {
			"$mangaId:t:" + chapters.lineSequence()
				.map { it.trim().lowercase() }
				.filter(String::isNotEmpty)
				.toSortedSet()
				.joinToString("\n")
		}
	}

	/** Parses the raw `manga_id:ids-or-t:titles` key written by the feed triggers. */
	fun ofRaw(raw: String): String? {
		val sep = raw.indexOf(':', startIndex = 1)
		if (sep < 0) return null
		val mangaId = raw.substring(0, sep).toLongOrNull() ?: return null
		val rest = raw.substring(sep + 1)
		return if (rest.startsWith("t:")) of(mangaId, "", rest.substring(2)) else of(mangaId, rest, "")
	}

	fun chapterIds(chapterIds: String): Set<Long> =
		chapterIds.lineSequence().mapNotNullTo(HashSet()) { it.trim().toLongOrNull() }
}
