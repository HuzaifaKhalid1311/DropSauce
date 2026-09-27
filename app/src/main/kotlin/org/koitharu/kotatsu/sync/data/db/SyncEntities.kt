package org.koitharu.kotatsu.sync.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Sync version of one row of a synced table, kept by the triggers in [SyncTriggers]. [pk] is the
 * row's local primary key rendered as text; [syncKey] is the cross-device key when it can't be
 * derived from [pk] (category uid, feed entry content). [isDeleted] marks a hard-deleted row.
 */
@Entity(tableName = "sync_rows", primaryKeys = ["tbl", "pk"])
class SyncRowEntity(
	@ColumnInfo(name = "tbl") val table: String,
	@ColumnInfo(name = "pk") val pk: String,
	@ColumnInfo(name = "v") val version: Long,
	@ColumnInfo(name = "deleted") val isDeleted: Boolean,
	@ColumnInfo(name = "sync_key") val syncKey: String?,
)

/** Last-seen state of one synced SharedPreferences key. [hash] is null when the key is absent. */
@Entity(tableName = "sync_prefs", primaryKeys = ["file", "key"])
class SyncPrefEntity(
	@ColumnInfo(name = "file") val file: String,
	@ColumnInfo(name = "key") val key: String,
	@ColumnInfo(name = "hash") val hash: Long?,
	@ColumnInfo(name = "v") val version: Long,
)

/** md5 of each remote file already merged, so unchanged replicas are never downloaded again. */
@Entity(tableName = "sync_remote")
class SyncRemoteEntity(
	@PrimaryKey @ColumnInfo(name = "file_id") val fileId: String,
	@ColumnInfo(name = "md5") val md5: String,
)
