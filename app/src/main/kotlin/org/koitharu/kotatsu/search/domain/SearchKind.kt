package org.koitharu.kotatsu.search.domain

enum class SearchKind {

	SIMPLE, TITLE, AUTHOR, TAG
}

/** A [SearchKind.TAG] query can hold several genres; results must have all of them. */
private const val TAG_SEPARATOR = ", "

fun joinTagQuery(genres: Collection<String>): String = genres.joinToString(TAG_SEPARATOR)

fun splitTagQuery(query: String): List<String> =
	query.split(TAG_SEPARATOR.trim()).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
