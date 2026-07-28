package org.koitharu.kotatsu.core.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.MangaTagsEntity
import org.koitharu.kotatsu.core.db.entity.MangaWithTags
import org.koitharu.kotatsu.core.db.entity.TagEntity

data class LibrarySourceUsage(
	val source: String,
	val sourceTitle: String?,
	val mangaCount: Int,
)

@Dao
abstract class MangaDao {

	@Transaction
	@Query("SELECT * FROM manga WHERE manga_id = :id")
	abstract suspend fun find(id: Long): MangaWithTags?

	@Query("SELECT EXISTS(SELECT * FROM manga WHERE manga_id = :id)")
	abstract suspend operator fun contains(id: Long): Boolean

	@Transaction
	@Query("SELECT * FROM manga WHERE public_url = :publicUrl")
	abstract suspend fun findByPublicUrl(publicUrl: String): MangaWithTags?

	@Query("SELECT source_title FROM manga WHERE manga_id = :id")
	abstract suspend fun findSourceTitle(id: Long): String?

	@Query("SELECT details_updated_at FROM manga WHERE manga_id = :id")
	abstract suspend fun getDetailsUpdatedAt(id: Long): Long?

	@Transaction
	@Query("SELECT * FROM manga WHERE source = :source")
	abstract suspend fun findAllBySource(source: String): List<MangaWithTags>

	/**
	 * Restored Kotatsu library entries on built-in (non-Mihon) sources that still carry user data.
	 * Used by the Kotatsu→Mihon migration to find what needs re-keying. `MIHON_%` sources are the
	 * app's own external sources; `LOCAL`/`UNKNOWN` are not migratable.
	 */
	@Transaction
	@Query(
		"""
		SELECT * FROM manga
		WHERE source NOT LIKE 'MIHON\_%' ESCAPE '\'
			AND source NOT LIKE 'LN\_%' ESCAPE '\'
			AND source NOT IN ('LOCAL', 'UNKNOWN')
			AND manga_id IN (
				SELECT manga_id FROM favourites WHERE deleted_at = 0
				UNION SELECT manga_id FROM history WHERE deleted_at = 0
				UNION SELECT manga_id FROM bookmarks
				UNION SELECT manga_id FROM tracks
				UNION SELECT manga_id FROM scrobblings
			)
		""",
	)
	abstract suspend fun findLegacyMangaWithUserData(): List<MangaWithTags>

	/**
	 * Distinct external (`MIHON_<id>`) source names referenced by the user's library (favourites or
	 * history). Used to recommend installing the matching extensions for migrated entries whose
	 * extension isn't installed yet.
	 */
	@Query(
		"""
		SELECT DISTINCT source FROM manga
		WHERE (source LIKE 'MIHON\_%' ESCAPE '\' OR source LIKE 'LN\_%' ESCAPE '\')
			AND manga_id IN (
				SELECT manga_id FROM favourites WHERE deleted_at = 0
				UNION SELECT manga_id FROM history WHERE deleted_at = 0
			)
		""",
	)
	abstract suspend fun findExternalSourcesInLibrary(): List<String>

	/**
	 * Sources represented by manga in favourites or history. The source key is kept intact so a
	 * bulk repair can take a stable snapshot of every matching manga, including restored Kotatsu
	 * sources that have no Mihon counterpart.
	 */
	@Query(
		"""
		SELECT source, MAX(source_title) AS sourceTitle, COUNT(*) AS mangaCount
		FROM manga
		WHERE source NOT IN ('LOCAL', 'UNKNOWN')
			AND manga_id IN (
				SELECT manga_id FROM favourites WHERE deleted_at = 0
				UNION SELECT manga_id FROM history WHERE deleted_at = 0
			)
		GROUP BY source
		ORDER BY sourceTitle COLLATE NOCASE, source COLLATE NOCASE
		""",
	)
	abstract fun observeLibrarySourceUsage(): Flow<List<LibrarySourceUsage>>

