package org.koitharu.kotatsu.sync.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * SQLite triggers that stamp every synced row with a version whenever its content changes, and
 * leave a tombstone when it is deleted. Living in the database means every write path (UI, backup
 * restore, Mihon import, migrations) is covered without any of them knowing about sync.
 *
 * Versions are `max(now, previous + 1)`: an edit always beats the version it replaced, even when the
 * clocks of two devices disagree. The sync applier writes remote rows and then overwrites the version
 * with the remote one inside the same transaction.
 */
object SyncTriggers {

	const val NOW = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"

	const val FAVOURITES = "favourites"
	const val CATEGORIES = "favourite_categories"
	const val HISTORY = "history"
	const val BOOKMARKS = "bookmarks"
	const val SCROBBLINGS = "scrobblings"
	const val STATS = "stats"
	const val FEED = "track_logs"
	const val MANGA_PREFS = "preferences"

	private class Tracked(
		val table: String,
		/** Primary key expression; `%` is replaced by NEW or OLD. */
		val pk: String,
		/** Columns whose change is a real edit. Anything else (e.g. a no-op rewrite) keeps the version. */
		val columns: List<String>,
		/** Tables that soft-delete through `deleted_at`: their own GC of old tombstones drops tracking. */
		val isSoftDelete: Boolean,
		/** Cross-device key refreshed on every write, when it derives from content. */
		val contentKey: String? = null,
		/** Cross-device key assigned once, on first insert. */
		val initialKey: String? = null,
	)

	private val tracked = listOf(
		Tracked(
			table = FAVOURITES,
			pk = "%.manga_id || ':' || %.category_id",
			columns = listOf("sort_key", "pinned", "created_at", "deleted_at"),
			isSoftDelete = true,
		),
		Tracked(
			table = CATEGORIES,
			pk = "%.category_id",
			columns = listOf(
				"created_at", "sort_key", "title", "`order`", "track", "download_new_chapters", "show_in_lib",
				"deleted_at",
			),
			isSoftDelete = true,
			// Title-derived so the same category created on two devices (or restored from a backup) is
			// one category everywhere. Renames keep the uid; the builder re-keys same-uid collisions.
			initialKey = "'t:' || trim(%.title)",
		),
		Tracked(
			table = HISTORY,
			pk = "%.manga_id",
			columns = listOf("created_at", "updated_at", "chapter_id", "page", "scroll", "percent", "deleted_at", "chapters"),
			isSoftDelete = true,
		),
		Tracked(
			table = BOOKMARKS,
			pk = "%.manga_id || ':' || %.page_id",
			columns = listOf("chapter_id", "page", "scroll", "image", "created_at", "percent"),
			isSoftDelete = false,
		),
		Tracked(
			table = SCROBBLINGS,
			pk = "%.scrobbler || ':' || %.id || ':' || %.manga_id",
			columns = listOf("target_id", "status", "chapter", "comment", "rating"),
			isSoftDelete = false,
		),
		Tracked(
			table = STATS,
			pk = "%.manga_id || ':' || %.started_at",
			columns = listOf("duration", "pages", "chapters"),
			isSoftDelete = false,
		),
		Tracked(
			table = FEED,
			pk = "%.id",
			columns = listOf("chapters", "chapter_ids", "created_at", "unread"),
			isSoftDelete = false,
			contentKey = FEED_KEY,
		),
		Tracked(
			table = MANGA_PREFS,
			pk = "%.manga_id",
			columns = listOf(
				"mode", "cf_brightness", "cf_contrast", "cf_invert", "cf_grayscale", "cf_book", "title_override",
				"cover_override", "description_override", "content_rating_override", "merge_scanlators",
			),
			isSoftDelete = false,
		),
	)

