package org.koitharu.kotatsu.scrobbling.mangaupdates.data

import kotlin.math.roundToInt

internal const val STATUS_READING = "reading"
internal const val STATUS_WISH_LIST = "wish_list"
internal const val STATUS_COMPLETE = "complete"
internal const val STATUS_UNFINISHED = "unfinished"
internal const val STATUS_ON_HOLD = "on_hold"

/** Index == MangaUpdates `list_id`: 0 reading, 1 wish list, 2 complete, 3 unfinished, 4 on hold. */
internal val LIST_STATUSES = listOf(
	STATUS_READING,
	STATUS_WISH_LIST,
	STATUS_COMPLETE,
	STATUS_UNFINISHED,
	STATUS_ON_HOLD,
)

/** MangaUpdates rates from 1.0 to 10.0 in steps of 0.1. */
internal const val RATING_SCALE = 10f
private const val RATING_MIN = 1.0
private const val RATING_MAX = 10.0

internal const val SEARCH_PAGE_SIZE = 25

private const val SERIES_ID_PREFIX = "id:"

/** The MangaUpdates list id of a stored [status], or -1 when it is not one of the five lists. */
internal fun listIdOf(status: String?): Int = LIST_STATUSES.indexOf(status)

/** The 1-based page that starts at [offset] items, which is how MangaUpdates paginates. */
internal fun pageOf(offset: Int): Int = offset / SEARCH_PAGE_SIZE + 1

/**
 * `id:12345` or `id:pb8ztfm` is a way to look a series up directly, as in Mihon. Returns what follows
 * the prefix, or `null` when [this] is an ordinary search.
 */
internal fun String.seriesIdQueryOrNull(): String? {
	val query = trim()
	if (!query.startsWith(SERIES_ID_PREFIX, ignoreCase = true)) return null
	return query.substring(SERIES_ID_PREFIX.length).trim()
}

/**
 * Series ids are numbers in the API but base 36 in the website's links (`/series/pb8ztfm`), so both
 * are accepted. An id made only of digits reads as a plain number, as it does in Mihon.
 */
internal fun String.toSeriesIdOrNull(): Long? = (toLongOrNull() ?: toLongOrNull(36))?.takeIf { it > 0 }

/**
 * A rating on the 0..10 scale as the API wants it: whole 0.1 steps within 1.0..10.0, or `null` for
 * "no rating" (the API deletes the rating rather than accept 0).
 */
internal fun Float.toApiRating(): Double? {
	if (this <= 0f) return null
	return (toDouble() * 10).roundToInt().div(10.0).coerceIn(RATING_MIN, RATING_MAX)
}

/** An API rating as the 0..1 fraction the app keeps. */
internal fun Double?.toRatingFraction(): Float = ((this ?: 0.0) / RATING_MAX).toFloat().coerceIn(0f, 1f)

private const val CREDENTIALS_SEPARATOR = '\n'

internal fun encodeCredentials(username: String, password: String): String =
	username + CREDENTIALS_SEPARATOR + password

/** The `(username, password)` inside [code], or `null` when it is not a pair or either is empty. */
internal fun decodeCredentials(code: String): Pair<String, String>? {
	if (CREDENTIALS_SEPARATOR !in code) return null
	val username = code.substringBefore(CREDENTIALS_SEPARATOR)
	val password = code.substringAfter(CREDENTIALS_SEPARATOR)
	return if (username.isEmpty() || password.isEmpty()) null else username to password
}
