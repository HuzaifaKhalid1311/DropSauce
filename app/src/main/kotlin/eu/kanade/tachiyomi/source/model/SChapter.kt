@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject
import java.io.Serializable

interface SChapter : Serializable {
	var url: String
	var name: String
	var chapter_number: Float
	var scanlator: String?
	var date_upload: Long
	var locked: Boolean
		get() = false
		set(_) {}

	/**
	 * Local state accessors required by Tsundoku source-api 1.6. Sources normally leave these alone;
	 * defaults keep older extension implementations binary-compatible.
	 */
	var read: Boolean
		get() = false
		set(_) {}

	var last_page_read: Int
		get() = 0
		set(_) {}

	var memo: JsonObject

	fun copyFrom(other: SChapter) {
		name = other.name
		url = other.url
		date_upload = other.date_upload
		chapter_number = other.chapter_number
		scanlator = other.scanlator
		memo = other.memo
	}

	companion object {
		fun create(): SChapter = SChapterImpl()
	}
}
