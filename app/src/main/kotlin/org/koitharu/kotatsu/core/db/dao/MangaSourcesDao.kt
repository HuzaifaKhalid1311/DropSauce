package org.koitharu.kotatsu.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import org.koitharu.kotatsu.core.db.entity.MangaSourceEntity
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper.PROTECTION_CAPTCHA

@Dao
abstract class MangaSourcesDao {

	@Query("SELECT * FROM sources ORDER BY pinned DESC, sort_key")
	abstract suspend fun findAll(): List<MangaSourceEntity>

	@Query("SELECT * FROM sources WHERE added_in >= :version")
	abstract suspend fun findAllFromVersion(version: Int): List<MangaSourceEntity>

	@Query("SELECT * FROM sources ORDER BY used_at DESC LIMIT :limit")
	abstract suspend fun findLastUsed(limit: Int): List<MangaSourceEntity>

	@Query("SELECT * FROM sources ORDER BY pinned DESC, sort_key")
	abstract fun observeAll(): Flow<List<MangaSourceEntity>>

	@Query("UPDATE sources SET used_at = :value WHERE source = :source")
	abstract suspend fun setLastUsed(source: String, value: Long)

	@Query("UPDATE sources SET pinned = :isPinned WHERE source = :source")
	abstract suspend fun setPinned(source: String, isPinned: Boolean)

	@Query("UPDATE sources SET cf_state = :state WHERE source = :source")
	abstract suspend fun setCfState(source: String, state: Int)

	@Query("SELECT title FROM sources WHERE source = :source LIMIT 1")
	abstract suspend fun findTitle(source: String): String?

	@Query("UPDATE sources SET title = :title WHERE source = :source")
	abstract suspend fun setTitle(source: String, title: String)

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	@Transaction
	abstract suspend fun insertIfAbsent(entries: Collection<MangaSourceEntity>)

	@Upsert
	abstract suspend fun upsert(entry: MangaSourceEntity)

	@Query("SELECT * FROM sources WHERE pinned = 1")
	abstract suspend fun findAllPinned(): List<MangaSourceEntity>

	@Query("SELECT * FROM sources WHERE cf_state = $PROTECTION_CAPTCHA")
	abstract suspend fun findAllCaptchaRequired(): List<MangaSourceEntity>

	fun dumpEnabled(): Flow<MangaSourceEntity> = flow {
		val window = 10
		var offset = 0
		while (currentCoroutineContext().isActive) {
			val list = findAllEnabled(offset, window)
			if (list.isEmpty()) {
				break
			}
			offset += window
			list.forEach { emit(it) }
		}
	}

	@Query("SELECT * FROM sources WHERE enabled = 1 ORDER BY source LIMIT :limit OFFSET :offset")
	protected abstract suspend fun findAllEnabled(offset: Int, limit: Int): List<MangaSourceEntity>
}
