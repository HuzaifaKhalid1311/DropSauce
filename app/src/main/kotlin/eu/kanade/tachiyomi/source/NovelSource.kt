package eu.kanade.tachiyomi.source

/**
 * Compatibility marker for Tsundoku novel extensions compiled against source-api 1.6.
 *
 * Novel detection and text fetching still dispatch through [Source.isNovelSource] and
 * [Source.fetchPageText]; this interface intentionally adds no separate behavior.
 */
@Deprecated("Detection is via Source.isNovelSource; fetchPageText is on Source")
interface NovelSource : Source

fun Source.isNovelSource(): Boolean = isNovelSource
