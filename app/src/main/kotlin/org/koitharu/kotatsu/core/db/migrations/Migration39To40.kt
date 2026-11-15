package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Sync triggers changed how they add tracking rows; drop them all so onOpen re-creates them. */
class Migration39To40 : Migration(39, 40) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.query("SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'sync\\_%' ESCAPE '\\'").use { c ->
			buildList { while (c.moveToNext()) add(c.getString(0)) }
		}.forEach { db.execSQL("DROP TRIGGER IF EXISTS `$it`") }
	}
}
