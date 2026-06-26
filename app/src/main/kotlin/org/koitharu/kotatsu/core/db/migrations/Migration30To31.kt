package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration30To31 : Migration(30, 31) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE tracks ADD COLUMN needs_preload INTEGER NOT NULL DEFAULT 1")
		// Existing rows with a known last_chapter_id are already preloaded
		db.execSQL("UPDATE tracks SET needs_preload = 0 WHERE last_chapter_id != 0")
	}
}
