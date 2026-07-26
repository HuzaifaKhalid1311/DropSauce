package org.koitharu.kotatsu.settings.sources.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.mihon.MihonExtensionLoader
import org.koitharu.kotatsu.mihon.model.ExternalRepoInfo
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExternalExtensionRepoRepository @Inject constructor(
	@BaseHttpClient private val okHttpClient: OkHttpClient,
) {

	private val json = Json {
		ignoreUnknownKeys = true
	}
	private val protoBuf = ProtoBuf { }

	/**
	 * Loads a repo's extension list, transparently supporting both the legacy `index.min.json` array
	 * and the newer "extension store" index (a JSON or protobuf `index.pb`, optionally gzip-compressed,
	 * optionally with its list in a separate `extensionListUrl`). Everything is mapped back onto
	 * [ExternalExtensionRepoEntry] so callers don't care which format the repo uses.
	 */
	suspend fun getExtensions(repoUrl: String, forceRefresh: Boolean = false): List<ExternalExtensionRepoEntry> =
		withContext(Dispatchers.IO) {
			loadEntries(buildIndexUrl(repoUrl), forceRefresh, cacheOnly = false, depth = 0)
				.filterNot { it.lang in MihonExtensionLoader.HIDDEN_LANGUAGES }
		}

	suspend fun getCachedExtensions(repoUrl: String): List<ExternalExtensionRepoEntry> =
		withContext(Dispatchers.IO) {
			loadEntries(buildIndexUrl(repoUrl), forceRefresh = false, cacheOnly = true, depth = 0)
				.filterNot { it.lang in MihonExtensionLoader.HIDDEN_LANGUAGES }
		}

	suspend fun validateStore(repoUrl: String): ValidatedExtensionStore {
		val normalizedUrl = normalizeExtensionStoreUrl(repoUrl)
		require(normalizedUrl.startsWith("https://")) { "Store index URL must use HTTPS" }
		val catalog = getExtensions(normalizedUrl, forceRefresh = true)
		val info = fetchIndexRepoInfo(normalizedUrl) ?: fetchRepoInfo(normalizedUrl)
		return ValidatedExtensionStore(
			store = ExtensionStoreRecord(
				id = stableExtensionStoreId(normalizedUrl),
				indexUrl = normalizedUrl,
				name = info?.name ?: extensionStoreUrlLabel(normalizedUrl),
				shortName = info?.shortName,
				fingerprint = info?.fingerprint,
				website = info?.website,
				discord = info?.discord,
			),
			catalog = catalog,
		)
	}

	private fun loadEntries(
		url: String,
		forceRefresh: Boolean,
		cacheOnly: Boolean,
		depth: Int,
	): List<ExternalExtensionRepoEntry> {
		if (depth > MAX_INDEX_HOPS) return emptyList() // guard against index_v2 / list-url cycles
		val bytes = fetchBytes(url, forceRefresh, cacheOnly) ?: return emptyList()
		return when (bytes.firstOrNull()) {
			OPEN_BRACKET -> json.decodeFromString<List<ExternalExtensionRepoEntry>>(bytes.decodeToString())
			OPEN_BRACE -> {
				val text = bytes.decodeToString()
				val repoJson = runCatching { json.decodeFromString<ExternalRepoJson>(text) }.getOrNull()
				// A '{' body is either a repo.json (meta / index_v2 pointer) or a store object.
				if (repoJson != null && (repoJson.indexV2 != null || repoJson.meta.signingKeyFingerprint.isNotBlank())) {
					loadEntries(
						repoJson.indexV2 ?: "${getBaseUrl(url)}/index.min.json",
						forceRefresh,
						cacheOnly,
						depth + 1,
					)
				} else {
					storeEntries(json.decodeFromString<NetworkExtensionStore>(text), forceRefresh, cacheOnly, depth)
				}
			}
			null -> emptyList()
			else -> {
				val store = runCatching { protoBuf.decodeFromByteArray<NetworkExtensionStore>(bytes) }.getOrNull()
				if (store != null && (store.extensionList != null || store.extensionListUrl != null)) {
					storeEntries(store, forceRefresh, cacheOnly, depth)
				} else {
					val list = runCatching { protoBuf.decodeFromByteArray<NetworkExtensionStore.ExtensionList>(bytes) }.getOrNull()
					list?.extensions?.map(NetworkExtensionStore.Extension::toRepoEntry)
						?: storeEntries(store ?: NetworkExtensionStore(), forceRefresh, cacheOnly, depth)
				}
			}
		}
	}

	private fun storeEntries(
		store: NetworkExtensionStore,
		forceRefresh: Boolean,
		cacheOnly: Boolean,
		depth: Int,
	): List<ExternalExtensionRepoEntry> {
		store.extensionList?.let { return it.extensions.map(NetworkExtensionStore.Extension::toRepoEntry) }
		val listUrl = store.extensionListUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
		if (depth > MAX_INDEX_HOPS) return emptyList()
		val bytes = fetchBytes(listUrl, forceRefresh, cacheOnly) ?: return emptyList()
		val list = when (bytes.firstOrNull()) {
			OPEN_BRACE -> json.decodeFromString<NetworkExtensionStore.ExtensionList>(bytes.decodeToString())
			null -> null
			else -> protoBuf.decodeFromByteArray<NetworkExtensionStore.ExtensionList>(bytes)
		}
		return list?.extensions?.map(NetworkExtensionStore.Extension::toRepoEntry).orEmpty()
	}

	/** Fetches [url], throwing on HTTP error; returns decompressed bytes, or null if the body is empty. */
	private fun fetchBytes(url: String, forceRefresh: Boolean, cacheOnly: Boolean = false): ByteArray? {
		val builder = Request.Builder().url(url).get()
		if (cacheOnly) builder.cacheControl(okhttp3.CacheControl.FORCE_CACHE)
		else if (forceRefresh) builder.cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
		okHttpClient.newCall(builder.build()).execute().use { response ->
			if (!response.isSuccessful) {
				throw IllegalStateException("Unable to load repo: HTTP ${response.code}")
			}
			return response.body.bytes().gunzipIfNeeded().takeIf { it.isNotEmpty() }
		}
	}

	private fun ByteArray.gunzipIfNeeded(): ByteArray =
		if (size >= 2 && this[0] == 0x1f.toByte() && this[1] == 0x8b.toByte()) {
			GZIPInputStream(inputStream()).use { it.readBytes() }
		} else {
			this
		}

	/**
	 * Fetches the repo's `repo.json` for its authoritative name + signing fingerprint. Returns null
	 * if the repo doesn't publish one (or it's unreachable) — callers then fall back to URL-derived
	 * naming and install-time provenance.
	 */
	suspend fun fetchRepoInfo(repoUrl: String): ExternalRepoInfo? = withContext(Dispatchers.IO) {
		runCatching {
			val bytes = fetchBytes("${getBaseUrl(repoUrl)}/repo.json", forceRefresh = false) ?: return@runCatching null
			parseRepoInfo(repoUrl, bytes.decodeToString())
		}.getOrNull()
	}

	private suspend fun fetchIndexRepoInfo(repoUrl: String): ExternalRepoInfo? = withContext(Dispatchers.IO) {
		runCatching {
			val bytes = fetchBytes(buildIndexUrl(repoUrl), forceRefresh = false) ?: return@runCatching null
			val store = when (bytes.firstOrNull()) {
				OPEN_BRACE -> {
					parseRepoInfo(repoUrl, bytes.decodeToString())?.let { return@runCatching it }
					json.decodeFromString<NetworkExtensionStore>(bytes.decodeToString())
				}
				OPEN_BRACKET, null -> return@runCatching null
				else -> protoBuf.decodeFromByteArray<NetworkExtensionStore>(bytes)
			}
			store.takeIf { it.name.isNotBlank() && it.signingKey.isNotBlank() }?.let {
				ExternalRepoInfo(
					url = repoUrl,
					name = it.name,
					shortName = it.badgeLabel.ifBlank { null },
					fingerprint = it.signingKey,
					website = it.contact?.website?.takeIf(String::isNotBlank),
					discord = it.contact?.discord?.takeIf(String::isNotBlank),
				)
			}
		}.getOrNull()
	}

	/**
	 * Resolves the APK download URL for an extension.
	 * Follows the Mihon convention: APKs are stored at `${baseRepoUrl}/apk/${apkName}`.
	 * If [apkName] is already an absolute URL it is returned unchanged.
	 */
	fun resolveApkUrl(repoUrl: String, apkName: String): String {
		if (apkName.startsWith("http://") || apkName.startsWith("https://")) {
			return apkName
		}
		val base = getBaseUrl(repoUrl)
		return "$base/apk/$apkName"
	}

	/**
	 * Resolves the icon URL for an extension package.
	 * Icons are stored at `${baseRepoUrl}/icon/${packageName}.png`.
	 */
	fun resolveIconUrl(repoUrl: String, packageName: String): String {
		val base = getBaseUrl(repoUrl)
		return "$base/icon/$packageName.png"
	}

	/**
	 * Ensures the repo URL points to an index file (index.min.json or index.pb).
	 * Accepts both the base URL and the full index URL.
	 */
	private fun buildIndexUrl(repoUrl: String): String {
		val base = repoUrl.trimEnd('/')
		return when {
			base.endsWith(".json") || base.endsWith(".pb") -> base
			else -> "$base/index.min.json"
		}
	}

	/**
	 * Returns the base repo URL (without the trailing /index.min.json, /index.pb or other filename).
	 */
	private fun getBaseUrl(repoUrl: String): String {
		val trimmed = repoUrl.trimEnd('/')
		return when {
			trimmed.endsWith(".json") || trimmed.endsWith(".pb") -> trimmed.substringBeforeLast('/')
			else -> trimmed
		}
	}

	private companion object {
		const val OPEN_BRACKET: Byte = 91 // '[' — legacy JSON array index
		const val OPEN_BRACE: Byte = 123 // '{' — JSON object (repo.json or store); else protobuf
		const val MAX_INDEX_HOPS = 3
	}
}

data class ValidatedExtensionStore(
	val store: ExtensionStoreRecord,
	val catalog: List<ExternalExtensionRepoEntry>,
)
