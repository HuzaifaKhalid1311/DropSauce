package org.koitharu.kotatsu.core.prefs

/** How one manga's chapter list is ordered; "reverse list" flips any of them. */
enum class ChaptersSortOrder {
	/** As the source hands it back. */
	SOURCE,

	/** Natural order on the shown chapter name. */
	NAME,

	/** Oldest upload first; chapters without a date go last. */
	DATE,
}
