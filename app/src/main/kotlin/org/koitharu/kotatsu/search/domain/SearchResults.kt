package org.haziffe.dropsauce.search.domain

import org.haziffe.dropsauce.parsers.model.Manga
import org.haziffe.dropsauce.parsers.model.MangaListFilter
import org.haziffe.dropsauce.parsers.model.SortOrder

data class SearchResults(
	val listFilter: MangaListFilter,
	val sortOrder: SortOrder,
	val manga: List<Manga>,
)
