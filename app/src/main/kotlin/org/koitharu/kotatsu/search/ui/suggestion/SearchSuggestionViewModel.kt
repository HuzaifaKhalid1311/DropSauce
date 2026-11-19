package org.koitharu.kotatsu.search.ui.suggestion

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.SearchSuggestionType
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.prefs.observeAsStateFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.search.domain.MangaSearchRepository
import org.koitharu.kotatsu.search.ui.suggestion.model.SearchSuggestionItem
import javax.inject.Inject

private const val DEBOUNCE_TIMEOUT = 300L
private const val MAX_MANGA_ITEMS = 12
private const val MAX_QUERY_ITEMS = 16
private const val MAX_HINTS_ITEMS = 3
private const val MAX_AUTHORS_ITEMS = 2
private const val MAX_SOURCES_ITEMS = 6
private const val MAX_SOURCES_TIPS_ITEMS = 2

@HiltViewModel
class SearchSuggestionViewModel @Inject constructor(
	private val repository: MangaSearchRepository,
	private val settings: AppSettings,
) : BaseViewModel() {

	private val query = MutableStateFlow("")
	private val invalidationTrigger = MutableStateFlow(0)

	/** Genres ticked in the chip row, searched together with the search button. */
	val selectedTags = MutableStateFlow<List<String>>(emptyList())

	val isIncognitoModeEnabled = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_INCOGNITO_MODE,
		valueProducer = { isIncognitoModeEnabled },
	)

	val suggestion: Flow<List<SearchSuggestionItem>> = combine(
		// No wait for the empty query: that's what shows the moment the search bar opens.
		query.debounce { if (it.isEmpty()) 0L else DEBOUNCE_TIMEOUT },
		settings.observeAsFlow(AppSettings.KEY_SEARCH_SUGGESTION_TYPES) { searchSuggestionTypes },
		settings.observeAsFlow(AppSettings.KEY_QUICK_FILTER) { isQuickFilterEnabled },
		invalidationTrigger,
		selectedTags,
	)
	{ searchQuery, types, isQuickFilterEnabled, _, selected ->
		SearchSuggestionRequest(searchQuery, types, isQuickFilterEnabled, selected)
	}.mapLatest { request ->
		buildSearchSuggestion(request)
	}.distinctUntilChanged()
		.withErrorHandling()
		.flowOn(Dispatchers.Default)

	fun onQueryChanged(newQuery: String) {
		query.value = newQuery
		if (newQuery.isNotEmpty()) {
			clearTags() // typing is a text search
		}
	}

	fun toggleTag(tag: String) {
		selectedTags.update { if (tag in it) it - tag else it + tag }
	}

	fun clearTags() {
		selectedTags.value = emptyList()
	}

	fun addQuickTag(tag: String) {
		val title = tag.trim()
		val tags = settings.searchQuickTags
		if (title.isNotEmpty() && tags.none { it.equals(title, ignoreCase = true) }) {
			settings.searchQuickTags = tags + title
			invalidationTrigger.value++
		}
	}

	fun removeQuickTag(tag: String) {
		settings.searchQuickTags -= tag
		selectedTags.update { it - tag }
		invalidationTrigger.value++
	}

	fun hideSourceSuggestion(source: MangaSource) {
		launchJob(Dispatchers.Default) {
			repository.hideSourceSuggestion(source)
			invalidationTrigger.value++
		}
	}

	fun saveQuery(query: String) {
		if (!settings.isIncognitoModeEnabled) {
			repository.saveSearchQuery(query)
			invalidationTrigger.value++
		}
	}

	fun clearSearchHistory() {
		launchJob(Dispatchers.Default) {
			repository.clearSearchHistory()
			invalidationTrigger.value++
		}
	}

	fun deleteQuery(query: String) {
		launchJob(Dispatchers.Default) {
			repository.deleteSearchQuery(query)
			invalidationTrigger.value++
		}
	}

	private suspend fun buildSearchSuggestion(
		request: SearchSuggestionRequest,
	): List<SearchSuggestionItem> = coroutineScope {
		// Suggestions are deliberately unscoped: manga/novel is picked on the results screen.
		listOfNotNull(
			// The genre chip row under the search bar acts as a quick filter, so it follows the
			// "Show quick filters" appearance setting in addition to the genre suggestion type.
			if (request.isQuickFilterEnabled && SearchSuggestionType.GENRES in request.types) {
				async { getTags(request.query, request.selectedTags) }
			} else {
				null
			},
			if (SearchSuggestionType.MANGA in request.types) {
				async { getManga(request.query) }
			} else {
				null
			},
			if (SearchSuggestionType.QUERIES_RECENT in request.types) {
				async { getRecentQueries(request.query) }
			} else {
				null
			},
			if (SearchSuggestionType.QUERIES_SUGGEST in request.types) {
				async { getQueryHints(request.query) }
			} else {
				null
			},
			if (SearchSuggestionType.SOURCES in request.types) {
				async { getSources(request.query) }
			} else {
				null
			},
			if (SearchSuggestionType.RECENT_SOURCES in request.types) {
				async { getRecentSources(request.query) }
			} else {
				null
			},
			if (SearchSuggestionType.AUTHORS in request.types) {
				async {
					getAuthors(request.query)
				}
			} else {
				null
			},
		).flatMap { it.await() }
	}

	private suspend fun getAuthors(searchQuery: String): List<SearchSuggestionItem> = runCatchingCancellable {
		repository.getAuthorsSuggestion(searchQuery, MAX_AUTHORS_ITEMS)
			.map { SearchSuggestionItem.Author(it) }
	}.getOrElse { e ->
		e.printStackTraceDebug()
		listOf(SearchSuggestionItem.Text(0, e))
	}

	private suspend fun getQueryHints(searchQuery: String): List<SearchSuggestionItem> = runCatchingCancellable {
		repository.getQueryHintSuggestion(searchQuery, MAX_HINTS_ITEMS)
			.map { SearchSuggestionItem.Hint(it) }
	}.getOrElse { e ->
		e.printStackTraceDebug()
		listOf(SearchSuggestionItem.Text(0, e))
	}

	private suspend fun getRecentQueries(searchQuery: String): List<SearchSuggestionItem> = runCatchingCancellable {
		repository.getQuerySuggestion(searchQuery, MAX_QUERY_ITEMS)
			.map { SearchSuggestionItem.RecentQuery(it) }
	}.getOrElse { e ->
		e.printStackTraceDebug()
		listOf(SearchSuggestionItem.Text(0, e))
	}

	/** The user's quick genre chips and the chip that adds one; only while the bar is empty (typing = a text search). */
	private fun getTags(searchQuery: String, selected: List<String>): List<SearchSuggestionItem> {
		if (searchQuery.isNotEmpty()) {
			return emptyList()
		}
		val chips = settings.searchQuickTags.sortedBy { it.lowercase() }.map { tag ->
			ChipsView.ChipModel(title = tag, isChecked = tag in selected, data = tag)
		} + ChipsView.ChipModel(
			titleResId = R.string.add,
			icon = R.drawable.ic_quick_tag_add,
			data = SearchSuggestionItem.Tags.AddChip,
		)
		return listOf(SearchSuggestionItem.Tags(chips, selectedCount = selected.size))
	}

	private suspend fun getManga(searchQuery: String): List<SearchSuggestionItem> = runCatchingCancellable {
		val manga = repository.getMangaSuggestion(searchQuery, MAX_MANGA_ITEMS, null)
		if (manga.isEmpty()) {
			emptyList()
		} else {
			listOf(SearchSuggestionItem.MangaList(manga))
		}
	}.getOrElse { e ->
		e.printStackTraceDebug()
		listOf(SearchSuggestionItem.Text(0, e))
	}

	private fun getSources(searchQuery: String): List<SearchSuggestionItem> =
		runCatchingCancellable {
			repository.getSourcesSuggestion(searchQuery, MAX_SOURCES_ITEMS)
				.map { SearchSuggestionItem.Source(it) }
		}.getOrElse { e ->
			e.printStackTraceDebug()
			listOf(SearchSuggestionItem.Text(0, e))
		}

	private suspend fun getRecentSources(searchQuery: String): List<SearchSuggestionItem> =
		if (searchQuery.isEmpty()) {
			runCatchingCancellable {
				repository.getSourcesSuggestion(MAX_SOURCES_TIPS_ITEMS)
					.map { SearchSuggestionItem.SourceTip(it) }
			}.getOrElse { e ->
				e.printStackTraceDebug()
				listOf(SearchSuggestionItem.Text(0, e))
			}
		} else {
			emptyList()
		}

	private data class SearchSuggestionRequest(
		val query: String,
		val types: Set<SearchSuggestionType>,
		val isQuickFilterEnabled: Boolean,
		val selectedTags: List<String>,
	)
}
