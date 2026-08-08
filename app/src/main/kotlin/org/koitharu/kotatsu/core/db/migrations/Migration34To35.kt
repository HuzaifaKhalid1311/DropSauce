package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration34To35 : Migration(34, 35) {

	override fun migrate(db: SupportSQLiteDatabase) {
		// Backs the "chapter fetch date" sort. Existing chapters keep 0 until their manga is
		// refreshed, which sorts them as oldest.
		db.execSQL("ALTER TABLE chapters ADD COLUMN date_fetch INTEGER NOT NULL DEFAULT 0")
		// UPDATED and RELEVANCE are gone: the former duplicated LATEST_CHAPTER, the latter only
		// ever applied to Suggestions.
		db.execSQL("UPDATE favourite_categories SET `order` = 'LATEST_CHAPTER' WHERE `order` = 'UPDATED'")
		db.execSQL("UPDATE favourite_categories SET `order` = 'NEWEST' WHERE `order` = 'RELEVANCE'")
	}
}
