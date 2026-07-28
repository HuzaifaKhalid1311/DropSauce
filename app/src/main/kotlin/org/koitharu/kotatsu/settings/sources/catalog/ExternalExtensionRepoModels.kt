package org.koitharu.kotatsu.settings.sources.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.protobuf.ProtoNumber
import org.koitharu.kotatsu.mihon.model.ExternalRepoInfo
import org.koitharu.kotatsu.mihon.model.MihonExtensionInfo
import org.koitharu.kotatsu.mihon.model.MihonLoadResult

@Serializable
data class ExternalExtensionRepoEntry(
	@SerialName("name") val name: String,
	@SerialName("pkg") val packageName: String,
	@SerialName("apk") val apkName: String,
	@SerialName("lang") val lang: String? = null,
	@SerialName("code") val versionCode: Long,
	@SerialName("version") val versionName: String,
	@SerialName("nsfw") val isNsfw: Int = 0,
	/** The catalogue sources this extension provides — lets us map a `MIHON_<id>` library entry
	 *  back to its installable package + display name regardless of where the entry came from. */
	@SerialName("sources") val sources: List<ExternalExtensionRepoSource> = emptyList(),
	/** Absolute icon URL supplied by newer stores; null for legacy repos (icon resolved by convention). */
	@SerialName("iconUrl") val iconUrl: String? = null,
)

/**
 * An entry in an LNReader plugin index — a plain JSON array of these. It is told apart from a legacy
 * Mihon array by the absence of `pkg`/`apk`/`code`, which makes the Mihon decode fail outright.
 */
@Serializable
data class LnStoreEntry(
	val id: String,
	val name: String,
	val site: String = "",
	val lang: String = "",
	val version: String = "0",
	/** Absolute url of the raw plugin code. */
	val url: String,
	val iconUrl: String = "",
)

/**
 * Presents a novel plugin as an ordinary store entry so the whole catalog UI, store health, download
 * and update machinery works unchanged. `apkName` carries the absolute `.js` url, which is both what
 * `resolveApkUrl` passes through untouched and how [isLnPlugin] recognises the row later.
 */
fun LnStoreEntry.toRepoEntry() = ExternalExtensionRepoEntry(
	name = name,
	packageName = id,
	apkName = url,
	lang = lang,
	versionCode = 0L,
	versionName = version,
	iconUrl = iconUrl.takeIf { it.isNotEmpty() },
)

val ExternalExtensionRepoEntry.isLnPlugin: Boolean
	get() = apkName.substringBefore('?').endsWith(".js", ignoreCase = true)

/** What a store publishes. Derived from its catalog — no index format declares this. */
enum class ExtensionStoreKind { MANGA, NOVEL, MIXED }

/** Null while the catalog is empty, i.e. the store is still being checked or is unreachable. */
fun List<ExternalExtensionRepoEntry>.extensionStoreKind(): ExtensionStoreKind? = when (count { it.isLnPlugin }) {
	0 -> if (isEmpty()) null else ExtensionStoreKind.MANGA
	size -> ExtensionStoreKind.NOVEL
	else -> ExtensionStoreKind.MIXED
}

// --- Newer "extension store" index format (Mihon #3349+): a single object, served either as JSON or
// protobuf (index.pb), optionally gzip-compressed, optionally with its extension list in a separate
// file (extensionListUrl). We decode it and map it back onto ExternalExtensionRepoEntry so nothing
// downstream has to care which format a repo uses. Field names/numbers mirror Mihon's model. ---

@Serializable
internal data class NetworkExtensionStore(
	@ProtoNumber(1) val name: String = "",
	@ProtoNumber(2) val badgeLabel: String = "",
	@ProtoNumber(3) val signingKey: String = "",
	@ProtoNumber(4) val contact: Contact? = null,
	@ProtoNumber(101) val extensionList: ExtensionList? = null,
	@ProtoNumber(102) val extensionListUrl: String? = null,
) {
	@Serializable
	data class Contact(
		@ProtoNumber(1) val website: String = "",
		@ProtoNumber(2) val discord: String? = null,
	)

	@Serializable
	data class ExtensionList(@ProtoNumber(1) val extensions: List<Extension> = emptyList())

	@Serializable
	data class Extension(
		@ProtoNumber(1) val name: String = "",
		@ProtoNumber(2) val packageName: String = "",
		@ProtoNumber(3) val resources: Resources = Resources(),
		@ProtoNumber(4) val extensionLib: String = "",
		@ProtoNumber(5) val versionCode: Long = 0,
		@ProtoNumber(6) val versionName: String = "",
		@ProtoNumber(7) val contentWarning: ContentWarning = ContentWarning.UNSPECIFIED,
		@ProtoNumber(8) val sources: List<Source> = emptyList(),
	)

	@Serializable
	data class Resources(
		@ProtoNumber(1) val apkUrl: String = "",
		@ProtoNumber(2) val iconUrl: String = "",
	)

	@Serializable
	data class Source(
		@ProtoNumber(1) val id: Long = 0,
		@ProtoNumber(2) val name: String = "",
		@ProtoNumber(3) val language: String = "",
		@ProtoNumber(4) val homeUrl: String = "",
		@ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
		@ProtoNumber(7) val message: String? = null,
	)

	@Serializable
	enum class ContentWarning {
		@ProtoNumber(0) @JsonNames("CONTENT_WARNING_UNSPECIFIED") UNSPECIFIED,
		@ProtoNumber(1) @JsonNames("CONTENT_WARNING_SAFE") SAFE,
		@ProtoNumber(2) @JsonNames("CONTENT_WARNING_MIXED") MIXED,
		@ProtoNumber(3) @JsonNames("CONTENT_WARNING_NSFW") NSFW,
	}
}