	@Query(
		"""
		SELECT manga_id FROM manga
		WHERE source IN (:sources)
			AND manga_id IN (
				SELECT manga_id FROM favourites WHERE deleted_at = 0
				UNION SELECT manga_id FROM history WHERE deleted_at = 0
			)
		""",
	)
	abstract suspend fun findLibraryMangaIdsBySources(sources: Collection<String>): List<Long>

	@Transaction
	@Query(
		"""
		SELECT * FROM manga
		WHERE source IN (:sources)
			AND manga_id IN (
				SELECT manga_id FROM favourites WHERE deleted_at = 0
				UNION SELECT manga_id FROM history WHERE deleted_at = 0
			)
		ORDER BY title COLLATE NOCASE
		""",
	)
	abstract suspend fun findLibraryMangaBySources(sources: Collection<String>): List<MangaWithTags>

	@Query("SELECT author FROM manga WHERE author LIKE :query GROUP BY author ORDER BY COUNT(author) DESC LIMIT :limit")
	abstract suspend fun findAuthors(query: String, limit: Int): List<String>

    @Query("SELECT author FROM manga WHERE manga.source = :source AND author IS NOT NULL AND author != '' GROUP BY author ORDER BY COUNT(author) DESC LIMIT :limit")
    abstract suspend fun findAuthorsBySource(source: String, limit: Int): List<String>

	@Transaction
	@Query("SELECT * FROM manga WHERE (title LIKE :query OR alt_title LIKE :query) AND manga_id IN (SELECT manga_id FROM favourites UNION SELECT manga_id FROM history) LIMIT :limit")
	abstract suspend fun searchByTitle(query: String, limit: Int): List<MangaWithTags>

	@Transaction
	@Query("SELECT * FROM manga WHERE (title LIKE :query OR alt_title LIKE :query) AND source = :source AND manga_id IN (SELECT manga_id FROM favourites UNION SELECT manga_id FROM history) LIMIT :limit")
	abstract suspend fun searchByTitle(query: String, source: String, limit: Int): List<MangaWithTags>

	@Upsert
	protected abstract suspend fun upsert(manga: MangaEntity)

	@Update(onConflict = OnConflictStrategy.IGNORE)
	abstract suspend fun update(manga: MangaEntity): Int

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	abstract suspend fun insertTagRelation(tag: MangaTagsEntity): Long

	@Query("DELETE FROM manga_tags WHERE manga_id = :mangaId")
	abstract suspend fun clearTagRelation(mangaId: Long)

	@Transaction
	@Delete
	abstract suspend fun delete(subjects: Collection<MangaEntity>)

	@Query(
		"""
		DELETE FROM manga WHERE NOT EXISTS(SELECT * FROM history WHERE history.manga_id == manga.manga_id) 
			AND NOT EXISTS(SELECT * FROM favourites WHERE favourites.manga_id == manga.manga_id)
			AND NOT EXISTS(SELECT * FROM bookmarks WHERE bookmarks.manga_id == manga.manga_id)
			AND NOT EXISTS(SELECT * FROM suggestions WHERE suggestions.manga_id == manga.manga_id)
			AND NOT EXISTS(SELECT * FROM scrobblings WHERE scrobblings.manga_id == manga.manga_id)
			AND NOT EXISTS(SELECT * FROM local_index WHERE local_index.manga_id == manga.manga_id)
			AND manga.manga_id NOT IN (:idsToKeep)
		""",
	)
	abstract suspend fun cleanup(idsToKeep: Set<Long>)

	@Transaction
	open suspend fun upsert(manga: MangaEntity, tags: Iterable<TagEntity>? = null) {
		upsert(manga)
		if (tags != null) {
			clearTagRelation(manga.id)
			tags.map {
				MangaTagsEntity(manga.id, it.id)
			}.forEach {
				insertTagRelation(it)
			}
		}
	}
}
