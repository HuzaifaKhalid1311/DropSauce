package org.koitharu.kotatsu.sync.domain

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.koitharu.kotatsu.backup.local.data.model.BackupPrimitive
import org.koitharu.kotatsu.backup.local.data.model.BookmarkBackup
import org.koitharu.kotatsu.backup.local.data.model.MangaBackup
import org.koitharu.kotatsu.backup.local.data.model.ScrobblingBackup
import org.koitharu.kotatsu.backup.local.data.model.SourceSettingsBackup
import org.koitharu.kotatsu.backup.local.data.model.StatsBackup
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaWithTags
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sync.data.model.SyncTrack
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.reader.data.TapGridSettings
import org.koitharu.kotatsu.sync.data.GoogleDriveApi
import org.koitharu.kotatsu.sync.data.GoogleDriveAuth
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.data.model.SyncCategory
import org.koitharu.kotatsu.sync.data.model.SyncConfig
import org.koitharu.kotatsu.sync.data.model.SyncContent
import org.koitharu.kotatsu.sync.data.model.SyncFavourite
import org.koitharu.kotatsu.sync.data.model.SyncHistory
import org.koitharu.kotatsu.sync.data.model.SyncMangaPrefs
import org.koitharu.kotatsu.sync.data.model.SyncSnapshot
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SyncResult {
	data object Success : SyncResult
	data object SignInRequired : SyncResult
	data object AlreadyRunning : SyncResult

	/** [retryable] is false for errors that won't fix themselves (e.g. a newer remote format). */
	data class Error(val message: String?, val retryable: Boolean = true) : SyncResult
}

/**
 * Orchestrates a full two-way Google Drive sync: pull the remote snapshot, merge it with the local
 * database (per-record, tombstone-aware), apply the merged result locally, then push it back. Row
 * data (favourites/categories/history) propagates deletions via tombstones; the config bundle
 * (settings/reader-grid/source-settings/custom-covers) is last-writer-wins by revision.
 *
 * "What to sync" gates which sections this device reads & writes. Disabled sections are passed
 * through unchanged from the remote snapshot, so opting out on one device never erases another's data.
 */
