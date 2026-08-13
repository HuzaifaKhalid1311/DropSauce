package org.koitharu.kotatsu.scrobbling.common.data

import androidx.room.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

@Dao
abstract class ScrobblingDao {

	@Query("SELECT * FROM scrobblings WHERE scrobbler = :scrobbler AND manga_id = :mangaId")
	abstract suspend fun find(scrobbler: Int, mangaId: Long): ScrobblingEntity?

	@Query("SELECT * FROM scrobblings WHERE scrobbler = :scrobbler AND manga_id = :mangaId")
	abstract fun observe(scrobbler: Int, mangaId: Long): Flow<ScrobblingEntity?>

	@Query("SELECT * FROM scrobblings WHERE scrobbler = :scrobbler")
	abstract fun observe(scrobbler: Int): Flow<List<ScrobblingEntity>>

	@Query("SELECT * FROM scrobblings WHERE manga_id = :mangaId")
	abstract suspend fun findAll(mangaId: Long): List<ScrobblingEntity>

	/**
	 * Other manga linked to the very same remote entry on the same tracker — a definite duplicate,
	 * whatever the titles happen to look like. Mirrors mihon's `track_dupes` CTE.
	 */
	@Query(
		"SELECT DISTINCT s2.manga_id FROM scrobblings s1 " +
			"INNER JOIN scrobblings s2 ON s1.scrobbler = s2.scrobbler " +
			"AND s1.target_id = s2.target_id AND s1.manga_id != s2.manga_id " +
			"WHERE s1.manga_id = :mangaId",
	)
	abstract suspend fun findLinkedMangaIds(mangaId: Long): LongArray

	@Upsert
	abstract suspend fun upsert(entity: ScrobblingEntity)

	@Query("DELETE FROM scrobblings WHERE scrobbler = :scrobbler AND manga_id = :mangaId")
	abstract suspend fun delete(scrobbler: Int, mangaId: Long)

	@Query("SELECT * FROM scrobblings ORDER BY scrobbler LIMIT :limit OFFSET :offset")
	protected abstract suspend fun findAll(offset: Int, limit: Int): List<ScrobblingEntity>

	fun dumpEnabled(): Flow<ScrobblingEntity> = flow {
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
