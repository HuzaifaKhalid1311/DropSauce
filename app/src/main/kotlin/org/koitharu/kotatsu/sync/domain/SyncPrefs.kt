package org.koitharu.kotatsu.sync.domain

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.room.withTransaction
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.backup.local.data.model.BackupPrimitive
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreRecord
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreRegistry
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreRegistryState
import org.koitharu.kotatsu.settings.sources.catalog.normalizeExtensionStoreUrl
import org.koitharu.kotatsu.sync.data.db.SyncPrefEntity
import org.koitharu.kotatsu.sync.data.model.RPref
import java.io.File
import javax.inject.Inject

/**
 * Per-key change tracking for the synced SharedPreferences files: app settings, reader tap grid,
 * source/extension settings, saved filters, pinned and recently used sources, novel plugin settings
 * and the extension stores (see [SyncedStores]). Each sync compares the current
 * values with the last-seen ones in `sync_prefs`; what differs gets a new version. A file seen for the
 * first time gets version 0 (unknown age), so it never outranks what's already in the cloud.
 */
@Reusable
class SyncPrefs @Inject constructor(
	@ApplicationContext private val context: Context,
	private val database: MangaDatabase,
	private val extensionStores: ExtensionStoreRegistry,
) {

	private val appFile = context.packageName + "_preferences"

	private fun trackedFiles(): List<String> {
		val dir = File(context.applicationInfo.dataDir, "shared_prefs")
		return dir.list().orEmpty()
			.filter { it.endsWith(".xml") }
			.map { it.removeSuffix(".xml") }
			.filter(::isTracked)
			.plus(SyncedStores.FILE)
			.sorted()
	}

	private fun isTracked(name: String): Boolean = name == appFile ||
		name == TAP_GRID ||
		name == SOURCE_STATE || // pinned sources
		name == SOURCE_USAGE ||
		name == SyncedStores.FILE ||
		SOURCE_FILE.matches(name) ||
		name.startsWith("MIHON_") || // legacy source settings and saved filters
		name.startsWith("LN_") || // novel source saved filters
		LN_PLUGIN_FILE.matches(name) // novel plugin settings; its local/session storage is a per-device cache

	private fun wireName(local: String) = if (local == appFile) APP else local

	private fun localName(wire: String): String? = (if (wire == APP) appFile else wire).takeIf(::isTracked)

	/**
	 * Stamps changes made since the last sync and returns every tracked key as a record. Category ids
	 * inside key names are translated to their cross-device uid via [categoryUids].
	 */
	suspend fun detect(categoryUids: Map<Int, String>): List<RPref> {
		val now = System.currentTimeMillis()
		val dao = database.getSyncDao()
		val result = ArrayList<RPref>()
		if (readFile(appFile).isEmpty() && dao.findPrefs(appFile).any { it.hash != null }) {
			// Settings never empty themselves: the file was lost or corrupted. Publishing that would reset
			// every other device, so start over as never-seen files and let the cloud's copy come back.
			// The extension stores live in the same file.
			Log.w(TAG, "app settings are empty, restoring them from the cloud")
			dao.deletePrefs(appFile)
			dao.deletePrefs(SyncedStores.FILE)
		}
		for (file in trackedFiles()) {
			val current = readFile(file)
			database.withTransaction {
				val baseline = dao.findPrefs(file).associateBy { it.key }
				val isFirstSeen = baseline.isEmpty()
				val updates = ArrayList<SyncPrefEntity>()
				for ((key, value) in current) {
					val hash = hash(value)
					val known = baseline[key]
					if (known == null) {
						updates += SyncPrefEntity(file, key, hash, if (isFirstSeen) 0L else now)
					} else if (known.hash != hash) {
						updates += SyncPrefEntity(file, key, hash, maxOf(now, known.version + 1))
					}
				}
				for (known in baseline.values) {
					if (known.hash != null && known.key !in current) {
						updates += SyncPrefEntity(file, known.key, null, maxOf(now, known.version + 1))
					}
				}
				if (updates.isNotEmpty()) {
					dao.upsertPrefs(updates)
				}
				val versions = baseline.values.associateTo(HashMap()) { it.key to it.version }
				updates.associateTo(versions) { it.key to it.version }
				for ((key, version) in versions) {
					val wireKey = translateOut(file, key, categoryUids) ?: continue
					result += RPref(wireName(file), wireKey, current[key], version)
				}
			}
		}
		return result
	}

	/**
	 * Applies remote winners. A key is only written if its value is still what [detect] saw, so an edit
	 * made during the sync is never overwritten — it just goes out with the next sync.
	 */
	suspend fun apply(winners: List<RPref>, categoryIds: Map<String, Int>) {
		val dao = database.getSyncDao()
		for ((wireFile, records) in winners.groupBy { it.file }) {
			val file = localName(wireFile) ?: continue
			if (file == SyncedStores.FILE) {
				applyStores(records)
				continue
			}
			val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)
			val baseline = dao.findPrefs(file).associateBy { it.key }
			val all = prefs.all
			val applied = ArrayList<SyncPrefEntity>(records.size)
			val editor = prefs.edit()
			for (record in records) {
				val key = translateIn(file, record.prefKey, categoryIds) ?: continue
				if (file == appFile && !AppSettings.isSyncableKey(key)) continue
				val current = BackupPrimitive.of(all[key])
				val currentHash = current?.let(::hash)
				if (currentHash != baseline[key]?.hash) continue // changed locally since detection
				val value = record.value
				if (value != null && current != null && value.javaClass != current.javaClass) {
					Log.w(TAG, "skipping $file/$key: type ${current.javaClass.simpleName} != ${value.javaClass.simpleName}")
					continue
				}
				editor.put(key, value)
				applied += SyncPrefEntity(file, key, value?.let(::hash), record.v)
			}
			if (applied.isNotEmpty() && editor.commit()) {
				dao.upsertPrefs(applied)
			}
		}
	}

	private suspend fun applyStores(records: List<RPref>) {
		val dao = database.getSyncDao()
		val baseline = dao.findPrefs(SyncedStores.FILE).associateBy { it.key }
		var applied = emptyList<RPref>()
		extensionStores.applySynced { state ->
			val (updated, done) = SyncedStores.apply(state, records) { key, current ->
				current?.let(::hash) == baseline[key]?.hash
			}
			applied = done
			updated
		}
		if (applied.isNotEmpty()) {
			dao.upsertPrefs(applied.map { SyncPrefEntity(SyncedStores.FILE, it.prefKey, it.value?.let(::hash), it.v) })
		}
	}

	private fun readFile(file: String): Map<String, BackupPrimitive> {
		if (file == SyncedStores.FILE) return SyncedStores.values(extensionStores.state)
		val all = context.getSharedPreferences(file, Context.MODE_PRIVATE).all
		val out = HashMap<String, BackupPrimitive>(all.size)
		for ((key, value) in all) {
			if (file == appFile && !AppSettings.isSyncableKey(key)) continue
			BackupPrimitive.of(value)?.let { out[key] = it }
		}
		return out
	}

	// Pinned-favourites order is stored per local category id, which means nothing on another device.
	private fun translateOut(file: String, key: String, categoryUids: Map<Int, String>): String? {
		if (file != appFile || !key.startsWith(AppSettings.KEY_FAVORITES_PINNED)) return key
		val id = key.removePrefix(AppSettings.KEY_FAVORITES_PINNED).toIntOrNull() ?: return null
		if (id == 0) return key // "all favourites"
		return AppSettings.KEY_FAVORITES_PINNED + '@' + (categoryUids[id] ?: return null)
	}

	private fun translateIn(file: String, key: String, categoryIds: Map<String, Int>): String? {
		if (file != appFile || !key.startsWith(AppSettings.KEY_FAVORITES_PINNED + '@')) return key
		val uid = key.removePrefix(AppSettings.KEY_FAVORITES_PINNED + '@')
		return AppSettings.KEY_FAVORITES_PINNED + (categoryIds[uid] ?: return null)
	}

	private fun SharedPreferences.Editor.put(key: String, value: BackupPrimitive?) {
		when (value) {
			null -> remove(key)
			is BackupPrimitive.StringValue -> putString(key, value.value)
			is BackupPrimitive.BoolValue -> putBoolean(key, value.value)
			is BackupPrimitive.IntValue -> putInt(key, value.value)
			is BackupPrimitive.LongValue -> putLong(key, value.value)
			is BackupPrimitive.FloatValue -> putFloat(key, value.value)
			is BackupPrimitive.StringSetValue -> putStringSet(key, value.value)
		}
	}

	companion object {

		private const val TAG = "GDriveSync"
		private const val APP = "app"
		private const val TAP_GRID = "tap_grid"
		private const val SOURCE_STATE = "source_state"
		private const val SOURCE_USAGE = "source_usage"
		private val SOURCE_FILE = Regex("source_\\d+")
		private val LN_PLUGIN_FILE = Regex("ln_.+_db")

		internal fun hash(value: BackupPrimitive): Long {
			val canonical = when (value) {
				is BackupPrimitive.StringValue -> "s:" + value.value
				is BackupPrimitive.BoolValue -> "b:" + value.value
				is BackupPrimitive.IntValue -> "i:" + value.value
				is BackupPrimitive.LongValue -> "l:" + value.value
				is BackupPrimitive.FloatValue -> "f:" + value.value.toRawBits()
				is BackupPrimitive.StringSetValue -> "S:" + value.value.sorted().joinToString("\u0000")
			}
			return (canonical.hashCode().toLong() shl 32) or (canonical.length.toLong() and 0xFFFFFFFFL)
		}
	}
}

