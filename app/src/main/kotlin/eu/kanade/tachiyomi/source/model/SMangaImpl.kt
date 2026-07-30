@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject

class SMangaImpl : SManga {
	override lateinit var url: String
	override lateinit var title: String
	override var thumbnail_url: String? = null
	override var artist: String? = null
	override var author: String? = null
	override var status: Int = 0
	override var description: String? = null
	override var genre: String? = null
	override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE
	override var initialized: Boolean = false
	override var altTitles: List<String> = emptyList()
	// Exact Mihon 1.6 type. A Map here changes getMemo/setMemo JVM descriptors and crashes APKs.
	override var memo: JsonObject = JsonObject(emptyMap())

	// Mihon's persistence/update layers identify source models by URL. Keep equality URL-based so
	// distinct/update operations do not duplicate the same source item after conversion.
	override fun equals(other: Any?): Boolean =
		this === other || (other is SManga && runCatching { url == other.url }.getOrDefault(false))

	override fun hashCode(): Int = runCatching { url.hashCode() }.getOrDefault(0)
}
