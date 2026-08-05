package org.koitharu.kotatsu.explore.ui.model

import org.koitharu.kotatsu.list.ui.model.ListModel

/**
 * Both halves of the extension list, built together so the Explore pager can show either page
 * instantly instead of rebuilding the list on every tab switch.
 */
data class ExploreSources(
	val manga: List<ListModel>,
	val novel: List<ListModel>,
) {

	operator fun get(isNovel: Boolean): List<ListModel> = if (isNovel) novel else manga
}