@Singleton
class GoogleDriveSyncRepository @Inject constructor(
	@ApplicationContext private val context: Context,
	private val database: MangaDatabase,
	private val appSettings: AppSettings,
	private val tapGridSettings: TapGridSettings,
	private val syncSettings: SyncSettings,
	private val auth: GoogleDriveAuth,
	private val api: GoogleDriveApi,
) {

	private val json = Json {
		encodeDefaults = true
		ignoreUnknownKeys = true
		allowSpecialFloatingPointValues = true
		coerceInputValues = true
	}

	/** Whether a sync is currently running (so the UI can show progress and avoid overlap). */
	val isSyncing = MutableStateFlow(false)
	private val syncMutex = Mutex()

	/** Records the signed-in account. Email/name/photo come straight from GoogleSignIn — no network call. */
	fun onSignedIn(email: String?, displayName: String?, photoUrl: String?) {
		syncSettings.accountEmail = email?.ifBlank { null } ?: "Google Drive"
		syncSettings.accountName = displayName
		syncSettings.accountPhotoUrl = photoUrl
		Log.i(TAG, "signed in as ${syncSettings.accountEmail}")
	}

	suspend fun sync(): SyncResult {
		if (!syncSettings.isSignedIn) return SyncResult.SignInRequired
		if (!syncMutex.tryLock()) return SyncResult.AlreadyRunning
		isSyncing.value = true
		try {
			var token = auth.requireAccessToken()
			try {
				performSync(token)
			} catch (e: SyncApiException) {
				// A cached token can be stale → 401. Refresh once and retry.
				if (e.code == 401) {
					Log.w(TAG, "token rejected (401), refreshing and retrying")
					auth.invalidateToken(token)
					token = auth.requireAccessToken()
					performSync(token)
				} else {
					throw e
				}
			}
			return SyncResult.Success
		} catch (e: SyncSignInRequiredException) {
			Log.w(TAG, "sign-in required", e)
			return SyncResult.SignInRequired
		} catch (e: SyncSchemaException) {
			// Remote was written by a newer app version — never overwrite it. Don't retry either.
			Log.e(TAG, "remote schema too new", e)
			syncSettings.lastSyncError = e.message
			return SyncResult.Error(e.message, retryable = false)
		} catch (e: Exception) {
			Log.e(TAG, "sync failed", e)
			syncSettings.lastSyncError = e.message ?: e.javaClass.simpleName
			return SyncResult.Error(e.message ?: e.javaClass.simpleName)
		} finally {
			isSyncing.value = false
			syncMutex.unlock()
		}
	}

	private suspend fun performSync(token: String) {
		val enabled = SyncContent.fromKeys(syncSettings.enabledContent)
		val now = System.currentTimeMillis()
		Log.i(TAG, "sync start: enabled=$enabled")

		// Retry loop for optimistic concurrency: if another device writes the canonical file between
		// our read and our write, we re-read and re-merge so its changes are never lost. Bounded; the
		// last attempt writes best-effort (the per-record merge converges on the next sync regardless).
		var attempt = 0
		while (true) {
			val files = api.findSyncFiles(token)
			val canonical = files.firstOrNull() // oldest file is the single source of truth
			val baseVersion = canonical?.version

			// Download + decode every file. A download failure THROWS out of here — we must never let a
			// transient network/auth error look like "no remote" and overwrite good cloud data locally.
			val remotes = ArrayList<SyncSnapshot>(files.size)
			val decodedIds = HashSet<String>(files.size)
			for (file in files) {
				val snapshot = api.download(token, file.id) { source ->
					decodeSnapshot(source)
				}
				if (snapshot != null) {
					remotes += snapshot
					decodedIds += file.id
				}
			}
			val remote = SyncMerger.combine(remotes)
			Log.i(
				TAG,
				"remote: files=${files.size} readable=${remotes.size} fav=${remote?.favourites?.size} " +
					"hist=${remote?.history?.size} cat=${remote?.categories?.size}",
			)

			val configResult = buildMergedConfig(remote?.config, enabled, now)
			val merged = buildMergedSnapshot(remote, configResult.config, now)
			Log.i(
				TAG,
				"merged: fav=${merged.favourites.size} hist=${merged.history.size} cat=${merged.categories.size} " +
					"cfgRev=${merged.config?.revision} remoteCfgWon=${configResult.remoteWon}",
			)
			applyToDatabase(merged, configResult.remoteWon, enabled)

			// Trim tombstones past the retention horizon from what we upload so the file can't grow
			// without bound; local rows are GC'd to match just below.
			val upload = pruneTombstones(merged, now)

			// Nothing to push (single readable file, byte-identical content)? Skip the upload entirely.
			val unchanged = files.size == 1 && remote != null && normalizedJson(upload) == normalizedJson(remote)
			if (unchanged) {
				Log.i(TAG, "no changes to push; skipping upload")
			} else {
				// Concurrency re-check immediately before writing: if the canonical file's version moved
				// since we read it, another device wrote concurrently → re-merge before overwriting.
				if (canonical != null && baseVersion != null) {
					val current = api.getFileVersion(token, canonical.id)
					if (current?.version != null && current.version != baseVersion) {
						if (attempt < MAX_CONFLICT_RETRIES) {
							Log.w(TAG, "remote changed during sync (v$baseVersion → v${current.version}); retrying merge")
							attempt++
							continue
						} else {
							throw SyncApiException(412, "Precondition failed: remote changed during sync on last attempt")
						}
					}
				}
				val fileId = try {
					api.upload(token, upload, canonical?.id, canonical?.etag)
				} catch (e: SyncApiException) {
					if (e.code == 412 && attempt < MAX_CONFLICT_RETRIES) {
						Log.w(TAG, "If-Match conflict on upload, retrying merge", e)
						attempt++
						continue
					}
					throw e
				}
				Log.i(TAG, "uploaded snapshot to $fileId")
				// Collapse first-run duplicates, but ONLY ones we decoded — their data is now merged into
				// this write. An unreadable duplicate is left untouched rather than risk losing data we
				// couldn't parse.
				for (file in files) {
					if (file.id != fileId && file.id in decodedIds) {
						runCatchingCancellable { api.delete(token, file.id) }
							.onSuccess { Log.i(TAG, "removed duplicate sync file ${file.id}") }
							.onFailure { Log.w(TAG, "failed to remove duplicate ${file.id}", it) }
					}
				}
			}

			// Shed old tombstones locally so the next snapshot we build is already trimmed.
			gcOldTombstones(now, enabled)

			syncSettings.lastSyncTimestamp = now
			syncSettings.lastSyncError = null
			syncSettings.configRevision = configResult.config.revision
			// Re-hash the ACTUAL local config after applying, so the next sync's change-detection has a
			// truthful baseline (a freshly-adopted remote config must not look "locally changed").
			syncSettings.configHash = configContentHash(dumpLocalConfig(enabled))
			return
		}
	}

	/** Deletes the remote snapshot(s) from Drive and forgets local sync bookkeeping. */
	suspend fun deleteRemoteData(): SyncResult = try {
		val token = auth.requireAccessToken()
		for (file in api.findSyncFiles(token)) {
			runCatchingCancellable { api.delete(token, file.id) }
		}
		syncSettings.lastSyncTimestamp = 0L
		syncSettings.configRevision = 0L
		syncSettings.configHash = null
		SyncResult.Success
	} catch (e: SyncSignInRequiredException) {
		SyncResult.SignInRequired
	} catch (e: Exception) {
		SyncResult.Error(e.message)
	}

	/**
	 * Decodes a downloaded snapshot. Throws [SyncSchemaException] when the file declares a newer schema
	 * than this build understands — so we abort rather than overwrite newer data.
	 */
	private fun decodeSnapshot(source: okio.BufferedSource): SyncSnapshot? {
		if (source.exhausted()) return null
		val peekSource = source.peek()
		val version = runCatching {
			@OptIn(ExperimentalSerializationApi::class)
			json.decodeFromStream(SchemaProbe.serializer(), peekSource.inputStream()).schemaVersion
		}.getOrNull()
		if (version != null && version > SyncSnapshot.SCHEMA_VERSION) {
			throw SyncSchemaException(version)
		}
		return try {
			@OptIn(ExperimentalSerializationApi::class)
			json.decodeFromStream(SyncSnapshot.serializer(), source.inputStream())
		} catch (e: Exception) {
			throw SyncApiException(0, "Remote file is corrupt or unreadable: ${e.message}")
		}
	}

	/** Serialized form with volatile per-sync fields zeroed, for an exact "did anything change?" compare. */
	private fun normalizedJson(snapshot: SyncSnapshot): String = json.encodeToString(
		SyncSnapshot.serializer(),
		SyncSnapshot(
			schemaVersion = snapshot.schemaVersion,
			deviceId = "",
			syncedAt = 0L,
			categories = snapshot.categories,
			favourites = snapshot.favourites,
			history = snapshot.history,
			bookmarks = snapshot.bookmarks,
			scrobblings = snapshot.scrobblings,
			tracks = snapshot.tracks,
			stats = snapshot.stats,
			config = snapshot.config,
		),
	)

	/** Returns a copy with tombstones older than [TOMBSTONE_TTL_MS] dropped from the row sections. */
	private fun pruneTombstones(snapshot: SyncSnapshot, now: Long): SyncSnapshot {
		val cutoff = now - TOMBSTONE_TTL_MS
		val categories = snapshot.categories.filter { it.deletedAt == 0L || it.deletedAt >= cutoff }
		val favourites = snapshot.favourites.filter { it.deletedAt == 0L || it.deletedAt >= cutoff }
		val history = snapshot.history.filter { it.deletedAt == 0L || it.deletedAt >= cutoff }
		if (categories.size == snapshot.categories.size &&
			favourites.size == snapshot.favourites.size &&
			history.size == snapshot.history.size
		) {
			return snapshot
		}
		Log.i(TAG, "pruned tombstones older than ${TOMBSTONE_TTL_MS}ms from upload")
		return SyncSnapshot(
			schemaVersion = snapshot.schemaVersion,
			deviceId = snapshot.deviceId,
			syncedAt = snapshot.syncedAt,
			categories = categories,
			favourites = favourites,
			history = history,
			bookmarks = snapshot.bookmarks,
			scrobblings = snapshot.scrobblings,
			tracks = snapshot.tracks,
			stats = snapshot.stats,
			config = snapshot.config,
		)
	}

	private suspend fun gcOldTombstones(now: Long, enabled: Set<SyncContent>) {
		val cutoff = now - TOMBSTONE_TTL_MS
		runCatchingCancellable {
			if (SyncContent.FAVOURITES in enabled) {
				database.getFavouritesDao().gc(cutoff)
				database.getFavouriteCategoriesDao().gc(cutoff)
			}
			if (SyncContent.HISTORY in enabled) {
				database.getHistoryDao().gc(cutoff)
			}
		}.onFailure { Log.w(TAG, "local tombstone gc failed", it) }
	}

	suspend fun signOut() {
		// signOut + revokeAccess so the next sign-in shows the account chooser / consent again.
		auth.signOut()
		syncSettings.clearAccount()
	}

	// region snapshot building / merging

	private suspend fun buildMergedSnapshot(
		remote: SyncSnapshot?,
		config: SyncConfig,
		now: Long,
	): SyncSnapshot {
		val enabled = SyncContent.fromKeys(syncSettings.enabledContent)
		val favEnabled = SyncContent.FAVOURITES in enabled
		val histEnabled = SyncContent.HISTORY in enabled

		val categories = if (favEnabled) {
			SyncMerger.mergeCategories(localCategories(), remote?.categories.orEmpty())
		} else {
			remote?.categories.orEmpty()
		}
		val favourites = if (favEnabled) {
			SyncMerger.mergeFavourites(localFavourites(), remote?.favourites.orEmpty())
		} else {
			remote?.favourites.orEmpty()
		}
		val history = if (histEnabled) {
			SyncMerger.mergeHistory(localHistory(), remote?.history.orEmpty())
		} else {
			remote?.history.orEmpty()
		}
		val bookmarks = if (SyncContent.BOOKMARKS in enabled) {
			SyncMerger.mergeBookmarks(localBookmarks(), remote?.bookmarks.orEmpty())
		} else {
			remote?.bookmarks.orEmpty()
		}
		val scrobblings = if (SyncContent.TRACKING in enabled) {
			SyncMerger.mergeScrobblings(localScrobblings(), remote?.scrobblings.orEmpty())
		} else {
			remote?.scrobblings.orEmpty()
		}
		val tracks = if (SyncContent.FEED in enabled) {
			SyncMerger.mergeTracks(localTracks(), remote?.tracks.orEmpty())
		} else {
			remote?.tracks.orEmpty()
		}
		val stats = if (SyncContent.STATS in enabled) {
			SyncMerger.mergeStats(localStats(), remote?.stats.orEmpty())
		} else {
			remote?.stats.orEmpty()
		}
		return SyncSnapshot(
			deviceId = syncSettings.deviceId,
			syncedAt = now,
			categories = categories,
			favourites = favourites,
			history = history,
			bookmarks = bookmarks,
			scrobblings = scrobblings,
			tracks = tracks,
			stats = stats,
			config = config,
		)
	}

	private class ConfigMergeResult(val config: SyncConfig, val remoteWon: Boolean)

	/**
	 * Merges the config bundle. Two important safety properties:
	 *  1. A device with no baseline hash (never synced) is treated as having made NO local change, so
	 *     it ADOPTS any existing remote config instead of overwriting the cloud with local defaults —
	 *     this is the bug that wiped settings when signing in on a second device.
	 *  2. Each section is unioned by key (winner overrides on conflict) so a key that exists on only
	 *     one device is never dropped. Disabled sections dump empty locally and so pass remote through.
	 *
	 * The whole bundle still resolves by [SyncConfig.revision] (last-writer-wins) when both sides edited
	 * overlapping keys concurrently — a rare case given the multi-hour sync cadence.
	 */
	private suspend fun buildMergedConfig(
		remote: SyncConfig?,
		enabled: Set<SyncContent>,
		now: Long,
	): ConfigMergeResult {
		val settingsEnabled = SyncContent.SETTINGS in enabled
		val coversEnabled = SyncContent.CUSTOM_COVERS in enabled

		val local = dumpLocalConfig(enabled)
		val currentHash = configContentHash(local)

		val hasBaseline = syncSettings.configHash != null
		val localChanged = hasBaseline && (settingsEnabled || coversEnabled) && currentHash != syncSettings.configHash
		val localRevision = if (localChanged) now else syncSettings.configRevision
		val remoteRevision = remote?.revision ?: -1L
		// Remote wins if it exists and either we made no deliberate local change (adopt the cloud) or
		// its revision is strictly newer. A tie with an unchanged local always goes to remote.
		val remoteWon = remote != null && (!localChanged || remoteRevision > localRevision)

		val merged = SyncConfig(
			revision = maxOf(localRevision, remoteRevision, 0L),
			settings = mergeConfigMap(local.settings, remote?.settings, remoteWon)
				.filterKeys { it !in EXCLUDED_SETTINGS_KEYS },
			readerGrid = mergeConfigMap(local.readerGrid, remote?.readerGrid, remoteWon),
			sourceSettings = mergeConfigList(local.sourceSettings, remote?.sourceSettings, remoteWon) { it.source },
			mangaPrefs = mergeConfigList(local.mangaPrefs, remote?.mangaPrefs, remoteWon) { it.mangaId },
		)
		return ConfigMergeResult(merged, remoteWon)
	}

	private suspend fun dumpLocalConfig(enabled: Set<SyncContent>): SyncConfig {
		val settingsEnabled = SyncContent.SETTINGS in enabled
		val coversEnabled = SyncContent.CUSTOM_COVERS in enabled
		return SyncConfig(
			revision = 0L,
			settings = if (settingsEnabled) dumpAppSettings() else emptyMap(),
			readerGrid = if (settingsEnabled) dumpReaderGrid() else emptyMap(),
			sourceSettings = if (settingsEnabled) dumpSourceSettings() else emptyList(),
			mangaPrefs = if (coversEnabled) dumpMangaPrefs() else emptyList(),
		)
	}

	/** Union of two maps; the winning side overrides on a shared key. A null remote yields local as-is. */
	private fun <V> mergeConfigMap(local: Map<String, V>, remote: Map<String, V>?, remoteWon: Boolean): Map<String, V> {
		if (remote == null) return local
		val out = LinkedHashMap<String, V>(local.size + remote.size)
		if (remoteWon) {
			out.putAll(local)
			out.putAll(remote)
		} else {
			out.putAll(remote)
			out.putAll(local)
		}
		return out
	}

	/** Union of two lists keyed by [key]; the winning side overrides on a shared key. */
	private inline fun <T, K> mergeConfigList(
		local: List<T>,
		remote: List<T>?,
		remoteWon: Boolean,
		key: (T) -> K,
	): List<T> {
		if (remote == null) return local
		val out = LinkedHashMap<K, T>(local.size + remote.size)
		val first = if (remoteWon) local else remote
		val second = if (remoteWon) remote else local
		for (item in first) out[key(item)] = item
		for (item in second) out[key(item)] = item
		return out.values.toList()
	}

	// endregion

	// region apply to DB

	private suspend fun applyToDatabase(
		merged: SyncSnapshot,
		remoteConfigWon: Boolean,
		enabled: Set<SyncContent>,
	) {
		if (SyncContent.FAVOURITES in enabled) {
			database.withTransaction {
				val existingCategoryIds = database.getFavouriteCategoriesDao().findAllForSync()
					.map { it.categoryId.toLong() }
					.toMutableSet()
				for (category in merged.categories) {
					database.getFavouriteCategoriesDao().upsert(category.toEntity())
					existingCategoryIds.add(category.categoryId.toLong())
				}
				for (favourite in merged.favourites) {
					if (favourite.categoryId !in existingCategoryIds) {
						Log.w(TAG, "Skipping orphan favourite for manga ${favourite.mangaId} referencing missing category ${favourite.categoryId}")
						continue
					}
					upsertManga(favourite.manga)
					database.getFavouritesDao().upsert(favourite.toEntity())
				}
			}
		}
		if (SyncContent.HISTORY in enabled) {
			database.withTransaction {
				for (entry in merged.history) {
					upsertManga(entry.manga)
					database.getHistoryDao().upsertForSync(entry.toEntity())
				}
			}
		}
		if (SyncContent.BOOKMARKS in enabled) {
			for (group in merged.bookmarks) {
				runCatchingCancellable {
					database.withTransaction {
						upsertManga(group.manga)
						if (group.bookmarks.isNotEmpty()) {
							database.getBookmarksDao().upsert(group.bookmarks.map { it.toEntity() })
						}
					}
				}
			}
		}
		if (SyncContent.FEED in enabled) {
			for (track in merged.tracks) {
				runCatchingCancellable {
					database.withTransaction {
						upsertManga(track.manga)
						database.getTracksDao().upsert(track.toEntity())
					}
				}
			}
		}
		if (SyncContent.TRACKING in enabled) {
			for (entry in merged.scrobblings) {
				runCatchingCancellable { database.getScrobblingDao().upsert(entry.toEntity()) }
			}
		}
		if (SyncContent.STATS in enabled) {
			// FK to history.manga_id — applied after history; tolerate rows whose history is absent.
			for (entry in merged.stats) {
				runCatchingCancellable { database.getStatsDao().upsert(entry.toEntity()) }
			}
		}
		// Apply config locally only when the remote bundle won the merge; otherwise local already holds
		// the newest config. We apply the MERGED config (not raw remote) so locally-unique keys survive.
		if (remoteConfigWon) {
			merged.config?.let { applyConfig(it, enabled) }
		}
	}

	private fun validateSetting(key: String, value: BackupPrimitive): Boolean {
		if (key in EXCLUDED_SETTINGS_KEYS) return false
		return when (key) {
			AppSettings.KEY_ADBLOCK,
			AppSettings.KEY_TITLE_OVER_COVER,
			AppSettings.KEY_GRID_SPACING_INCREASED,
			AppSettings.KEY_THEME_AMOLED,
			AppSettings.KEY_HAPTIC_FEEDBACK,
			AppSettings.KEY_OFFLINE_DISABLED,
			AppSettings.KEY_READER_DOUBLE_PAGES,
			AppSettings.KEY_READER_DOUBLE_FOLDABLE,
			AppSettings.KEY_READER_ZOOM_BUTTONS,
			AppSettings.KEY_READER_CONTROL_LTR,
			AppSettings.KEY_READER_NAVIGATION_INVERTED,
			AppSettings.KEY_READER_FULLSCREEN,
			AppSettings.KEY_READER_VOLUME_BUTTONS,
			AppSettings.KEY_READER_MODE_DETECT,
			AppSettings.KEY_READER_CROP,
			AppSettings.KEY_READER_SCREEN_ON,
			AppSettings.KEY_PAGES_NUMBERS,
			AppSettings.KEY_TRACKER_ENABLED,
			AppSettings.KEY_TRACKER_WIFI_ONLY,
			AppSettings.KEY_TRACK_SOURCES,
			AppSettings.KEY_TRACKER_NOTIFICATIONS,
			AppSettings.KEY_TRACKER_NO_NSFW,
			AppSettings.KEY_NOTIFICATIONS_SOUND,
			AppSettings.KEY_NOTIFICATIONS_VIBRATE,
			AppSettings.KEY_NOTIFICATIONS_LIGHT,
			"discord_rpc_enabled" -> value is BackupPrimitive.BoolValue

			AppSettings.KEY_LIST_MODE,
			AppSettings.KEY_LIST_MODE_HISTORY,
			AppSettings.KEY_LIST_MODE_FAVORITES,
			AppSettings.KEY_LIST_MODE_SUGGESTIONS,
			AppSettings.KEY_THEME,
			AppSettings.KEY_COLOR_THEME,
			AppSettings.KEY_READER_ANIMATION,
			AppSettings.KEY_READER_BACKGROUND,
			AppSettings.KEY_READER_CONTROLS,
			AppSettings.KEY_READER_MODE,
			AppSettings.KEY_TRACKER_FREQUENCY,
			AppSettings.KEY_TRACKER_DOWNLOAD,
			AppSettings.KEY_PAGES_PRELOAD,
			AppSettings.KEY_DOWNLOADS_METERED_NETWORK,
			AppSettings.KEY_SEARCH_SUGGESTION_TYPES,
			AppSettings.KEY_MANGA_LIST_BADGES -> value is BackupPrimitive.StringValue

			AppSettings.KEY_GRID_SIZE,
			AppSettings.KEY_GRID_SIZE_PAGES -> {
				value is BackupPrimitive.IntValue && value.value in 1..10
			}

			AppSettings.KEY_READER_DOUBLE_PAGES_SENSITIVITY -> {
				value is BackupPrimitive.FloatValue && value.value in 0f..1f
			}

			AppSettings.KEY_READER_ORIENTATION -> {
				value is BackupPrimitive.IntValue || value is BackupPrimitive.StringValue
			}

			else -> false
		}
	}

	private suspend fun applyConfig(config: SyncConfig, enabled: Set<SyncContent>) {
		if (SyncContent.SETTINGS in enabled) {
			val settings = config.settings.filter { (key, value) -> validateSetting(key, value) }
			appSettings.upsertAll(settings.mapValues { it.value.rawValue() })
			val readerGrid = config.readerGrid.filter { (key, value) -> value is BackupPrimitive.StringValue }
			tapGridSettings.upsertAll(readerGrid.mapValues { it.value.rawValue() })
			applySourceSettings(config.sourceSettings)
		}
		if (SyncContent.CUSTOM_COVERS in enabled) {
			for (pref in config.mangaPrefs) {
				database.getPreferencesDao().upsert(pref.toEntity())
			}
		}
	}

	private suspend fun upsertManga(manga: MangaBackup) {
		val tags = manga.tags.map { it.toEntity() }
		if (tags.isNotEmpty()) {
			database.getTagsDao().upsert(tags)
		}
		database.getMangaDao().upsert(manga.toEntity(), tags)
	}

	private suspend fun applySourceSettings(list: List<SourceSettingsBackup>) {
		val knownSources = database.getSourcesDao().findAll().mapTo(HashSet()) { it.source }
		for (entry in list) {
			if (entry.source !in knownSources) {
				Log.d(TAG, "sync: skipping source settings for '${entry.source}' — source not installed")
				continue
			}
			val prefsName = SourceSettings.getStorageName(entry.source)
			val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
			prefs.edit {
				entry.values.forEach { (key, primitive) ->
					when (primitive) {
						is BackupPrimitive.StringValue -> putString(key, primitive.value)
						is BackupPrimitive.BoolValue -> putBoolean(key, primitive.value)
						is BackupPrimitive.IntValue -> putInt(key, primitive.value)
						is BackupPrimitive.LongValue -> putLong(key, primitive.value)
						is BackupPrimitive.FloatValue -> putFloat(key, primitive.value)
						is BackupPrimitive.StringSetValue -> putStringSet(key, primitive.value)
					}
				}
			}
		}
	}

	// endregion

	// region local readers

	private suspend fun localCategories(): List<SyncCategory> =
		database.getFavouriteCategoriesDao().findAllForSync().map(::SyncCategory)

	private suspend fun localFavourites(): List<SyncFavourite> {
		val mangaCache = HashMap<Long, MangaBackup>()
		return database.getFavouritesDao().findAllForSync().mapNotNull { entity ->
			val manga = mangaCache.getOrPut(entity.mangaId) {
				database.getMangaDao().find(entity.mangaId)?.toBackup() ?: run {
					Log.w(TAG, "sync: skipping favourite(mangaId=${entity.mangaId}) — manga row missing")
					return@mapNotNull null
				}
			}
			SyncFavourite(entity, manga)
		}
	}

	private suspend fun localHistory(): List<SyncHistory> {
		val mangaCache = HashMap<Long, MangaBackup>()
		return database.getHistoryDao().findAllForSync().mapNotNull { entity ->
			val manga = mangaCache.getOrPut(entity.mangaId) {
				database.getMangaDao().find(entity.mangaId)?.toBackup() ?: run {
					Log.w(TAG, "sync: skipping history(mangaId=${entity.mangaId}) — manga row missing")
					return@mapNotNull null
				}
			}
			SyncHistory(entity, manga)
		}
	}

	private suspend fun localBookmarks(): List<BookmarkBackup> =
		database.getBookmarksDao().dump().toList().map { (manga, items) -> BookmarkBackup(manga, items) }

	private suspend fun localScrobblings(): List<ScrobblingBackup> =
		database.getScrobblingDao().dumpEnabled().toList().map(::ScrobblingBackup)

	private suspend fun localStats(): List<StatsBackup> =
		database.getStatsDao().dumpEnabled().toList().map(::StatsBackup)

	private suspend fun localTracks(): List<SyncTrack> {
		val mangaCache = HashMap<Long, MangaBackup>()
		return database.getTracksDao().findAllForSync().mapNotNull { entity ->
			val manga = mangaCache.getOrPut(entity.mangaId) {
				database.getMangaDao().find(entity.mangaId)?.toBackup() ?: run {
					Log.w(TAG, "sync: skipping track(mangaId=${entity.mangaId}) — manga row missing")
					return@mapNotNull null
				}
			}
			SyncTrack(entity, manga)
		}
	}

	private fun MangaWithTags.toBackup(): MangaBackup = MangaBackup(this)

	private fun dumpAppSettings(): Map<String, BackupPrimitive> {
		val map = appSettings.getAllValues().toMutableMap()
		EXCLUDED_SETTINGS_KEYS.forEach { map.remove(it) }
		val backupMap = map.toSortedMap().mapNotNullValuesToBackup()
		return backupMap.filter { (key, value) -> validateSetting(key, value) }
	}

	private fun dumpReaderGrid(): Map<String, BackupPrimitive> {
		val backupMap = tapGridSettings.getAllValues().toSortedMap().mapNotNullValuesToBackup()
		return backupMap.filter { (key, value) -> value is BackupPrimitive.StringValue }
	}

	private suspend fun dumpSourceSettings(): List<SourceSettingsBackup> {
		val sources = database.getSourcesDao().findAll()
		val result = ArrayList<SourceSettingsBackup>(sources.size)
		for (source in sources.sortedBy { it.source }) {
			val prefsName = SourceSettings.getStorageName(source.source)
			val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
			val values = prefs.all.toSortedMap().mapNotNullValuesToBackup()
			if (values.isNotEmpty()) {
				result += SourceSettingsBackup(source = source.source, values = values)
			}
		}
		return result
	}

	private suspend fun dumpMangaPrefs(): List<SyncMangaPrefs> =
		database.getPreferencesDao().getOverrides().sortedBy { it.mangaId }.map(::SyncMangaPrefs)

	private fun Map<String, *>.mapNotNullValuesToBackup(): Map<String, BackupPrimitive> {
		val out = LinkedHashMap<String, BackupPrimitive>(size)
		for ((key, value) in this) {
			BackupPrimitive.of(value)?.let { out[key] = it }
		}
		return out
	}

	private fun configContentHash(config: SyncConfig): String {
		val normalized = SyncConfig(
			revision = 0L,
			settings = config.settings.toSortedMap(),
			readerGrid = config.readerGrid.toSortedMap(),
			sourceSettings = config.sourceSettings.sortedBy { it.source },
			mangaPrefs = config.mangaPrefs.sortedBy { it.mangaId },
		)
		return json.encodeToString(SyncConfig.serializer(), normalized).hashCode().toString()
	}

	// endregion

	/** Lightweight probe to read just the schema version before attempting a full decode. */
	@Serializable
	private class SchemaProbe(@SerialName("schema") val schemaVersion: Int = 0)

	private companion object {

		const val TAG = "GDriveSync"

		/**
		 * How long soft-deleted rows (tombstones) are retained in the snapshot and locally before being
		 * garbage-collected. Must comfortably exceed the longest realistic gap between a device's syncs
		 * so every device sees a deletion before its tombstone is dropped; a device offline longer than
		 * this may resurrect an item it never learned was deleted (the accepted trade-off for bounding
		 * the file size).
		 */
		const val TOMBSTONE_TTL_MS = 60L * 24 * 60 * 60 * 1000 // 60 days

		/** Max times to re-merge when another device writes the file mid-sync, before a best-effort write. */
		const val MAX_CONFLICT_RETRIES = 3

		val EXCLUDED_SETTINGS_KEYS = setOf(
			AppSettings.KEY_APP_PASSWORD,
			AppSettings.KEY_APP_PASSWORD_NUMERIC,
			AppSettings.KEY_PROXY_PASSWORD,
			AppSettings.KEY_PROXY_LOGIN,
			AppSettings.KEY_INCOGNITO_MODE,
			AppSettings.KEY_ONBOARDING_COMPLETED,
			AppSettings.KEY_ONBOARDING_INSTALL_ID,
		)
	}
}