/**
 * Extension stores travel as one record per store plus their order, not as the registry blob, so two
 * devices adding different stores both keep them. Which package was installed from which store stays
 * on each device.
 */
internal object SyncedStores {

	const val FILE = "extension_stores"
	const val ORDER = "@order"

	private val json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = true
	}

	fun values(state: ExtensionStoreRegistryState): Map<String, BackupPrimitive> {
		val out = LinkedHashMap<String, BackupPrimitive>()
		for (store in state.stores) {
			out[store.id] = BackupPrimitive.StringValue(json.encodeToString(ExtensionStoreRecord.serializer(), store))
		}
		out[ORDER] = BackupPrimitive.StringValue(state.stores.joinToString("\n") { it.id })
		return out
	}

	/** Applies the [records] whose key [isUnchanged] since detection; returns the new state and what was applied. */
	fun apply(
		state: ExtensionStoreRegistryState,
		records: List<RPref>,
		isUnchanged: (key: String, current: BackupPrimitive?) -> Boolean,
	): Pair<ExtensionStoreRegistryState, List<RPref>> {
		val current = values(state)
		val stores = state.stores.toMutableList()
		var order: List<String>? = null
		val applied = ArrayList<RPref>()
		for (record in records) {
			if (!isUnchanged(record.prefKey, current[record.prefKey])) continue
			val value = record.value
			if (value != null && value !is BackupPrimitive.StringValue) continue
			if (record.prefKey == ORDER) {
				order = value?.value?.split('\n')?.filter(String::isNotEmpty) ?: continue
			} else if (value == null) {
				stores.removeAll { it.id == record.prefKey }
			} else {
				val store = runCatching { json.decodeFromString(ExtensionStoreRecord.serializer(), value.value) }
					.getOrNull()
					?.takeIf { it.id == record.prefKey }
					?: continue
				val index = stores.indexOfFirst { it.id == store.id }
				when {
					index >= 0 -> stores[index] = store
					stores.none { it.isSameStore(store) } -> stores += store
					else -> continue // already here under another id (added separately on two devices)
				}
			}
			applied += record
		}
		order?.let { ids -> stores.sortBy { ids.indexOf(it.id).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE } }
		val ids = stores.mapTo(HashSet()) { it.id }
		return state.copy(stores = stores, ownerships = state.ownerships.filter { it.storeId in ids }) to applied
	}

	private fun ExtensionStoreRecord.isSameStore(other: ExtensionStoreRecord): Boolean =
		normalizeExtensionStoreUrl(indexUrl).equals(normalizeExtensionStoreUrl(other.indexUrl), ignoreCase = true) ||
			(!fingerprint.isNullOrBlank() && fingerprint.equals(other.fingerprint, ignoreCase = true))
}
