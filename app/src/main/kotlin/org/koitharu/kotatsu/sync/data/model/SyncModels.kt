package org.koitharu.kotatsu.sync.data.model

/** What the user chose to sync on this device. A disabled type is neither published nor applied. */
enum class SyncContent(val key: String) {
	FAVOURITES("favourites"),
	HISTORY("history"),
	BOOKMARKS("bookmarks"),
	FEED("feed"),
	TRACKING("tracking"),
	STATS("stats"),
	SETTINGS("settings"),
	CUSTOM_COVERS("custom_covers"),
	;

	companion object {

		val DEFAULT: Set<String> = entries.mapTo(LinkedHashSet()) { it.key }

		fun fromKeys(keys: Set<String>): Set<SyncContent> =
			entries.filterTo(LinkedHashSet()) { it.key in keys }
	}
}
