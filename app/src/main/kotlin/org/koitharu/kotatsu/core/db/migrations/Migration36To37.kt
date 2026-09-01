package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration36To37 : Migration(36, 37) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("ALTER TABLE preferences ADD COLUMN `description_override` TEXT")
	}
}
