package org.koitharu.kotatsu.favourites.domain.model

import org.koitharu.kotatsu.parsers.model.Manga

/**
 * An existing favourite that looks like the manga the user is about to add.
 *
 * [chaptersCount] comes from the local cache only; it is 0 when nothing has been cached yet.
 * Duplicate detection never goes online, so this is intentionally best-effort.
 */
data class DuplicateMatch(
	val manga: Manga,
	val chaptersCount: Int,
)

/** All matches found for a single manga the user is adding. */
data class DuplicateGroup(
	val target: Manga,
	val targetChaptersCount: Int,
	val matches: List<DuplicateMatch>,
)
