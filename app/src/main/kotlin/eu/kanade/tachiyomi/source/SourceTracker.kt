package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga

/**
 * Optional Tsundoku source-api 1.6 contract. Kept for binary compatibility with novel APKs that
 * implement remote progress tracking; loading such a source must not fail with NoClassDefFoundError.
 */
interface SourceTracker {
	val supportsChapterTracking: Boolean
		get() = true

	val supportsFavoritesTracking: Boolean
		get() = false

	suspend fun onChaptersRead(
		manga: SManga,
		changedChapters: List<SChapter>,
		allChapters: List<SChapter>,
		categories: List<String>,
	) = Unit

	suspend fun onChaptersUnread(
		manga: SManga,
		changedChapters: List<SChapter>,
		allChapters: List<SChapter>,
		categories: List<String>,
	) = Unit

	suspend fun onFavorited(manga: SManga, categories: List<String>) = Unit

	suspend fun onUnfavorited(manga: SManga, categories: List<String>) = Unit
}