	/** Idempotent; runs on every open so a fresh install and an upgraded install end up identical. */
	fun create(db: SupportSQLiteDatabase) {
		for (t in tracked) {
			val newPk = t.pk.replace("%", "NEW")
			val oldPk = t.pk.replace("%", "OLD")
			val newKey = (t.contentKey ?: t.initialKey)?.replace("%", "NEW") ?: "NULL"
			val keyUpdate = t.contentKey?.let { ", sync_key = ${it.replace("%", "NEW")}" }.orEmpty()
			val bump = """
				INSERT OR IGNORE INTO sync_rows (tbl, pk, v, deleted, sync_key) VALUES ('${t.table}', $newPk, 0, 0, $newKey);
				UPDATE sync_rows SET v = MAX($NOW, v + 1), deleted = 0$keyUpdate WHERE tbl = '${t.table}' AND pk = $newPk;
			""".trimIndent()
			val changed = t.columns.joinToString(" OR ") { "NEW.$it IS NOT OLD.$it" }
			db.execSQL("CREATE TRIGGER IF NOT EXISTS sync_${t.table}_ins AFTER INSERT ON ${t.table} BEGIN $bump END")
			db.execSQL("CREATE TRIGGER IF NOT EXISTS sync_${t.table}_upd AFTER UPDATE ON ${t.table} WHEN $changed BEGIN $bump END")
			val oldKey = (t.contentKey ?: t.initialKey)?.replace("%", "OLD") ?: "NULL"
			val tombstone = """
				INSERT OR IGNORE INTO sync_rows (tbl, pk, v, deleted, sync_key) VALUES ('${t.table}', $oldPk, 0, 1, $oldKey);
				UPDATE sync_rows SET v = MAX($NOW, v + 1), deleted = 1 WHERE tbl = '${t.table}' AND pk = $oldPk;
			""".trimIndent()
			if (t.isSoftDelete) {
				// A live row deleted outright (e.g. by a cascade) is a real deletion; an already
				// soft-deleted row being purged by GC is not news for anyone.
				db.execSQL(
					"CREATE TRIGGER IF NOT EXISTS sync_${t.table}_del AFTER DELETE ON ${t.table} " +
						"WHEN OLD.deleted_at = 0 BEGIN $tombstone END",
				)
				db.execSQL(
					"CREATE TRIGGER IF NOT EXISTS sync_${t.table}_gc AFTER DELETE ON ${t.table} " +
						"WHEN OLD.deleted_at != 0 BEGIN DELETE FROM sync_rows WHERE tbl = '${t.table}' AND pk = $oldPk; END",
				)
			} else {
				db.execSQL("CREATE TRIGGER IF NOT EXISTS sync_${t.table}_del AFTER DELETE ON ${t.table} BEGIN $tombstone END")
			}
		}
	}

	/** Seeds versions for rows that existed before change tracking, from their own timestamps. */
	fun backfill(db: SupportSQLiteDatabase) {
		db.execSQL(
			"INSERT OR IGNORE INTO sync_rows SELECT '$FAVOURITES', manga_id || ':' || category_id, " +
				"MAX(created_at, deleted_at), 0, NULL FROM favourites",
		)
		db.execSQL(
			"INSERT OR IGNORE INTO sync_rows SELECT '$CATEGORIES', category_id, MAX(created_at, deleted_at), 0, " +
				"'t:' || trim(title) FROM favourite_categories",
		)
		db.execSQL(
			"INSERT OR IGNORE INTO sync_rows SELECT '$HISTORY', manga_id, MAX(updated_at, deleted_at), 0, NULL FROM history",
		)
		db.execSQL(
			"INSERT OR IGNORE INTO sync_rows SELECT '$BOOKMARKS', manga_id || ':' || page_id, created_at, 0, NULL FROM bookmarks",
		)
		db.execSQL(
			"INSERT OR IGNORE INTO sync_rows SELECT '$SCROBBLINGS', scrobbler || ':' || id || ':' || manga_id, 0, 0, NULL " +
				"FROM scrobblings",
		)
		db.execSQL(
			"INSERT OR IGNORE INTO sync_rows SELECT '$STATS', manga_id || ':' || started_at, started_at + duration, 0, NULL " +
				"FROM stats",
		)
		db.execSQL(
			"INSERT OR IGNORE INTO sync_rows SELECT '$FEED', id, created_at, 0, ${FEED_KEY.replace("%.", "")} FROM track_logs",
		)
		db.execSQL("INSERT OR IGNORE INTO sync_rows SELECT '$MANGA_PREFS', manga_id, 0, 0, NULL FROM preferences")
	}

	/** Raw feed identity: chapter ids when known, titles for rows written before ids were stored. */
	private const val FEED_KEY =
		"%.manga_id || ':' || (CASE WHEN %.chapter_ids = '' THEN 't:' || %.chapters ELSE %.chapter_ids END)"
}
