package org.koitharu.kotatsu.settings.sources.catalog

import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.mihon.model.ExternalRepoInfo
import java.net.URI
import java.util.UUID

@Serializable
data class ExtensionStoreRecord(
	val id: String,
	val indexUrl: String,
	val name: String,
	val shortName: String? = null,
	val fingerprint: String? = null,
	val website: String? = null,
	val discord: String? = null,
	val enabled: Boolean = true,
) {
	val displayName: String
		get() = shortName?.takeIf(String::isNotBlank) ?: name
}

@Serializable
enum class ExtensionInstallMode {
	SYSTEM,
	SANDBOX,
}

@Serializable
data class ExtensionStoreOwnership(
	val mode: ExtensionInstallMode,
	val packageName: String,
	val storeId: String,
)

sealed interface ExtensionCatalogPage {
	val id: String

	data object Available : ExtensionCatalogPage {
		override val id: String = "available"
	}

	data class Store(
		override val id: String,
		val title: String,
	) : ExtensionCatalogPage

	data object Empty : ExtensionCatalogPage {
		override val id: String = "empty"
	}
}

fun buildExtensionCatalogPages(
	stores: List<ExtensionStoreRecord>,
): List<ExtensionCatalogPage> {
	val labels = extensionStoreDisplayLabels(stores)
	return buildList {
		add(ExtensionCatalogPage.Available)
		stores.mapTo(this) { store ->
			ExtensionCatalogPage.Store(store.id, labels[store.id] ?: store.displayName)
		}
	}
}

internal fun shouldShowCatalogFilters(pages: List<ExtensionCatalogPage>): Boolean =
	pages.any { it != ExtensionCatalogPage.Empty }

internal fun shouldShowStoreTabDivider(position: Int, page: ExtensionCatalogPage): Boolean =
	position == 1 && page is ExtensionCatalogPage.Store

internal fun canUseStoreCatalogForUpdates(health: StoreHealth): Boolean =
	health != StoreHealth.UNAVAILABLE

