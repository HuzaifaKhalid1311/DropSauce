package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.koitharu.kotatsu.sync.data.db.SyncTriggers

/** Change tracking for Google Drive sync. Additive only: existing tables are left untouched. */
class Migration37To38 : Migration(37, 38) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `sync_rows` (`tbl` TEXT NOT NULL, `pk` TEXT NOT NULL, `v` INTEGER NOT NULL, " +
				"`deleted` INTEGER NOT NULL, `sync_key` TEXT, PRIMARY KEY(`tbl`, `pk`))",
		)
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `sync_prefs` (`file` TEXT NOT NULL, `key` TEXT NOT NULL, `hash` INTEGER, " +
				"`v` INTEGER NOT NULL, PRIMARY KEY(`file`, `key`))",
		)
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `sync_remote` (`file_id` TEXT NOT NULL, `md5` TEXT NOT NULL, PRIMARY KEY(`file_id`))",
		)
		SyncTriggers.backfill(db)
	}
}
