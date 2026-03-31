package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration27To28 : Migration(27, 28) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE manga ADD COLUMN source_title TEXT")
		db.execSQL("ALTER TABLE sources ADD COLUMN title TEXT")
	}
}
