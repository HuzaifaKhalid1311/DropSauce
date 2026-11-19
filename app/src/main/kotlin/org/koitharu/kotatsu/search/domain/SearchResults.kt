package org.koitharu.kotatsu.search.domain

import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.SortOrder

data class SearchResults(
	val listFilter: MangaListFilter,
	val sortOrder: SortOrder,
	val manga: List<Manga>,
	/** A genre search where the extension lacked a genre as a filter, so it searched the name as text. */
	val isGenreFallback: Boolean = false,
)
