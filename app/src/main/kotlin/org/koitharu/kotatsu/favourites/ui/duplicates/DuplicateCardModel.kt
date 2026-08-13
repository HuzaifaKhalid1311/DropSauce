package org.koitharu.kotatsu.favourites.ui.duplicates

import androidx.compose.runtime.Immutable
import org.koitharu.kotatsu.favourites.domain.MangaDuplicate
import org.koitharu.kotatsu.parsers.model.Manga

/**
 * One existing favourite shown in the duplicates sheet.
 *
 * @param incomingChapters chapters of the manga being added, or null while it is still unknown —
 * the difference arrow stays hidden rather than lying about a zero
 */
@Immutable
data class DuplicateCardModel(
	val duplicate: MangaDuplicate,
	val incomingChapters: Int?,
	val isMigrating: Boolean,
	val isBlocked: Boolean,
) {

	val manga: Manga
		get() = duplicate.manga

	/** Positive means the copy already in the library has that many more chapters. */
	val chaptersDiff: Int
		get() = if (incomingChapters == null || incomingChapters == 0 || duplicate.chaptersCount == 0) {
			0
		} else {
			duplicate.chaptersCount - incomingChapters
		}
}
