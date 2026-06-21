package org.koitharu.kotatsu.settings.compose

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Tiny process-wide bus that lets the settings-search flow ask a freshly-opened Compose screen
 * to highlight a specific row once. Rows are matched by their preference key or (localized) title.
 *
 * [SettingsActivity] sets [pendingKey]/[pendingTitle] right after navigating to a search result; the matching
 * [SettingsItem] flashes its background a single time and then calls [consume] to clear it, so the
 * highlight never repeats on recomposition or re-entry.
 */
object SettingsSearchHighlight {

	val pendingKey = MutableStateFlow<String?>(null)
	val pendingTitle = MutableStateFlow<String?>(null)

	fun request(key: String?, title: String?) {
		pendingKey.value = key?.takeIf { it.isNotBlank() }
		pendingTitle.value = title?.takeIf { it.isNotBlank() }
	}

	fun consume(key: String?, title: String) {
		if (key != null && pendingKey.value == key) {
			pendingKey.value = null
			pendingTitle.value = null
		} else if (pendingTitle.value == title) {
			pendingKey.value = null
			pendingTitle.value = null
		}
	}

	fun clear() {
		pendingKey.value = null
		pendingTitle.value = null
	}
}
