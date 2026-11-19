package org.koitharu.kotatsu.search.domain

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.contains
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.mihon.MihonFilterHost
import org.koitharu.kotatsu.mihon.MihonFilterMapper
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.almostEquals
import org.koitharu.kotatsu.parsers.util.levenshteinDistance
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable

private const val MATCH_THRESHOLD_DEFAULT = 0.2f

class SearchV2Helper @AssistedInject constructor(
	@Assisted private val source: MangaSource,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val dataRepository: MangaDataRepository,
	private val settings: AppSettings,
) {

	suspend operator fun invoke(query: String, kind: SearchKind): SearchResults? {
		if (settings.isNsfwContentDisabled && source.isNsfw()) {
			return null
		}
		val repository = mangaRepositoryFactory.create(source)
		val listFilter = repository.getFilter(query, kind) ?: return null
		val sortOrder = repository.getSortOrder(kind)
		val list = repository.getList(0, sortOrder, listFilter)
		if (list.isEmpty()) {
			return null
		}
		val result = list.toMutableList()
		result.postFilter(query, kind)
		result.sortByRelevance(query, kind)
		return SearchResults(
			listFilter = listFilter,
			sortOrder = sortOrder,
			manga = result,
			// getGenreFilter puts the genres it couldn't tick into the text query
			isGenreFallback = kind == SearchKind.TAG && !listFilter.query.isNullOrEmpty(),
		)
	}

	private suspend fun MangaRepository.getFilter(query: String, kind: SearchKind): MangaListFilter? = when (kind) {
		SearchKind.SIMPLE,
		SearchKind.TITLE -> if (filterCapabilities.isSearchSupported) {
			MangaListFilter(query = query)
		} else {
			null
		}

		SearchKind.AUTHOR -> if (filterCapabilities.isAuthorSearchSupported) {
			MangaListFilter(author = query)
		} else if (filterCapabilities.isSearchSupported) {
			MangaListFilter(query = query)
		} else {
			null
		}

		SearchKind.TAG -> {
			val genres = splitTagQuery(query)
			if (genres.isEmpty()) {
				null
			} else if (this is MihonFilterHost && supportsDynamicFilters) {
				getGenreFilter(this, genres)
			} else {
				val tags = this@SearchV2Helper.dataRepository.findTags(this.source) + runCatchingCancellable {
					this@getFilter.getFilterOptions().availableTags
				}.onFailure { e ->
					e.printStackTraceDebug()
				}.getOrDefault(emptySet())
				val picked = genres.mapNotNull { genre -> tags.find { x -> x.title.equals(genre, ignoreCase = true) } }
				if (picked.size == genres.size) {
					MangaListFilter(tags = picked.toSet())
				} else {
					null
				}
			}
		}
	}

	/**
	 * Mihon's genre search (BrowseSourceViewModel.searchGenre), for several genres at once: tick each
	 * genre the extension has as a filter, and search the names of the ones it doesn't as text.
	 */
	private suspend fun MangaRepository.getGenreFilter(
		host: MihonFilterHost,
		genres: List<String>,
	): MangaListFilter? {
		val defaults = host.loadDefaultFilterList()
		val working = host.loadDefaultFilterList()
		val usedSelects = ArrayList<Filter.Select<*>>()
		val missing = genres.filterNot { working.selectGenre(it, usedSelects) }
		if (missing.isNotEmpty() && !filterCapabilities.isSearchSupported) {
			return null
		}
		return MangaListFilter(
			query = missing.joinToString(" ").ifEmpty { null },
			tags = MihonFilterMapper.encode(working, defaults, source),
		)
	}

	/** Same matching as Mihon: a checkbox/tri-state named [genre] in a top-level group, or a select option. */
	private fun FilterList.selectGenre(genre: String, usedSelects: MutableList<Filter.Select<*>>): Boolean {
		for (filter in this) {
			if (filter is Filter.Group<*>) {
				val match = filter.state.firstOrNull { it is Filter<*> && it.name.equals(genre, ignoreCase = true) }
				when (match) {
					is Filter.TriState -> match.state = Filter.TriState.STATE_INCLUDE
					is Filter.CheckBox -> match.state = true
					else -> continue
				}
				return true
			} else if (filter is Filter.Select<*>) {
				val index = filter.values.indexOfFirst { it.toString().equals(genre, ignoreCase = true) }
				// A select holds one value: a second genre in the same select can't be ticked too.
				if (index != -1 && usedSelects.none { it === filter }) {
					filter.state = index
					usedSelects += filter
					return true
				}
			}
		}
		return false
	}

	private fun MutableList<Manga>.postFilter(query: String, kind: SearchKind) {
		if (settings.isNsfwContentDisabled) {
			removeAll { it.isNsfw() }
		}
		when (kind) {
			SearchKind.TITLE -> retainAll { m ->
				m.matches(query, MATCH_THRESHOLD_DEFAULT)
			}

			SearchKind.AUTHOR -> retainAll { m ->
				m.authors.isEmpty() || m.authors.contains(query, ignoreCase = true)
			}

			SearchKind.SIMPLE, // no filtering expected
			SearchKind.TAG -> Unit
		}
	}

	private fun MutableList<Manga>.sortByRelevance(query: String, kind: SearchKind) {
		when (kind) {
			SearchKind.SIMPLE,
			SearchKind.TITLE -> sortBy { m ->
				minOf(
					m.title.levenshteinDistance(query),
					m.altTitles.firstOrNull()?.levenshteinDistance(query) ?: Int.MAX_VALUE,
				)
			}

			SearchKind.AUTHOR -> sortByDescending { m ->
				m.authors.contains(query, ignoreCase = true)
			}

			SearchKind.TAG -> {
				val genres = splitTagQuery(query)
				sortByDescending { m ->
					m.tags.count { tag -> genres.any { it.equals(tag.title, ignoreCase = true) } }
				}
			}
		}
	}

	private fun MangaRepository.getSortOrder(kind: SearchKind): SortOrder {
		val preferred: SortOrder = when (kind) {
			SearchKind.SIMPLE,
			SearchKind.TITLE,
			SearchKind.AUTHOR -> SortOrder.RELEVANCE

			SearchKind.TAG -> SortOrder.POPULARITY
		}
		return if (preferred in sortOrders) {
			preferred
		} else {
			defaultSortOrder
		}
	}


	private fun Manga.matches(query: String, threshold: Float): Boolean {
		return matchesTitles(title, query, threshold) || matchesTitles(altTitles.firstOrNull(), query, threshold)
	}

	private fun matchesTitles(a: String?, b: String?, threshold: Float): Boolean {
		return !a.isNullOrEmpty() && !b.isNullOrEmpty() && a.almostEquals(b, threshold)
	}

	@AssistedFactory
	interface Factory {

		fun create(source: MangaSource): SearchV2Helper
	}
}
