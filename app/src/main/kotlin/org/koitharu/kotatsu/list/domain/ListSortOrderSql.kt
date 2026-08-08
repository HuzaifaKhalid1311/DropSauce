package org.koitharu.kotatsu.list.domain

/**
 * Builds the `ORDER BY` clause for a sorted manga list. Every query that uses it must join `manga`.
 *
 * The three expressions are passed in because they live in different places per table: the history
 * table has them as plain columns, while favourites has to reach into `history` for them.
 */
fun ListSortOrder.toOrderBy(
	dateAdded: String,
	lastRead: String,
	progress: String,
): String {
	val direction = if (isAscending) "ASC" else "DESC"
	val totalChapters = "(SELECT COUNT(*) FROM chapters WHERE chapters.manga_id = manga.manga_id)"
	val unreadCount = "($totalChapters * (1.0 - $progress))"
	val expression = when (type) {
		ListSortOrder.Type.ALPHABETICAL -> "manga.title"

		ListSortOrder.Type.TOTAL_CHAPTERS -> totalChapters

		ListSortOrder.Type.LAST_READ -> lastRead

		// Like Mihon, entries with nothing left to read stay at the bottom whichever way you sort.
		// An empty chapter cache means "not fetched yet", not "fully read", so it is left out of that.
		ListSortOrder.Type.UNREAD_COUNT -> return "CASE WHEN $totalChapters > 0 AND $unreadCount < 1 " +
			"THEN 1 ELSE 0 END ASC, $unreadCount $direction, manga.title ASC"

		// tracks.last_chapter_date is only filled in for tracked manga, so the cached chapters win
		ListSortOrder.Type.LATEST_CHAPTER ->
			"IFNULL((SELECT MAX(upload_date) FROM chapters WHERE chapters.manga_id = manga.manga_id), " +
				"IFNULL((SELECT last_chapter_date FROM tracks WHERE tracks.manga_id = manga.manga_id), 0))"

		ListSortOrder.Type.DATE_ADDED -> dateAdded

		ListSortOrder.Type.PROGRESS -> progress

		ListSortOrder.Type.NEW_CHAPTERS ->
			"IFNULL((SELECT chapters_new FROM tracks WHERE tracks.manga_id = manga.manga_id), 0)"
	}
	// alphabetical tiebreak, same as Mihon's comparator
	return "$expression $direction, manga.title ASC"
}
