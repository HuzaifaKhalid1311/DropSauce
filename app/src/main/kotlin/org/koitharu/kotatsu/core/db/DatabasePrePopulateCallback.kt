package org.koitharu.kotatsu.core.db

import android.content.res.Resources
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.sync.data.db.SyncTriggers

class DatabasePrePopulateCallback(private val resources: Resources) : RoomDatabase.Callback() {

	override fun onCreate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"INSERT INTO favourite_categories (created_at, sort_key, title, `order`, track, download_new_chapters, show_in_lib, `deleted_at`) VALUES (?,?,?,?,?,?,?,?)",
			arrayOf<Any?>(
				System.currentTimeMillis(),
				1,
				resources.getString(R.string.read_later),
				SortOrder.NEWEST.name,
				1,
				0,
				1,
				0L,
			)
		)
	}

	// After onCreate on purpose: the pre-populated category stays untracked (version 0), so on a fresh
	// install it never outranks the same category coming from another device.
	override fun onOpen(db: SupportSQLiteDatabase) {
		SyncTriggers.create(db)
	}
}
