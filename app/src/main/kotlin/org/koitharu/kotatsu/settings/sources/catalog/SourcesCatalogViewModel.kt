package org.koitharu.kotatsu.settings.sources.catalog

import androidx.annotation.WorkerThread
import androidx.lifecycle.viewModelScope
import androidx.room.invalidationTrackerFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_SOURCES
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.mapSortedByCount
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.explore.data.SourcesSortOrder
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.util.EnumSet
import java.util.LinkedHashSet
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SourcesCatalogViewModel @Inject constructor(
	private val repository: MangaSourcesRepository,
	db: MangaDatabase,
	settings: AppSettings,
) : BaseViewModel() {

	val onActionDone = MutableEventFlow<ReversibleAction>()
	private val builtInLocales: Set<String?> = repository.allMangaSources.mapTo(LinkedHashSet<String?>()) { it.locale }.also {
		it.add(null)
	}
	private val builtInContentTypes = MutableStateFlow<List<ContentType>>(emptyList())
	private val mihonSources = repository.observeMihonSources()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, emptyList())

	private val searchQuery = MutableStateFlow<String?>(null)
	val appliedFilter = MutableStateFlow(
		SourcesCatalogFilter(
			mode = SourcesCatalogMode.BUILTIN,
			types = emptySet(),
			locale = Locale.getDefault().language.takeIf { it in builtInLocales },
			isNewOnly = false,
		),
	)

	val hasNewSources = combine(
		appliedFilter,
		repository.observeHasNewSources(),
	) { filter, hasNewSources ->
		filter.mode == SourcesCatalogMode.BUILTIN && hasNewSources
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

	val locales: StateFlow<Set<String?>> = combine(
		appliedFilter,
		mihonSources,
	) { filter, sources ->
		when (filter.mode) {
			SourcesCatalogMode.BUILTIN -> builtInLocales
			SourcesCatalogMode.MIHON -> sources.toLocaleSet()
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, builtInLocales)

	val contentTypes: StateFlow<List<ContentType>> = combine(
		appliedFilter,
		builtInContentTypes,
	) { filter, types ->
		if (filter.mode == SourcesCatalogMode.BUILTIN) {
			types
		} else {
			emptyList()
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	val content: StateFlow<List<ListModel>> = combine(
		searchQuery,
		appliedFilter,
		db.invalidationTrackerFlow(TABLE_SOURCES),
		mihonSources,
	) { q, f, _, _ ->
		buildSourcesList(f, q)
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	init {
		repository.clearNewSourcesBadge()
		launchJob(Dispatchers.Default) {
			builtInContentTypes.value = getContentTypes(settings.isNsfwContentDisabled)
		}
	}

	fun performSearch(query: String?) {
		searchQuery.value = query?.trim()
	}

	fun setMode(value: SourcesCatalogMode) {
		val filter = appliedFilter.value
		if (filter.mode == value) {
			return
		}
		val locales = when (value) {
			SourcesCatalogMode.BUILTIN -> builtInLocales
			SourcesCatalogMode.MIHON -> mihonSources.value.toLocaleSet()
		}
		appliedFilter.value = filter.copy(
			mode = value,
			types = emptySet(),
			locale = filter.locale?.takeIf { it in locales },
			isNewOnly = if (value == SourcesCatalogMode.BUILTIN) filter.isNewOnly else false,
		)
	}

	fun setLocale(value: String?) {
		appliedFilter.value = appliedFilter.value.copy(locale = value)
	}

	fun addSource(source: MangaSource) {
		if (source !is MangaParserSource) {
			return
		}
		launchJob(Dispatchers.Default) {
			val rollback = repository.setSourcesEnabled(setOf(source), true)
			onActionDone.call(ReversibleAction(R.string.source_enabled, rollback))
		}
	}

	fun setContentType(value: ContentType, isAdd: Boolean) {
		val filter = appliedFilter.value
		val types = EnumSet.noneOf(ContentType::class.java)
		types.addAll(filter.types)
		if (isAdd) {
			types.add(value)
		} else {
			types.remove(value)
		}
		appliedFilter.value = filter.copy(types = types)
	}

	fun setNewOnly(value: Boolean) {
		appliedFilter.value = appliedFilter.value.copy(isNewOnly = value)
	}

	private suspend fun buildSourcesList(filter: SourcesCatalogFilter, query: String?): List<SourceCatalogItem> {
		val sources = when (filter.mode) {
			SourcesCatalogMode.BUILTIN -> repository.queryParserSources(
				isDisabledOnly = true,
				isNewOnly = filter.isNewOnly,
				excludeBroken = false,
				types = filter.types,
				query = query,
				locale = filter.locale,
				sortOrder = SourcesSortOrder.ALPHABETIC,
			)
			SourcesCatalogMode.MIHON -> repository.queryMihonSources(
				query = query,
				locale = filter.locale,
			)
		}
		return if (sources.isEmpty()) {
			listOf(
				if (query == null) {
					SourceCatalogItem.Hint(
						icon = R.drawable.ic_empty_feed,
						title = R.string.no_manga_sources,
						text = R.string.no_manga_sources_catalog_text,
					)
				} else {
					SourceCatalogItem.Hint(
						icon = R.drawable.ic_empty_feed,
						title = R.string.nothing_found,
						text = R.string.no_manga_sources_found,
					)
				},
			)
		} else {
			sources.map {
				SourceCatalogItem.Source(
					source = it,
					isAddAvailable = filter.mode == SourcesCatalogMode.BUILTIN,
				)
			}
		}
	}

	@WorkerThread
	private fun getContentTypes(isNsfwDisabled: Boolean): List<ContentType> {
		val result = repository.allMangaSources.mapSortedByCount { it.contentType }
		return if (isNsfwDisabled) {
			result.filterNot { it == ContentType.HENTAI }
		} else {
			result
		}
	}

	private fun List<MihonMangaSource>.toLocaleSet(): Set<String?> = mapTo(LinkedHashSet<String?>()) { it.language }.also {
		it.add(null)
	}
}
