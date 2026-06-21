package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration30To31 : Migration(30, 31) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE scrobblings ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
	}
}
