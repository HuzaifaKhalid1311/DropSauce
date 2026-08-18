package org.koitharu.kotatsu.mihon

import eu.kanade.tachiyomi.source.model.FilterList

/**
 * Implemented by Mihon-backed repositories ([MihonMangaRepository] and its lazy proxy) to expose the
 * source's dynamic [FilterList]. Lets [org.koitharu.kotatsu.filter.ui.FilterCoordinator] detect a
 * dynamic-filter source and load its default filters without leaking Mihon types into the core
 * `MangaRepository` interface.
 */
interface MihonFilterHost {

	/** True when this repository serves a Mihon source whose filters should use the dynamic filter UI. */
	val supportsDynamicFilters: Boolean

	/** Returns a fresh [FilterList] in its default state (a new instance on every call). */
	suspend fun loadDefaultFilterList(): FilterList

	/**
	 * True when the in-app Popular/Latest listings are served by dedicated endpoints that take no
	 * filters, so switching to one replaces the applied filters instead of combining with them —
	 * Mihon's own behaviour. Sources whose listing call accepts filters (LNReader plugins) leave it
	 * false and keep the filters applied.
	 */
	val inAppListingsExclusive: Boolean
		get() = false
}
