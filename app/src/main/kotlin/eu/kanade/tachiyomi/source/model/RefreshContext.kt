package eu.kanade.tachiyomi.source.model

/**
 * State handed to a source when it re-fetches a known manga's chapter list, so it can skip work.
 * Fork API (Tsundoku, extensions-lib 1.6): novel sources that paginate their chapter list override
 * `getChapterList(manga, context)` instead of the single-argument form, so the host has to call this
 * variant or it would only ever see the first page of chapters.
 *
 * Field names and types must match the extension-side declaration exactly.
 */
data class RefreshContext(
	/** The host's own id for the manga being refreshed. */
	val mangaId: Long,
	/** Chapters already stored locally, in source order. */
	val existingChapters: List<SChapter>,
	/** When the manga was last refreshed successfully, in millis; 0 when unknown. */
	val lastFetchTime: Long,
	/** Asks the source to re-fetch everything and skip any count-based short circuit. */
	val forceRefresh: Boolean = false,
)
