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

	suspend fun validateStore(repoUrl: String, forceRefresh: Boolean = true): ValidatedExtensionStore =
		withContext(Dispatchers.IO) {
			val normalizedUrl = normalizeExtensionStoreUrl(repoUrl)
			require(normalizedUrl.startsWith("https://")) { "Store index URL must use HTTPS" }

			val resolvedTarget = if (!normalizedUrl.endsWith(".pb", ignoreCase = true)) {
				resolveRepoJsonTarget(normalizedUrl, forceRefresh)
			} else {
				null
			}

			val targetIndexUrl = resolvedTarget?.indexUrl ?: buildIndexUrl(normalizedUrl)
			var storeData = loadStoreData(targetIndexUrl, forceRefresh, cacheOnly = false)

			// Safeguard against deprecated stub index.min.json (e.g. Keiyoushi dummy notice)
			if (
				storeData.catalog.isNotEmpty() &&
				storeData.catalog.all { it.packageName in DEPRECATED_DUMMY_PACKAGES }
			) {
				val pbCandidateUrl = "${getBaseUrl(targetIndexUrl)}/index.pb"
				val pbStoreData = runCatching {
					loadStoreData(pbCandidateUrl, forceRefresh, cacheOnly = false)
				}.getOrNull()
				if (pbStoreData != null && pbStoreData.catalog.isNotEmpty()) {
					storeData = pbStoreData
				}
			}

			val effectiveIndexUrl = storeData.resolvedUrl ?: targetIndexUrl
			val info = storeData.info ?: resolvedTarget?.repoInfo ?: fetchRepoInfo(effectiveIndexUrl, forceRefresh)

			ValidatedExtensionStore(
				store = ExtensionStoreRecord(
					id = stableExtensionStoreId(effectiveIndexUrl),
					indexUrl = effectiveIndexUrl,
					name = info?.name ?: extensionStoreUrlLabel(effectiveIndexUrl),
					shortName = info?.shortName,
					fingerprint = info?.fingerprint,
					website = info?.website,
					discord = info?.discord,
				),
				catalog = storeData.catalog.filterNot { it.lang in MihonExtensionLoader.HIDDEN_LANGUAGES },
			)
		}

	private fun resolveRepoJsonTarget(
		repoUrl: String,
		forceRefresh: Boolean,
	): ResolvedStoreTarget? {
		val baseUrl = getBaseUrl(repoUrl)
		val repoJsonUrl = "$baseUrl/repo.json"
		val bytes = runCatching { fetchBytes(repoJsonUrl, forceRefresh) }.getOrNull() ?: return null
		val text = bytes.decodeToString()
		val repoJson = runCatching { json.decodeFromString<ExternalRepoJson>(text) }.getOrNull()
		val repoInfo = parseRepoInfo(repoUrl, text)
		val indexV2 = repoJson?.indexV2?.takeIf(String::isNotBlank)?.let { resolveIndexV2Url(baseUrl, it) }
		return if (indexV2 != null) {
			ResolvedStoreTarget(indexUrl = indexV2, repoInfo = repoInfo)
		} else {
			ResolvedStoreTarget(indexUrl = buildIndexUrl(repoUrl), repoInfo = repoInfo)
		}
	}

	private fun resolveIndexV2Url(baseUrl: String, indexV2: String): String {
		if (indexV2.startsWith("https://", ignoreCase = true) || indexV2.startsWith("http://", ignoreCase = true)) {
			return indexV2
		}
		val base = baseUrl.trimEnd('/')
		val relative = indexV2.trimStart('/')
		return "$base/$relative"
	}

	private fun loadEntries(
		url: String,
		forceRefresh: Boolean,
		cacheOnly: Boolean,
		depth: Int = 0,
	): List<ExternalExtensionRepoEntry> {
		val data = loadStoreData(url, forceRefresh, cacheOnly, depth)
		if (data.catalog.isNotEmpty() && data.catalog.all { it.packageName in DEPRECATED_DUMMY_PACKAGES } && depth < MAX_INDEX_HOPS) {
			val pbCandidateUrl = "${getBaseUrl(url)}/index.pb"
			val pbData = runCatching { loadStoreData(pbCandidateUrl, forceRefresh, cacheOnly, depth + 1) }.getOrNull()
			if (!pbData?.catalog.isNullOrEmpty()) {
				return pbData.catalog
			}
		}
		return data.catalog
	}

	private fun loadStoreData(
		url: String,
		forceRefresh: Boolean,
		cacheOnly: Boolean,
		depth: Int = 0,
	): LoadedStoreData {
		if (depth > MAX_INDEX_HOPS) return LoadedStoreData(emptyList())
		val bytes = fetchBytes(url, forceRefresh, cacheOnly) ?: return LoadedStoreData(emptyList())
		return when (bytes.firstOrNull()) {
			OPEN_BRACKET -> {
				val text = bytes.decodeToString()
				val asMihon = runCatching { json.decodeFromString<List<ExternalExtensionRepoEntry>>(text) }
				val catalog = asMihon.getOrNull() ?: runCatching {
					json.decodeFromString<List<LnStoreEntry>>(text).map(LnStoreEntry::toRepoEntry)
				}.getOrElse { lnError ->
					throw asMihon.exceptionOrNull() ?: lnError
				}
				LoadedStoreData(catalog = catalog, resolvedUrl = url)
			}
			OPEN_BRACE -> {
				val text = bytes.decodeToString()
				val repoJson = runCatching { json.decodeFromString<ExternalRepoJson>(text) }.getOrNull()
				if (repoJson != null && (repoJson.indexV2 != null || repoJson.meta.signingKeyFingerprint.isNotBlank())) {
					val targetUrl = repoJson.indexV2?.let { resolveIndexV2Url(getBaseUrl(url), it) }
						?: "${getBaseUrl(url)}/index.min.json"
					val next = loadStoreData(targetUrl, forceRefresh, cacheOnly, depth + 1)
					val info = parseRepoInfo(url, text)
					next.copy(
						info = next.info ?: info,
						resolvedUrl = next.resolvedUrl ?: targetUrl,
					)
				} else {
					val store = json.decodeFromString<NetworkExtensionStore>(text)
					LoadedStoreData(
						catalog = storeEntries(store, forceRefresh, cacheOnly, depth),
						info = store.toExternalRepoInfo(url),
						resolvedUrl = url,
					)
				}
			}
			null -> LoadedStoreData(emptyList())
			else -> {
				val store = runCatching { protoBuf.decodeFromByteArray<NetworkExtensionStore>(bytes) }.getOrNull()
				if (store != null && (store.extensionList != null || store.extensionListUrl != null)) {
					LoadedStoreData(
						catalog = storeEntries(store, forceRefresh, cacheOnly, depth),
						info = store.toExternalRepoInfo(url),
						resolvedUrl = url,
					)
				} else {
					val list = runCatching { protoBuf.decodeFromByteArray<NetworkExtensionStore.ExtensionList>(bytes) }.getOrNull()
					val catalog = list?.extensions?.map(NetworkExtensionStore.Extension::toRepoEntry)
						?: storeEntries(store ?: NetworkExtensionStore(), forceRefresh, cacheOnly, depth)
					LoadedStoreData(
						catalog = catalog,
						info = store?.toExternalRepoInfo(url),
						resolvedUrl = url,
					)
				}
			}
		}
	}

	private fun NetworkExtensionStore.toExternalRepoInfo(url: String): ExternalRepoInfo? =
		takeIf { it.name.isNotBlank() && it.signingKey.isNotBlank() }?.let {
			ExternalRepoInfo(
				url = url,
				name = it.name,
				shortName = it.badgeLabel.ifBlank { null },
				fingerprint = it.signingKey,
				website = it.contact?.website?.takeIf(String::isNotBlank),
				discord = it.contact?.discord?.takeIf(String::isNotBlank),
			)
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
	suspend fun fetchRepoInfo(
		repoUrl: String,
		forceRefresh: Boolean = false,
	): ExternalRepoInfo? = withContext(Dispatchers.IO) {
		runCatching {
			val bytes = fetchBytes("${getBaseUrl(repoUrl)}/repo.json", forceRefresh) ?: return@runCatching null
			parseRepoInfo(repoUrl, bytes.decodeToString())
		}.getOrNull()
	}

	private suspend fun fetchIndexRepoInfo(
		repoUrl: String,
		forceRefresh: Boolean,
	): ExternalRepoInfo? = withContext(Dispatchers.IO) {
		runCatching {
			loadStoreData(buildIndexUrl(repoUrl), forceRefresh, cacheOnly = false).info
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

	private data class LoadedStoreData(
		val catalog: List<ExternalExtensionRepoEntry>,
		val info: ExternalRepoInfo? = null,
		val resolvedUrl: String? = null,
	)

	private data class ResolvedStoreTarget(
		val indexUrl: String,
		val repoInfo: ExternalRepoInfo? = null,
	)

	private companion object {
		val DEPRECATED_DUMMY_PACKAGES = setOf(
			"eu.kanade.tachiyomi.extension.all.keiyoushi",
			"eu.kanade.tachiyomi.extension.all.mihon",
		)
		const val OPEN_BRACKET: Byte = 91 // '[' — legacy JSON array index
		const val OPEN_BRACE: Byte = 123 // '{' — JSON object (repo.json or store); else protobuf
		const val MAX_INDEX_HOPS = 3
	}
}

data class ValidatedExtensionStore(
	val store: ExtensionStoreRecord,
	val catalog: List<ExternalExtensionRepoEntry>,
)
