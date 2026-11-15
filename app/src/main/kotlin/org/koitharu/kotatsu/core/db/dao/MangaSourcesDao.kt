package org.koitharu.kotatsu.core.db.dao

import androidx.room.Dao
import androidx.room.Query

/** Legacy table: source state now lives in SharedPreferences; only the Cloudflare marker is still written. */
@Dao
abstract class MangaSourcesDao {

	@Query("UPDATE sources SET cf_state = :state WHERE source = :source")
	abstract suspend fun setCfState(source: String, state: Int)
}