/** Maps a newer-format store extension onto the entry type the rest of the app already speaks. */
internal fun NetworkExtensionStore.Extension.toRepoEntry(): ExternalExtensionRepoEntry {
	val langs = sources.map { it.language }.filter { it.isNotBlank() }.toSet()
	return ExternalExtensionRepoEntry(
		name = name,
		packageName = packageName,
		apkName = resources.apkUrl, // absolute; resolveApkUrl returns absolute URLs unchanged
		lang = when (langs.size) {
			0 -> null
			1 -> langs.first()
			else -> "all"
		},
		versionCode = versionCode,
		versionName = versionName,
		// contentWarning replaced the old nsfw bool; MIXED/NSFW map to the existing nsfw flag.
		isNsfw = if (contentWarning >= NetworkExtensionStore.ContentWarning.MIXED) 1 else 0,
		sources = sources.map { ExternalExtensionRepoSource(id = it.id.toString(), name = it.name, lang = it.language) },
		iconUrl = resources.iconUrl.ifBlank { null },
	)
}

/** A repo's `repo.json`: authoritative name + signing fingerprint, plus an optional pointer to the
 *  newer index (`index_v2`, e.g. an `index.pb`) when the repo has migrated. */
@Serializable
internal data class ExternalRepoJson(
	@SerialName("index_v2") val indexV2: String? = null,
	@SerialName("meta") val meta: Meta = Meta(),
) {
	@Serializable
	data class Meta(
		@SerialName("name") val name: String = "",
		@SerialName("shortName") val shortName: String? = null,
		@SerialName("website") val website: String? = null,
		@SerialName("discord") val discord: String? = null,
		@SerialName("signingKeyFingerprint") val signingKeyFingerprint: String = "",
	)
}

private val repoJsonParser = Json { ignoreUnknownKeys = true }

/**
 * Parses a repo's `repo.json` body into [ExternalRepoInfo]. Handles both the legacy `{ meta: {...} }`
 * shape and the newer store shape (top-level `name`/`signingKey`). Returns null if the body isn't
 * valid repo metadata (e.g. the repo doesn't publish one) so callers fall back to URL-derived naming.
 */
internal fun parseRepoInfo(repoUrl: String, body: String): ExternalRepoInfo? {
	// Legacy: { "meta": { "name", "shortName", "signingKeyFingerprint" } }
	runCatching { repoJsonParser.decodeFromString<ExternalRepoJson>(body) }.getOrNull()?.meta
		?.takeIf { it.name.isNotBlank() && it.signingKeyFingerprint.isNotBlank() }
		?.let {
			return ExternalRepoInfo(
				url = repoUrl,
				name = it.name,
				shortName = it.shortName,
				fingerprint = it.signingKeyFingerprint,
				website = it.website,
				discord = it.discord,
			)
		}
	// Newer store: { "name", "badgeLabel", "signingKey", ... }
	runCatching { repoJsonParser.decodeFromString<NetworkExtensionStore>(body) }.getOrNull()
		?.takeIf { it.name.isNotBlank() && it.signingKey.isNotBlank() }
		?.let {
			return ExternalRepoInfo(
				url = repoUrl,
				name = it.name,
				shortName = it.badgeLabel.ifBlank { null },
				fingerprint = it.signingKey,
				website = it.contact?.website?.takeIf(String::isNotBlank),
				discord = it.contact?.discord?.takeIf(String::isNotBlank),
			)
		}
	return null
}

@Serializable
data class ExternalExtensionRepoSource(
	@SerialName("id") val id: String = "",
	@SerialName("name") val name: String = "",
	@SerialName("lang") val lang: String? = null,
)

/**
 * A source-api ABI bump is an update even when the extension's own version code is unchanged.
 */
fun ExternalExtensionRepoEntry.isNewerThan(local: MihonExtensionInfo): Boolean {
	return isNewerThan(local.versionCode, local.libVersion)
}

fun ExternalExtensionRepoEntry.isNewerThan(local: MihonLoadResult.Success): Boolean {
	return isNewerThan(local.versionCode, local.libVersion)
}

private fun ExternalExtensionRepoEntry.isNewerThan(localVersionCode: Long, localLibVersion: Double): Boolean {
	val availableLibVersion = versionName.split('.').take(2).joinToString(".").toDoubleOrNull() ?: 0.0
	return versionCode > localVersionCode || availableLibVersion > localLibVersion
}
