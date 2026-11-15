package org.koitharu.kotatsu.sync.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.sync.data.model.SyncContent
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent settings for Google Drive sync, in their own prefs file so they never travel with the
 * synced or backed-up app settings. No OAuth tokens are stored here — Play Services manages those.
 */
@Singleton
class SyncSettings @Inject constructor(@ApplicationContext context: Context) {

	private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

	// In noBackupFilesDir: an Android auto-backup restored onto a second phone must not give it this
	// device's id, or both would write the same replica.
	private val deviceIdFile = File(context.noBackupFilesDir, "sync_device_id")

	init {
		// Settings of the previous sync implementation.
		if (prefs.contains(KEY_LEGACY_CONFIG_HASH) || prefs.contains(KEY_LEGACY_DEVICE_ID)) {
			prefs.edit { LEGACY_KEYS.forEach { remove(it) } }
		}
	}

	var accountEmail: String?
		get() = prefs.getString(KEY_ACCOUNT_EMAIL, null)
		set(value) = prefs.edit { putString(KEY_ACCOUNT_EMAIL, value) }

	var accountName: String?
		get() = prefs.getString(KEY_ACCOUNT_NAME, null)
		set(value) = prefs.edit { putString(KEY_ACCOUNT_NAME, value) }

	var accountPhotoUrl: String?
		get() = prefs.getString(KEY_ACCOUNT_PHOTO, null)
		set(value) = prefs.edit { putString(KEY_ACCOUNT_PHOTO, value) }

	/** When true the account name and email are blurred in the UI. */
	var isEmailHidden: Boolean
		get() = prefs.getBoolean(KEY_EMAIL_HIDDEN, false)
		set(value) = prefs.edit { putBoolean(KEY_EMAIL_HIDDEN, value) }

	val isSignedIn: Boolean
		get() = !accountEmail.isNullOrEmpty()

	val deviceId: String by lazy {
		deviceIdFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
			?: UUID.randomUUID().toString().replace("-", "").also { deviceIdFile.writeText(it) }
	}

	/** Background sync interval in minutes; 0 turns background sync off. Defaults to every 6 hours. */
	var intervalMinutes: Int
		get() = prefs.getString(KEY_INTERVAL, "360")?.toIntOrNull() ?: 360
		set(value) = prefs.edit { putString(KEY_INTERVAL, value.toString()) }

	var isWifiOnly: Boolean
		get() = prefs.getBoolean(KEY_WIFI_ONLY, false)
		set(value) = prefs.edit { putBoolean(KEY_WIFI_ONLY, value) }

	var enabledContent: Set<String>
		get() = prefs.getStringSet(KEY_CONTENT, null) ?: SyncContent.DEFAULT
		set(value) = prefs.edit { putStringSet(KEY_CONTENT, value) }

	var lastSyncTimestamp: Long
		get() = prefs.getLong(KEY_LAST_SYNC, 0L)
		set(value) = prefs.edit { putLong(KEY_LAST_SYNC, value) }

	var lastSyncError: String?
		get() = prefs.getString(KEY_LAST_ERROR, null)
		set(value) = prefs.edit { putString(KEY_LAST_ERROR, value) }

	/** sha-1 of the replica content last uploaded, to skip identical uploads. */
	var lastUploadHash: String?
		get() = prefs.getString(KEY_UPLOAD_HASH, null)
		set(value) = prefs.edit { putString(KEY_UPLOAD_HASH, value) }

	/** Change-tracking fingerprint at the last replica build; equal means nothing changed locally. */
	var lastBuildFingerprint: String?
		get() = prefs.getString(KEY_BUILD_FINGERPRINT, null)
		set(value) = prefs.edit { putString(KEY_BUILD_FINGERPRINT, value) }

	/** The "what to sync" selection the last successful sync ran with. */
	var lastSyncedContent: Set<String>?
		get() = prefs.getStringSet(KEY_SYNCED_CONTENT, null)
		set(value) = prefs.edit { putStringSet(KEY_SYNCED_CONTENT, value) }

	var lastHousekeeping: Long
		get() = prefs.getLong(KEY_HOUSEKEEPING, 0L)
		set(value) = prefs.edit { putLong(KEY_HOUSEKEEPING, value) }

	/** When this install first synced with replicas; an older-format file written later means an old app. */
	var replicaSince: Long
		get() = prefs.getLong(KEY_REPLICA_SINCE, 0L)
		set(value) = prefs.edit { putLong(KEY_REPLICA_SINCE, value) }

	/** Last time the old single-file format was written by a not-yet-updated device, or 0. */
	var legacyWriterSeenAt: Long
		get() = prefs.getLong(KEY_LEGACY_WRITER, 0L)
		set(value) = prefs.edit { putLong(KEY_LEGACY_WRITER, value) }

	/** Forget everything tied to the Drive account (not user preferences like interval or content). */
	fun clearAccount() {
		prefs.edit {
			remove(KEY_ACCOUNT_EMAIL)
			remove(KEY_ACCOUNT_NAME)
			remove(KEY_ACCOUNT_PHOTO)
			remove(KEY_LAST_ERROR)
		}
		clearRemoteState()
	}

	/** Forget what is in Drive, so the next sync re-reads and re-uploads everything. */
	fun clearRemoteState() = prefs.edit {
		remove(KEY_LAST_SYNC)
		remove(KEY_UPLOAD_HASH)
		remove(KEY_BUILD_FINGERPRINT)
		remove(KEY_LEGACY_WRITER)
		remove(KEY_SYNCED_CONTENT)
		remove(KEY_HOUSEKEEPING)
		remove(KEY_REPLICA_SINCE) // the next sync joins that cloud again
	}

	companion object {

		private const val PREFS_NAME = "sync"
		private const val KEY_ACCOUNT_EMAIL = "account_email"
		private const val KEY_ACCOUNT_NAME = "account_name"
		private const val KEY_ACCOUNT_PHOTO = "account_photo"
		private const val KEY_EMAIL_HIDDEN = "email_hidden"
		private const val KEY_INTERVAL = "interval"
		private const val KEY_WIFI_ONLY = "wifi_only"
		private const val KEY_CONTENT = "content"
		private const val KEY_LAST_SYNC = "last_sync"
		private const val KEY_LAST_ERROR = "last_error"
		private const val KEY_UPLOAD_HASH = "upload_hash"
		private const val KEY_BUILD_FINGERPRINT = "build_fingerprint"
		private const val KEY_HOUSEKEEPING = "housekeeping"
		private const val KEY_SYNCED_CONTENT = "synced_content"
		private const val KEY_REPLICA_SINCE = "replica_since"
		private const val KEY_LEGACY_WRITER = "legacy_writer"

		private const val KEY_LEGACY_CONFIG_HASH = "config_hash"
		private const val KEY_LEGACY_DEVICE_ID = "device_id"
		private val LEGACY_KEYS = arrayOf(
			KEY_LEGACY_DEVICE_ID,
			KEY_LEGACY_CONFIG_HASH,
			"config_revision",
			"feed_baseline",
			"sync_on_start",
			"disable_deletion_sync",
		)
	}
}