@Serializable
data class ExtensionStoreRegistryState(
	val stores: List<ExtensionStoreRecord> = emptyList(),
	val ownerships: List<ExtensionStoreOwnership> = emptyList(),
) {

	fun containsStoreUrl(indexUrl: String): Boolean {
		val normalizedUrl = normalizeExtensionStoreUrl(indexUrl)
		return stores.any { normalizeExtensionStoreUrl(it.indexUrl).equals(normalizedUrl, ignoreCase = true) }
	}

	fun add(store: ExtensionStoreRecord): Result<ExtensionStoreRegistryState> {
		val normalizedUrl = normalizeExtensionStoreUrl(store.indexUrl)
		val fingerprint = store.fingerprint?.takeIf(String::isNotBlank)
		val duplicate = stores.any {
			normalizeExtensionStoreUrl(it.indexUrl).equals(normalizedUrl, ignoreCase = true) ||
				(fingerprint != null && it.fingerprint.equals(fingerprint, ignoreCase = true))
		}
		return if (duplicate) {
			Result.failure(IllegalArgumentException("Store already exists"))
		} else {
			Result.success(copy(stores = stores + store.copy(indexUrl = normalizedUrl)))
		}
	}

	fun replace(store: ExtensionStoreRecord): ExtensionStoreRegistryState =
		copy(stores = stores.map { current -> if (current.id == store.id) store else current })

	fun editStore(storeId: String, replacement: ExtensionStoreRecord): Result<ExtensionStoreRegistryState> {
		val current = stores.firstOrNull { it.id == storeId }
			?: return Result.failure(IllegalArgumentException("Store not found"))
		val normalizedUrl = normalizeExtensionStoreUrl(replacement.indexUrl)
		val fingerprint = replacement.fingerprint?.takeIf(String::isNotBlank)
		val duplicate = stores.any {
			it.id != storeId && (
				normalizeExtensionStoreUrl(it.indexUrl).equals(normalizedUrl, ignoreCase = true) ||
					(fingerprint != null && it.fingerprint.equals(fingerprint, ignoreCase = true))
				)
		}
		if (duplicate) return Result.failure(IllegalArgumentException("Store already exists"))
		return Result.success(
			replace(
				replacement.copy(
					id = current.id,
					indexUrl = normalizedUrl,
					enabled = current.enabled,
				),
			),
		)
	}

	fun removeStore(storeId: String): ExtensionStoreRegistryState = copy(
		stores = stores.filterNot { it.id == storeId },
		ownerships = ownerships.filterNot { it.storeId == storeId },
	)

	fun move(fromIndex: Int, toIndex: Int): ExtensionStoreRegistryState {
		if (fromIndex !in stores.indices || toIndex !in stores.indices || fromIndex == toIndex) return this
		val reordered = stores.toMutableList()
		reordered.add(toIndex, reordered.removeAt(fromIndex))
		return copy(stores = reordered)
	}

	fun setOwner(
		mode: ExtensionInstallMode,
		packageName: String,
		storeId: String,
	): ExtensionStoreRegistryState {
		if (stores.none { it.id == storeId }) return this
		val ownership = ExtensionStoreOwnership(mode, packageName, storeId)
		return copy(
			ownerships = ownerships.filterNot { it.mode == mode && it.packageName == packageName } + ownership,
		)
	}

	fun removeOwner(mode: ExtensionInstallMode, packageName: String): ExtensionStoreRegistryState = copy(
		ownerships = ownerships.filterNot { it.mode == mode && it.packageName == packageName },
	)

	fun ownerId(mode: ExtensionInstallMode, packageName: String): String? =
		ownerships.firstOrNull { it.mode == mode && it.packageName == packageName }?.storeId

	fun cleanupDisabledStores(): ExtensionStoreRegistryState {
		val enabledStores = stores.filter { it.enabled }
		val enabledStoreIds = enabledStores.mapTo(HashSet()) { it.id }
		return copy(
			stores = enabledStores,
			ownerships = ownerships.filter { it.storeId in enabledStoreIds },
		)
	}

	fun reconcileOwnerships(
		systemPackages: Set<String>,
		sandboxPackages: Set<String>,
	): ExtensionStoreRegistryState = copy(
		ownerships = ownerships.filter { ownership ->
			when (ownership.mode) {
				ExtensionInstallMode.SYSTEM -> ownership.packageName in systemPackages
				ExtensionInstallMode.SANDBOX -> ownership.packageName in sandboxPackages
			}
		},
	).cleanupDisabledStores()
}

fun normalizeExtensionStoreUrl(value: String): String {
	val trimmed = value.trim().trimEnd('/')
	val withIndex = when {
		trimmed.endsWith("/index.min.json", ignoreCase = true) -> trimmed
		trimmed.endsWith(".json", ignoreCase = true) || trimmed.endsWith(".pb", ignoreCase = true) -> trimmed
		else -> "$trimmed/index.min.json"
	}
	return runCatching {
		val uri = URI(withIndex)
		URI(
			uri.scheme?.lowercase(),
			uri.userInfo,
			uri.host?.lowercase(),
			uri.port,
			uri.path,
			uri.query,
			null,
		).toString()
	}.getOrDefault(withIndex)
}

