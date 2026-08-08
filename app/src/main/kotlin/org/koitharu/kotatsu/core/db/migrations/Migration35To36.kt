package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration35To36 : Migration(35, 36) {

	override fun migrate(db: SupportSQLiteDatabase) {
		// Drops the date_fetch column added in 34->35: the "chapter fetch date" sort it backed is
		// gone. Rebuilt rather than ALTER ... DROP COLUMN, which needs SQLite 3.35 (Android 14).
		db.execSQL(
			"CREATE TABLE IF NOT EXISTS `chapters_new` (`chapter_id` INTEGER NOT NULL, " +
				"`manga_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `number` REAL NOT NULL, " +
				"`volume` INTEGER NOT NULL, `url` TEXT NOT NULL, `scanlator` TEXT, " +
				"`upload_date` INTEGER NOT NULL, `branch` TEXT, `source` TEXT NOT NULL, " +
				"`index` INTEGER NOT NULL, PRIMARY KEY(`manga_id`, `chapter_id`), " +
				"FOREIGN KEY(`manga_id`) REFERENCES `manga`(`manga_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
		)
		db.execSQL(
			"INSERT INTO chapters_new SELECT chapter_id, manga_id, name, number, volume, url, " +
				"scanlator, upload_date, branch, source, `index` FROM chapters",
		)
		db.execSQL("DROP TABLE chapters")
		db.execSQL("ALTER TABLE chapters_new RENAME TO chapters")
		// The sorts these named are gone too, so fall the affected categories back to the default.
		db.execSQL(
			"UPDATE favourite_categories SET `order` = 'NEWEST' WHERE `order` IN " +
				"('RATING', 'RATING_ASC', 'TRACKER_SCORE', 'TRACKER_SCORE_ASC', " +
				"'LAST_CHECK', 'LAST_CHECK_ASC', 'CHAPTER_FETCH_DATE', 'CHAPTER_FETCH_DATE_ASC')",
		)
	}
}
