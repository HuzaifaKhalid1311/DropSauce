package org.koitharu.kotatsu.core.db.dao

import android.database.DatabaseUtils
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.core.db.MangaQueryBuilder
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.tracker.data.TrackLogEntity
import org.koitharu.kotatsu.tracker.data.TrackLogWithManga

@Dao
abstract class TrackLogsDao : MangaQueryBuilder.ConditionCallback {

	fun observeAll(
		limit: Int,
		filterOptions: Set<ListFilterOption>,
	): Flow<List<TrackLogWithManga>> = observeAllImpl(
		MangaQueryBuilder("track_logs", this)
			.filters(filterOptions)
			.limit(limit)
			.orderBy("created_at DESC")
			.build(),
	)

	@Query("DELETE FROM track_logs")
	abstract suspend fun clear()

	/** All visible feed rows, used by cloud sync. */
	@Query("SELECT * FROM track_logs")
	abstract suspend fun findAllForSync(): List<TrackLogEntity>

	@Query("SELECT DISTINCT manga_id FROM track_logs")
	abstract suspend fun findMangaIds(): LongArray

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	abstract suspend fun insert(entity: TrackLogEntity): Long

	@Query("DELETE FROM track_logs WHERE id = :id")
	abstract suspend fun delete(id: Long)

	@Query("SELECT * FROM track_logs WHERE id = :id")
	abstract suspend fun findById(id: Long): TrackLogEntity?

	@Query("UPDATE track_logs SET unread = 0 WHERE manga_id = :mangaId AND unread = 1")
	abstract suspend fun markAsRead(mangaId: Long)

	@Query("SELECT id FROM track_logs WHERE manga_id = :mangaId AND unread = 1")
	abstract suspend fun findUnreadIds(mangaId: Long): LongArray

	@Query("SELECT * FROM track_logs WHERE manga_id = :mangaId AND unread = 1")
	abstract suspend fun findUnreadByManga(mangaId: Long): List<TrackLogEntity>

	@Query("UPDATE track_logs SET unread = 0 WHERE id = :id")
	abstract suspend fun markLogAsRead(id: Long)

	@Query("UPDATE track_logs SET unread = 1 WHERE id IN (:ids)")
	abstract suspend fun markUnread(ids: Collection<Long>)

	@Query("DELETE FROM track_logs WHERE manga_id NOT IN (SELECT manga_id FROM tracks)")
	abstract suspend fun gc()

	@Query("DELETE FROM track_logs WHERE id IN (SELECT id FROM track_logs ORDER BY created_at DESC LIMIT 0 OFFSET :size)")
	abstract suspend fun trim(size: Int)

	@Query("SELECT COUNT(*) FROM track_logs")
	abstract suspend fun count(): Int

	@Query("SELECT COUNT(*) FROM track_logs WHERE unread = 1")
	abstract fun observeUnreadCount(): Flow<Int>

	@Transaction
	@RawQuery(observedEntities = [TrackLogEntity::class])
	protected abstract fun observeAllImpl(query: SupportSQLiteQuery): Flow<List<TrackLogWithManga>>

	override fun getCondition(option: ListFilterOption): String? = when (option) {
		ListFilterOption.Macro.FAVORITE -> "EXISTS(SELECT * FROM favourites WHERE favourites.manga_id = track_logs.manga_id)"
		is ListFilterOption.Favorite -> "EXISTS(SELECT * FROM favourites WHERE favourites.manga_id = track_logs.manga_id AND favourites.category_id = ${option.category.id})"
		is ListFilterOption.Tag -> "EXISTS(SELECT * FROM manga_tags WHERE manga_tags.manga_id = track_logs.manga_id AND tag_id = ${option.tagId})"
		ListFilterOption.Macro.NSFW -> "(SELECT nsfw FROM manga WHERE manga.manga_id = track_logs.manga_id) = 1"
		is ListFilterOption.State -> option.state?.let {
			"(SELECT state FROM manga WHERE manga.manga_id = track_logs.manga_id) = ${DatabaseUtils.sqlEscapeString(it.name)}"
		}

		else -> null
	}
}
