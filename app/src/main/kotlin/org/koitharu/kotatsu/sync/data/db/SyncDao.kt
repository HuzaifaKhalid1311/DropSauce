package org.koitharu.kotatsu.sync.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity
import org.koitharu.kotatsu.core.db.entity.MangaWithTags
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.stats.data.StatsEntity
import org.koitharu.kotatsu.tracker.data.TrackLogEntity

@Dao
abstract class SyncDao {

	@Query("SELECT * FROM sync_rows WHERE tbl = :table")
	abstract suspend fun findRows(table: String): List<SyncRowEntity>

	@Query("SELECT * FROM sync_rows WHERE tbl = :table AND pk = :pk")
	abstract suspend fun findRow(table: String, pk: String): SyncRowEntity?

	@Upsert
	abstract suspend fun upsertRow(row: SyncRowEntity)

	@Query("UPDATE sync_rows SET v = :version, deleted = :isDeleted WHERE tbl = :table AND pk = :pk")
	abstract suspend fun setVersion(table: String, pk: String, version: Long, isDeleted: Boolean)

	@Query("UPDATE sync_rows SET sync_key = :key WHERE tbl = :table AND pk = :pk")
	abstract suspend fun setKey(table: String, pk: String, key: String)

	@Query("DELETE FROM sync_rows WHERE deleted = 1 AND v < :cutoff")
	abstract suspend fun gcTombstones(cutoff: Long)

	@Query("DELETE FROM sync_prefs WHERE hash IS NULL AND v < :cutoff")
	abstract suspend fun gcRemovedPrefs(cutoff: Long)

	/** Changes whenever any tracked row is written, deleted or re-versioned. */
	@Query("SELECT COUNT(*) || ':' || IFNULL(SUM(v), 0) || ':' || (SELECT COUNT(*) || ':' || IFNULL(SUM(v), 0) FROM sync_prefs) FROM sync_rows")
	abstract suspend fun fingerprint(): String

	@Query("SELECT * FROM sync_prefs WHERE file = :file")
	abstract suspend fun findPrefs(file: String): List<SyncPrefEntity>

	@Upsert
	abstract suspend fun upsertPrefs(entities: Collection<SyncPrefEntity>)

	@Query("DELETE FROM sync_prefs WHERE file = :file")
	abstract suspend fun deletePrefs(file: String)

	@Query("SELECT * FROM sync_remote")
	abstract suspend fun findRemote(): List<SyncRemoteEntity>

	@Upsert
	abstract suspend fun upsertRemote(entity: SyncRemoteEntity)

	@Query("DELETE FROM sync_remote WHERE file_id = :fileId")
	abstract suspend fun deleteRemote(fileId: String)

	@Query("DELETE FROM sync_remote")
	abstract suspend fun clearRemote()

	// Whole-table reads for building the replica.

	@Query("SELECT * FROM bookmarks")
	abstract suspend fun findAllBookmarks(): List<BookmarkEntity>

	@Query("SELECT * FROM scrobblings")
	abstract suspend fun findAllScrobblings(): List<ScrobblingEntity>

	@Query("SELECT * FROM stats")
	abstract suspend fun findAllStats(): List<StatsEntity>

	@Query("SELECT * FROM preferences")
	abstract suspend fun findAllMangaPrefs(): List<MangaPrefsEntity>

	@Transaction
	@Query("SELECT * FROM manga WHERE manga_id IN (:ids)")
	abstract suspend fun findManga(ids: Collection<Long>): List<MangaWithTags>

	@Query("SELECT details_updated_at FROM manga WHERE manga_id = :id")
	abstract suspend fun findDetailsUpdatedAt(id: Long): Long?

	@Query("SELECT * FROM track_logs WHERE manga_id = :mangaId ORDER BY id")
	abstract suspend fun findFeed(mangaId: Long): List<TrackLogEntity>

	@Query(
		"SELECT EXISTS(SELECT 1 FROM favourites WHERE manga_id = :mangaId AND deleted_at = 0) " +
			"OR EXISTS(SELECT 1 FROM history WHERE manga_id = :mangaId AND deleted_at = 0)",
	)
	abstract suspend fun isInLibrary(mangaId: Long): Boolean

	@Query("SELECT manga_id FROM manga WHERE source = :source")
	abstract suspend fun findMangaIds(source: String): LongArray

	// Deletes by the cross-device key, for applying remote tombstones.

	@Query("DELETE FROM bookmarks WHERE manga_id = :mangaId AND page_id = :pageId")
	abstract suspend fun deleteBookmark(mangaId: Long, pageId: Long)

	@Query("DELETE FROM scrobblings WHERE scrobbler = :scrobbler AND id = :id AND manga_id = :mangaId")
	abstract suspend fun deleteScrobbling(scrobbler: Int, id: Int, mangaId: Long)

	@Query("DELETE FROM stats WHERE manga_id = :mangaId AND started_at = :startedAt")
	abstract suspend fun deleteStats(mangaId: Long, startedAt: Long)

	@Query("UPDATE favourites SET deleted_at = :deletedAt WHERE manga_id = :mangaId AND category_id = :categoryId AND deleted_at = 0")
	abstract suspend fun softDeleteFavourite(mangaId: Long, categoryId: Long, deletedAt: Long)

	@Query("UPDATE favourite_categories SET deleted_at = :deletedAt WHERE category_id = :categoryId AND deleted_at = 0")
	abstract suspend fun softDeleteCategory(categoryId: Int, deletedAt: Long)

	@Query("UPDATE history SET deleted_at = :deletedAt WHERE manga_id = :mangaId AND deleted_at = 0")
	abstract suspend fun softDeleteHistory(mangaId: Long, deletedAt: Long)
}