fun migrateLegacyExtensionStores(
	activeUrl: String?,
	legacyOwners: Map<String, String>,
	systemPackages: Set<String>,
	sandboxPackages: Set<String>,
	repoInfos: List<ExternalRepoInfo>,
): ExtensionStoreRegistryState {
	val normalizedActiveUrl = activeUrl?.takeIf(String::isNotBlank)?.let(::normalizeExtensionStoreUrl)
	val urls = buildList {
		normalizedActiveUrl?.let(::add)
		legacyOwners.values
			.asSequence()
			.filter(String::isNotBlank)
			.map(::normalizeExtensionStoreUrl)
			.filterNot(::contains)
			.forEach(::add)
	}
	val infoByUrl = repoInfos.associateBy { normalizeExtensionStoreUrl(it.url) }
	val stores = urls.map { url ->
		val info = infoByUrl[url]
		ExtensionStoreRecord(
			id = stableExtensionStoreId(url),
			indexUrl = url,
			name = info?.name ?: extensionStoreUrlLabel(url),
			shortName = info?.shortName,
			fingerprint = info?.fingerprint,
			website = info?.website,
			discord = info?.discord,
			enabled = url == normalizedActiveUrl,
		)
	}
	val idByUrl = stores.associate { it.indexUrl to it.id }
	val ownerships = buildList {
		for ((packageName, ownerUrl) in legacyOwners) {
			val storeId = idByUrl[normalizeExtensionStoreUrl(ownerUrl)] ?: continue
			if (packageName in systemPackages) {
				add(ExtensionStoreOwnership(ExtensionInstallMode.SYSTEM, packageName, storeId))
			}
			if (packageName in sandboxPackages) {
				add(ExtensionStoreOwnership(ExtensionInstallMode.SANDBOX, packageName, storeId))
			}
		}
	}
	return ExtensionStoreRegistryState(stores, ownerships).cleanupDisabledStores()
}

fun stableExtensionStoreId(indexUrl: String): String =
	UUID.nameUUIDFromBytes(normalizeExtensionStoreUrl(indexUrl).lowercase().toByteArray()).toString()

/**
 * Human label for a store that publishes no `repo.json` — every LNReader plugin index, plus legacy
 * Mihon repos served straight off a raw file host. Naming these by host + path printed most of the
 * url back, because the index usually sits several directories deep.
 */
fun extensionStoreUrlLabel(indexUrl: String): String = runCatching {
	val uri = URI(normalizeExtensionStoreUrl(indexUrl))
	val host = uri.host ?: return@runCatching null
	val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
	when {
		// raw.githubusercontent.com/<owner>/<repo>/… and github.com/<owner>/<repo>/…
		host.endsWith("githubusercontent.com") || host == "github.com" -> segments.getOrNull(1)
		host.endsWith("github.io") -> host.substringBefore('.')
		else -> null
	}?.takeIf(String::isNotBlank) ?: host
}.getOrNull() ?: indexUrl

/**
 * Whether any of [fields] matches [query], ignoring case, spacing and punctuation on both sides —
 * "manga fire" has to find "MangaFire", and "mangafire" has to find "Manga Fire".
 *
 * ponytail: normalise-and-substring, not fuzzy scoring. It fixes the whole class of separator
 * mismatches; typo tolerance would need ranked results, which this flat list has no place for.
 */
fun matchesExtensionQuery(query: String?, vararg fields: String?): Boolean {
	if (query.isNullOrBlank()) return true
	val normalizedQuery = query.normalizeForExtensionSearch()
	if (normalizedQuery.isEmpty()) return true
	return fields.any { field ->
		!field.isNullOrEmpty() && field.normalizeForExtensionSearch().contains(normalizedQuery)
	}
}

private fun String.normalizeForExtensionSearch(): String =
	filter(Char::isLetterOrDigit).lowercase()

data class RecommendedExtensionRef(
	val packageName: String,
	val displayName: String,
)

fun collectRecommendedExtensionRefs(
	externalSourceNames: List<String>,
	installedSourceIds: Set<Long>,
	installedPackages: Set<String>,
	repoSourceIndex: Map<Long, Pair<String, String>>,
): List<RecommendedExtensionRef> {
	val seen = HashSet<String>()
	return externalSourceNames.mapNotNull { sourceName ->
		val sourceId = sourceName.removePrefix("MIHON_").substringBefore(':').toLongOrNull() ?: return@mapNotNull null
		if (sourceId in installedSourceIds) return@mapNotNull null
		val (packageName, displayName) = repoSourceIndex[sourceId] ?: return@mapNotNull null
		if (packageName.isBlank() || packageName in installedPackages || !seen.add(packageName)) return@mapNotNull null
		RecommendedExtensionRef(packageName, displayName)
	}
}
