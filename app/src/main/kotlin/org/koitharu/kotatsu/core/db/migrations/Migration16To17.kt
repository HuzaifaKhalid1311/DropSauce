package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration16To17 : Migration(16, 17) {

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("CREATE TABLE `sources` (`source` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `sort_key` INTEGER NOT NULL, PRIMARY KEY(`source`))")
		db.execSQL("CREATE INDEX `index_sources_sort_key` ON `sources` (`sort_key`)")
		// No built-in sources to migrate; extensions are managed externally
	}
}
