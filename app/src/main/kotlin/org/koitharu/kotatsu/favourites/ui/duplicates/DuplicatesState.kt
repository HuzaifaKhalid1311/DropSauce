package org.koitharu.kotatsu.favourites.ui.duplicates

import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource

sealed interface DuplicatesState {

	/** Still deciding whether there is anything to ask about — the sheet stays invisible. */
	data object Checking : DuplicatesState

	data class Ask(
		val incoming: Manga,
		val cards: List<DuplicateCardModel>,
		val remaining: Int,
	) : DuplicatesState {

		val isMigrating: Boolean
			get() = cards.any { it.isMigrating }
	}
}

class MigrationResult(
	val title: String,
	val fromSource: MangaSource,
	val toSource: MangaSource,
)
