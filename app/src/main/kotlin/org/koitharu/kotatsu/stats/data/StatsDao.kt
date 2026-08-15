package org.koitharu.kotatsu.stats.data

import androidx.room.Dao
import androidx.room.MapColumn
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import kotlin.collections.forEach

@Dao
abstract class StatsDao {

	@Query("SELECT * FROM stats WHERE manga_id = :mangaId ORDER BY started_at")
	abstract suspend fun findAll(mangaId: Long): List<StatsEntity>

	@Query("SELECT IFNULL(SUM(pages),0) FROM stats WHERE manga_id = :mangaId")
	abstract suspend fun getReadPagesCount(mangaId: Long): Int

	@Query("SELECT IFNULL(SUM(duration), 0) FROM stats WHERE chapters > 0")
	abstract suspend fun getTotalReadDurationWithChapters(): Long

	@Query("SELECT IFNULL(SUM(chapters), 0) FROM stats")
	abstract suspend fun getTotalReadChapters(): Int

	@Query("SELECT started_at, duration FROM stats WHERE started_at + duration >= :fromDate ORDER BY started_at")
	abstract suspend fun getDurationEntriesIntersecting(fromDate: Long): List<DurationEntry>

	@Query("DELETE FROM stats")
	abstract suspend fun clear()

	@Query("SELECT COUNT(*) FROM stats WHERE manga_id = :mangaId")
	abstract fun observeRowCount(mangaId: Long): Flow<Int>

	@Upsert
	abstract suspend fun upsert(entity: StatsEntity)

	suspend fun getDurationStats(fromDate: Long, favouriteCategories: Set<Long>): Map<MangaEntity, Long> {
		val where = whereClause(fromDate, favouriteCategories)
		// INNER JOIN: a stats row whose manga was purged would otherwise group under a NULL manga_id
		// and hand Room an all-null MangaEntity.
		val query = SimpleSQLiteQuery(
			"SELECT manga.*, SUM(duration) AS d FROM stats JOIN manga ON manga.manga_id = stats.manga_id WHERE $where GROUP BY manga.manga_id ORDER BY d DESC",
		)
		return getDurationStatsImpl(query)
	}

	@RawQuery
	protected abstract suspend fun getDurationStatsImpl(
		query: SupportSQLiteQuery
	): Map<@MapColumn("manga") MangaEntity, @MapColumn("d") Long>

	/**
	 * Raw reading sessions for a period, filtered exactly like [getDurationStats] so the summary
	 * numbers, the activity chart and the per-title breakdown always describe the same data set.
	 */
	suspend fun getSessions(fromDate: Long, favouriteCategories: Set<Long>): List<StatsEntity> {
		val where = whereClause(fromDate, favouriteCategories)
		return getSessionsImpl(
			SimpleSQLiteQuery(
				"SELECT stats.* FROM stats JOIN manga ON manga.manga_id = stats.manga_id WHERE $where ORDER BY started_at",
			),
		)
	}

	@RawQuery
	protected abstract suspend fun getSessionsImpl(query: SupportSQLiteQuery): List<StatsEntity>

	/** Shared by both statistics queries so their totals can never describe different rows. */
	private fun whereClause(fromDate: Long, favouriteCategories: Set<Long>): String {
		val conditions = ArrayList<String>(3)
		conditions.add("(SELECT deleted_at FROM history WHERE history.manga_id = stats.manga_id) = 0")
		conditions.add("stats.started_at >= $fromDate")
		if (favouriteCategories.isNotEmpty()) {
			val ids = favouriteCategories.joinToString(",")
			conditions.add("stats.manga_id IN (SELECT manga_id FROM favourites WHERE category_id IN ($ids))")
		}
		return conditions.joinToString(separator = " AND ")
	}

	@Query("SELECT * FROM stats ORDER BY started_at LIMIT :limit OFFSET :offset")
	protected abstract suspend fun findAll(offset: Int, limit: Int): List<StatsEntity>
	fun dumpEnabled(): Flow<StatsEntity> = flow {
		val window = 10
		var offset = 0
		while (currentCoroutineContext().isActive) {
			val list = findAll(offset, window)
			if (list.isEmpty()) {
				break
			}
			offset += window
			list.forEach { emit(it) }
		}
	}
}
