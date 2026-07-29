package org.koitharu.kotatsu.lnreader.model

import org.json.JSONObject
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLangCode
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageLabel
import org.koitharu.kotatsu.parsers.model.MangaSource

/**
 * Metadata extracted from a plugin at install time, cached to `plugin.json` so Explore can render
 * before the JS realm has booted.
 */
data class LnPlugin(
	val id: String,
	val name: String,
	val site: String,
	val lang: String,
	val version: String,
	val iconUrl: String,
	/** Raw `imageRequestInit` object, applied as headers when loading covers. */
	val imageRequestInit: String?,
	/** Raw `filters` object; decoded lazily by LnFilterMapper. */
	val filters: String?,
	/** Raw `pluginSettings` object. */
	val pluginSettings: String?,
	val hasParsePage: Boolean,
	val hasResolveUrl: Boolean,
	/** Store this plugin was installed from, so the catalog can name its provider. */
	val storeId: String? = null,
) {

	fun toJson(): JSONObject = JSONObject()
		.put("storeId", storeId ?: JSONObject.NULL)
		.put("id", id)
		.put("name", name)
		.put("site", site)
		.put("lang", lang)
		.put("version", version)
		.put("iconUrl", iconUrl)
		.put("imageRequestInit", imageRequestInit ?: JSONObject.NULL)
		.put("filters", filters ?: JSONObject.NULL)
		.put("pluginSettings", pluginSettings ?: JSONObject.NULL)
		.put("hasParsePage", hasParsePage)
		.put("hasResolveUrl", hasResolveUrl)

	companion object {

		fun fromJson(json: JSONObject) = LnPlugin(
			id = json.getString("id"),
			name = json.optString("name").ifEmpty { json.getString("id") },
			site = json.optString("site"),
			lang = json.optString("lang"),
			version = json.optString("version", "0"),
			iconUrl = json.optString("iconUrl"),
			imageRequestInit = json.optJsonString("imageRequestInit"),
			filters = json.optJsonString("filters"),
			pluginSettings = json.optJsonString("pluginSettings"),
			hasParsePage = json.optBoolean("hasParsePage"),
			hasResolveUrl = json.optBoolean("hasResolveUrl"),
			storeId = json.optString("storeId").takeIf { it.isNotEmpty() },
		)

		private fun JSONObject.optJsonString(key: String): String? =
			if (isNull(key)) null else get(key).toString()
	}
}

/**
 * An LNReader novel plugin presented as a [MangaSource].
 *
 * Unlike [org.koitharu.kotatsu.mihon.model.MihonMangaSource], identity is plain name equality: the
 * plugin id is already a stable string, so there is no numeric id to parse and no `<id>:<name>`
 * suffix form to tolerate.
 */
data class LnMangaSource(val plugin: LnPlugin) : MangaSource {

	override val name: String
		get() = "LN_${plugin.id}"

	val pluginId: String
		get() = plugin.id

	val displayName: String
		get() = plugin.name

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		return other is MangaSource && other.name == name
	}

	override fun hashCode(): Int = name.hashCode()

	override fun toString(): String = "LnMangaSource(id=${plugin.id}, name=${plugin.name}, lang=${plugin.lang})"
}

/**
 * LNReader plugins declare `lang` inconsistently — some a BCP-47 code ("en"), some a name
 * ("English"). Both resolve to a translated label; anything unrecognised shows as the plugin wrote it.
 */
val LnPlugin.languageLabel: String
	get() = getExternalExtensionLanguageLabel(lang)

/** The plugin's language as a BCP-47 code, for comparing against the catalog's language filter. */
val LnPlugin.langCode: String
	get() = getExternalExtensionLangCode(lang)
