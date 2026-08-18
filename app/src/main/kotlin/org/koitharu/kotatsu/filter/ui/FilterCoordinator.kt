package org.koitharu.kotatsu.filter.ui

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.ViewModelLifecycle
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.util.LocaleComparator
import org.koitharu.kotatsu.core.util.ext.asFlow
import org.koitharu.kotatsu.core.util.ext.lifecycleScope
import org.koitharu.kotatsu.core.util.ext.sortedByOrdinal
import org.koitharu.kotatsu.core.util.ext.sortedWithSafe
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.koitharu.kotatsu.filter.data.PersistableFilter
import org.koitharu.kotatsu.filter.data.SavedFiltersRepository
import org.koitharu.kotatsu.filter.ui.model.FilterProperty
import org.koitharu.kotatsu.filter.ui.tags.TagTitleComparator
import org.koitharu.kotatsu.mihon.MihonFilterHost
import org.koitharu.kotatsu.mihon.MihonFilterMapper
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Demographic
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.YEAR_MIN
import org.koitharu.kotatsu.parsers.util.ifZero
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import org.koitharu.kotatsu.remotelist.ui.RemoteListFragment
import org.koitharu.kotatsu.search.domain.MangaSearchRepository
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

@ViewModelScoped
class FilterCoordinator @Inject constructor(
    savedStateHandle: SavedStateHandle,
    mangaRepositoryFactory: MangaRepository.Factory,
    private val searchRepository: MangaSearchRepository,
    private val savedFiltersRepository: SavedFiltersRepository,
    @ApplicationContext context: Context,
    lifecycle: ViewModelLifecycle,
) {

    private val coroutineScope = lifecycle.lifecycleScope + Dispatchers.Default
    private val repository = mangaRepositoryFactory.create(MangaSource(savedStateHandle[RemoteListFragment.ARG_SOURCE]))
    private val sourceLocale: String? = null
    private val sourceSettings = SourceSettings(context, repository.source)

    private val currentListFilter = MutableStateFlow(restoreSortFilter())
    private val currentSortOrder = MutableStateFlow(repository.defaultSortOrder)
    private val initialSortOrder = repository.defaultSortOrder

    private val availableSortOrders = repository.sortOrders
    private val filterOptions = suspendLazy { repository.getFilterOptions() }

    val capabilities = repository.filterCapabilities

    val mangaSource: MangaSource
        get() = repository.source

    val isFilterApplied: Boolean
        get() = currentListFilter.value.isNotEmpty()

    val query: StateFlow<String?> = currentListFilter.map { it.query }
        .stateIn(coroutineScope, SharingStarted.Eagerly, null)

    val sortOrder: StateFlow<FilterProperty<SortOrder>> = currentSortOrder.map { selected ->
        FilterProperty(
            availableItems = availableSortOrders.sortedByOrdinal(),
            selectedItem = selected,
        )
    }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)

    val tags: StateFlow<FilterProperty<MangaTag>> = combine(
        getTopTags(TAGS_LIMIT),
        currentListFilter.distinctUntilChangedBy { it.tags },
    ) { available, selected ->
        available.fold(
            onSuccess = {
                FilterProperty(
                    availableItems = it.addFirstDistinct(selected.tags),
                    selectedItems = selected.tags,
                )
            },
            onFailure = {
                FilterProperty.error(it)
            },
        )
    }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)

    val tagsExcluded: StateFlow<FilterProperty<MangaTag>> = if (capabilities.isTagsExclusionSupported) {
        combine(
            getBottomTags(TAGS_LIMIT),
            currentListFilter.distinctUntilChangedBy { it.tagsExclude },
        ) { available, selected ->
            available.fold(
                onSuccess = {
                    FilterProperty(
                        availableItems = it.addFirstDistinct(selected.tagsExclude),
                        selectedItems = selected.tagsExclude,
                    )
                },
                onFailure = {
                    FilterProperty.error(it)
                },
            )
        }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)
    } else {
        MutableStateFlow(FilterProperty.EMPTY)
    }

    val authors: StateFlow<FilterProperty<String>> = if (capabilities.isAuthorSearchSupported) {
        combine(
            flow { emit(searchRepository.getAuthors(repository.source, TAGS_LIMIT)) },
            currentListFilter.distinctUntilChangedBy { it.author },
        ) { available, selected ->
            FilterProperty(
                availableItems = available,
                selectedItems = setOfNotNull(selected.author),
            )
        }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)
    } else {
        MutableStateFlow(FilterProperty.EMPTY)
    }

    val states: StateFlow<FilterProperty<MangaState>> = combine(
        filterOptions.asFlow(),
        currentListFilter.distinctUntilChangedBy { it.states },
    ) { available, selected ->
        available.fold(
            onSuccess = {
                FilterProperty(
                    availableItems = it.availableStates.sortedByOrdinal(),
                    selectedItems = selected.states,
                )
            },
            onFailure = {
                FilterProperty.error(it)
            },
        )
    }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)

    val contentRating: StateFlow<FilterProperty<ContentRating>> = combine(
        filterOptions.asFlow(),
        currentListFilter.distinctUntilChangedBy { it.contentRating },
    ) { available, selected ->
        available.fold(
            onSuccess = {
                FilterProperty(
                    availableItems = it.availableContentRating.sortedByOrdinal(),
                    selectedItems = selected.contentRating,
                )
            },
            onFailure = {
                FilterProperty.error(it)
            },
        )
    }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)

    val contentTypes: StateFlow<FilterProperty<ContentType>> = combine(
        filterOptions.asFlow(),
        currentListFilter.distinctUntilChangedBy { it.types },
    ) { available, selected ->
        available.fold(
            onSuccess = {
                FilterProperty(
                    availableItems = it.availableContentTypes.sortedByOrdinal(),
                    selectedItems = selected.types,
                )
            },
            onFailure = {
                FilterProperty.error(it)
            },
        )
    }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)

    val demographics: StateFlow<FilterProperty<Demographic>> = combine(
        filterOptions.asFlow(),
        currentListFilter.distinctUntilChangedBy { it.demographics },
    ) { available, selected ->
        available.fold(
            onSuccess = {
                FilterProperty(
                    availableItems = it.availableDemographics.sortedByOrdinal(),
                    selectedItems = selected.demographics,
                )
            },
            onFailure = {
                FilterProperty.error(it)
            },
        )
    }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)

    val locale: StateFlow<FilterProperty<Locale?>> = combine(
        filterOptions.asFlow(),
        currentListFilter.distinctUntilChangedBy { it.locale },
    ) { available, selected ->
        available.fold(
            onSuccess = {
                FilterProperty(
                    availableItems = it.availableLocales.sortedWithSafe(LocaleComparator()).addFirstDistinct(null),
                    selectedItems = setOfNotNull(selected.locale),
                )
            },
            onFailure = {
                FilterProperty.error(it)
            },
        )
    }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)

    val originalLocale: StateFlow<FilterProperty<Locale?>> = if (capabilities.isOriginalLocaleSupported) {
        combine(
            filterOptions.asFlow(),
            currentListFilter.distinctUntilChangedBy { it.originalLocale },
        ) { available, selected ->
            available.fold(
                onSuccess = {
                    FilterProperty(
                        availableItems = it.availableLocales.sortedWithSafe(LocaleComparator()).addFirstDistinct(null),
                        selectedItems = setOfNotNull(selected.originalLocale),
                    )
                },
                onFailure = {
                    FilterProperty.error(it)
                },
            )
        }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)
    } else {
        MutableStateFlow(FilterProperty.EMPTY)
    }

    val year: StateFlow<FilterProperty<Int>> = if (capabilities.isYearSupported) {
        currentListFilter.distinctUntilChangedBy { it.year }.map { selected ->
            FilterProperty(
                availableItems = listOf(YEAR_MIN, MAX_YEAR),
                selectedItems = setOf(selected.year),
            )
        }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)
    } else {
        MutableStateFlow(FilterProperty.EMPTY)
    }

    val yearRange: StateFlow<FilterProperty<Int>> = if (capabilities.isYearRangeSupported) {
        currentListFilter.distinctUntilChanged { old, new ->
            old.yearTo == new.yearTo && old.yearFrom == new.yearFrom
        }.map { selected ->
            FilterProperty(
                availableItems = listOf(YEAR_MIN, MAX_YEAR),
                selectedItems = setOf(selected.yearFrom.ifZero { YEAR_MIN }, selected.yearTo.ifZero { MAX_YEAR }),
            )
        }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.LOADING)
    } else {
        MutableStateFlow(FilterProperty.EMPTY)
    }

    val savedFilters: StateFlow<FilterProperty<PersistableFilter>> = combine(
        savedFiltersRepository.observeAll(repository.source),
        currentListFilter,
        currentSortOrder,
    ) { available, applied, sortOrder ->
        val appliedWithoutQuery = applied.copy(query = null)
        val appliedSort = if (isDynamicFilter) null else sortOrder.name
        FilterProperty(
            availableItems = available,
            selectedItems = setOfNotNull(
                available.find {
                    val savedSort = it.sortOrder ?: if (isDynamicFilter) null else initialSortOrder.name
                    it.filter == appliedWithoutQuery && savedSort == appliedSort
                },
            ),
        )
    }.stateIn(coroutineScope, SharingStarted.Lazily, FilterProperty.EMPTY)

    val canSaveFilter: StateFlow<Boolean> = combine(currentListFilter, currentSortOrder) { filter, sortOrder ->
        filter.copy(query = null).isNotEmpty() || (!isDynamicFilter && sortOrder != initialSortOrder)
    }.stateIn(coroutineScope, SharingStarted.Eagerly, false)

    private val filterHost: MihonFilterHost?
        get() = repository as? MihonFilterHost

    /** True when the source exposes a dynamic Mihon [FilterList] that should use the dynamic filter UI. */
    val isDynamicFilter: Boolean
        get() = filterHost?.supportsDynamicFilters == true

    init {
        // Persist the active source sort (the "srt@…" tag) per source so it survives app restarts.
        if (isDynamicFilter) {
            currentListFilter
                .map { f -> f.tags.firstOrNull { it.key.startsWith(MihonFilterMapper.SORT_KEY_PREFIX) } }
                .distinctUntilChanged()
                .onEach { sortTag ->
                    sourceSettings.lastSortTagKey = sortTag?.key
                    sourceSettings.lastSortTagTitle = sortTag?.title
                }
                .launchIn(coroutineScope)
        }
    }

    /** Rebuilds the last-saved sort tag (if any) so a dynamic source opens with its remembered sort. */
    private fun restoreSortFilter(): MangaListFilter {
        if (!isDynamicFilter) {
            return MangaListFilter.EMPTY
        }
        val key = sourceSettings.lastSortTagKey
        val title = sourceSettings.lastSortTagTitle
        if (key.isNullOrEmpty() || title == null || !key.startsWith(MihonFilterMapper.SORT_KEY_PREFIX)) {
            return MangaListFilter.EMPTY
        }
        return MangaListFilter(tags = setOf(MangaTag(title = title, key = key, source = repository.source)))
    }

    fun reset() {
        currentListFilter.value = MangaListFilter.EMPTY
    }

    fun resetSourceSort() {
        currentListFilter.update { filter ->
            filter.copy(
                tags = filter.tags.filterNot { it.key.startsWith(MihonFilterMapper.SORT_KEY_PREFIX) }.toSet(),
            )
        }
    }

    /**
     * Loads the source's filters: [WorkingFilters.defaults] in their default state and
     * [WorkingFilters.working] with the currently-applied filter decoded onto it. Both are fresh,
     * independently-mutable instances.
     */
    suspend fun loadWorkingFilters(): WorkingFilters {
        val host = filterHost ?: return WorkingFilters(FilterList(), FilterList())
        val defaults = host.loadDefaultFilterList()
        val working = host.loadDefaultFilterList()
        MihonFilterMapper.decode(working, currentListFilter.value)
        return WorkingFilters(working = working, defaults = defaults)
    }

    /** Encodes the mutated [working] list (vs [defaults]) into the active filter, keeping the search query. */
    fun applyDynamicFilters(working: FilterList, defaults: FilterList) {
        val tags = MihonFilterMapper.encode(working, defaults, repository.source)
        val query = currentListFilter.value.takeQueryIfSupported()
        currentListFilter.value = MangaListFilter(query = query, tags = tags)
    }

    /**
     * Loads the state of the compact sort picker: the in-app listings (Mihon's own Popular/Latest
     * endpoints) plus the source's own sort control, if it has one. A server-side sort is active
     * exactly while an encoded "srt@..." tag is applied, and it replaces the in-app selection.
     */
    suspend fun loadSortState(): SortState {
        val host = filterHost
        if (host?.supportsDynamicFilters == true) {
            val working = host.loadDefaultFilterList()
            MihonFilterMapper.decode(working, currentListFilter.value)
            val hasSourceSort = currentListFilter.value.tags.any {
                it.key.startsWith(MihonFilterMapper.SORT_KEY_PREFIX)
            }
            val sourceSort = when (val ref = MihonFilterMapper.findSortFilter(working)) {
                is MihonFilterMapper.SortRef.OfSort -> SortState.Source(
                    options = ref.filter.values.toList(),
                    // Without an applied sort tag the picker shows the in-app listing as selected, so
                    // the FilterList's own default selection must not be marked here.
                    selectedIndex = if (hasSourceSort) ref.filter.state?.index ?: -1 else -1,
                    isAscending = hasSourceSort && ref.filter.state?.ascending == true,
                    supportsDirection = true,
                )

                is MihonFilterMapper.SortRef.OfSelect -> SortState.Source(
                    options = ref.filter.values.map { it.toString() },
                    selectedIndex = if (hasSourceSort) ref.filter.state else -1,
                    isAscending = false,
                    supportsDirection = false,
                )

                null -> null
            }
            return SortState(
                inAppOptions = IN_APP_SORT_ORDERS.filter { it in availableSortOrders },
                inAppSelected = currentSortOrder.value.takeUnless { hasSourceSort },
                source = sourceSort,
            )
        }
        return SortState(
            inAppOptions = availableSortOrders.sortedByOrdinal(),
            inAppSelected = currentSortOrder.value,
            source = null,
        )
    }

    /**
     * Switches to an in-app listing (Popular/Latest). For Mihon sources those are listings of their
     * own, so — like Mihon's chips — they replace the search+filter state, the source's own sort
     * included. Sources whose listing call takes filters keep them.
     */
    fun applyInAppSort(order: SortOrder) {
        if (filterHost?.inAppListingsExclusive == true) {
            currentListFilter.value = MangaListFilter.EMPTY
        }
        setSortOrder(order)
    }

    /** Applies a source sort selection ([Filter.Sort] or sort [Filter.Select]), preserving other filters. */
    fun applySourceSort(index: Int, isAscending: Boolean) = coroutineScope.launch {
        val host = filterHost ?: return@launch
        val defaults = host.loadDefaultFilterList()
        val working = host.loadDefaultFilterList()
        MihonFilterMapper.decode(working, currentListFilter.value)
        when (val ref = MihonFilterMapper.findSortFilter(working)) {
            is MihonFilterMapper.SortRef.OfSort -> ref.filter.state = Filter.Sort.Selection(index, isAscending)
            is MihonFilterMapper.SortRef.OfSelect -> if (index in ref.filter.values.indices) {
                ref.filter.state = index
            }

            null -> return@launch
        }
        applyDynamicFilters(working, defaults)
    }

    fun snapshot() = Snapshot(
        sortOrder = currentSortOrder.value,
        listFilter = currentListFilter.value,
    )

    fun observe(): Flow<Snapshot> = combine(currentSortOrder, currentListFilter) { order, filter ->
        Snapshot(order, filter)
    }

    fun setSortOrder(newSortOrder: SortOrder) {
        currentSortOrder.value = newSortOrder
        repository.defaultSortOrder = newSortOrder
    }

    fun set(value: MangaListFilter) {
        currentListFilter.value = value
    }

    fun setAdjusted(value: MangaListFilter) {
        var newFilter = value
        if (!newFilter.author.isNullOrEmpty() && !capabilities.isAuthorSearchSupported) {
            newFilter = newFilter.copy(
                query = newFilter.author,
                author = null,
            )
        }
        if (!newFilter.query.isNullOrEmpty() && !newFilter.hasNonSearchOptions() && !capabilities.isSearchWithFiltersSupported) {
            newFilter = MangaListFilter(query = newFilter.query)
        }
        set(newFilter)
    }

    fun saveCurrentFilter(name: String) = coroutineScope.launch {
        savedFiltersRepository.save(
            source = repository.source,
            name = name,
            filter = currentListFilter.value.copy(query = null),
            sortOrder = if (isDynamicFilter) null else currentSortOrder.value.name,
        )
    }

    fun deleteSavedFilter(id: Int) = coroutineScope.launch {
        savedFiltersRepository.delete(repository.source, id)
    }

    fun isSavedFilterNameTaken(name: String): Boolean =
        savedFilters.value.availableItems.any { it.name.equals(name, ignoreCase = true) }

    fun applySavedFilter(savedFilter: PersistableFilter) {
        val query = currentListFilter.value.query
        setAdjusted(savedFilter.filter.copy(query = query))
        val savedSortOrder = savedFilter.sortOrder ?: if (isDynamicFilter) null else initialSortOrder.name
        savedSortOrder?.let { name ->
            availableSortOrders.firstOrNull { it.name == name }?.let(::setSortOrder)
        }
    }

    fun clearSavedFilter() {
        val query = currentListFilter.value.query
        currentListFilter.value = MangaListFilter(query = query)
        if (!isDynamicFilter) {
            setSortOrder(initialSortOrder)
        }
    }

    fun setQuery(value: String?) {
        val newQuery = value?.trim()?.nullIfEmpty()
        currentListFilter.update { oldValue ->
            if (capabilities.isSearchWithFiltersSupported || newQuery == null) {
                oldValue.copy(query = newQuery)
            } else {
                MangaListFilter(query = newQuery)
            }
        }
    }

    fun setLocale(value: Locale?) {
        currentListFilter.update { oldValue ->
            oldValue.copy(
                locale = value,
                query = oldValue.takeQueryIfSupported(),
            )
        }
    }

    fun setAuthor(value: String?) {
        currentListFilter.update { oldValue ->
            oldValue.copy(
                author = value,
                query = oldValue.takeQueryIfSupported(),
            )
        }
    }

    fun setOriginalLocale(value: Locale?) {
        currentListFilter.update { oldValue ->
            oldValue.copy(
                originalLocale = value,
                query = oldValue.takeQueryIfSupported(),
            )
        }
    }

    fun setYear(value: Int) {
        currentListFilter.update { oldValue ->
            oldValue.copy(
                year = value,
                query = oldValue.takeQueryIfSupported(),
            )
        }
    }

    fun setYearRange(valueFrom: Int, valueTo: Int) {
        currentListFilter.update { oldValue ->
            oldValue.copy(
                yearFrom = valueFrom,
                yearTo = valueTo,
                query = oldValue.takeQueryIfSupported(),
            )
        }
    }

    fun toggleState(value: MangaState, isSelected: Boolean) {
        currentListFilter.update { oldValue ->
            oldValue.copy(
                states = if (isSelected) oldValue.states + value else oldValue.states - value,
                query = oldValue.takeQueryIfSupported(),
            )
        }
    }

    fun toggleContentRating(value: ContentRating, isSelected: Boolean) {
        currentListFilter.update { oldValue ->
            oldValue.copy(
                contentRating = if (isSelected) oldValue.contentRating + value else oldValue.contentRating - value,
                query = oldValue.takeQueryIfSupported(),
            )
        }
    }

    fun toggleDemographic(value: Demographic, isSelected: Boolean) {
        currentListFilter.update { oldValue ->
            oldValue.copy(
                demographics = if (isSelected) oldValue.demographics + value else oldValue.demographics - value,
                query = oldValue.takeQueryIfSupported(),
            )
        }
    }

    fun toggleContentType(value: ContentType, isSelected: Boolean) {
        currentListFilter.update { oldValue ->
            oldValue.copy(
                types = if (isSelected) oldValue.types + value else oldValue.types - value,
                query = oldValue.takeQueryIfSupported(),
            )
        }
    }

    fun toggleTag(value: MangaTag, isSelected: Boolean) {
        currentListFilter.update { oldValue ->
            val newTags = if (capabilities.isMultipleTagsSupported) {
                if (isSelected) oldValue.tags + value else oldValue.tags - value
            } else {
                if (isSelected) setOf(value) else emptySet()
            }
            oldValue.copy(
                tags = newTags,
                tagsExclude = oldValue.tagsExclude - newTags,
                query = oldValue.takeQueryIfSupported(),
            )
        }
    }

    fun toggleTagExclude(value: MangaTag, isSelected: Boolean) {
        currentListFilter.update { oldValue ->
            val newTagsExclude = if (capabilities.isMultipleTagsSupported) {
                if (isSelected) oldValue.tagsExclude + value else oldValue.tagsExclude - value
            } else {
                if (isSelected) setOf(value) else emptySet()
            }
            oldValue.copy(
                tags = oldValue.tags - newTagsExclude,
                tagsExclude = newTagsExclude,
                query = oldValue.takeQueryIfSupported(),
            )
        }
    }

    fun getAllTags(): Flow<Result<List<MangaTag>>> = filterOptions.asFlow().map {
        it.map { x -> x.availableTags.sortedWithSafe(TagTitleComparator(sourceLocale)) }
    }

    private fun MangaListFilter.takeQueryIfSupported() = when {
        capabilities.isSearchWithFiltersSupported -> query
        query.isNullOrEmpty() -> query
        hasNonSearchOptions() -> null
        else -> query
    }

    private fun getTopTags(limit: Int): Flow<Result<List<MangaTag>>> = combine(
        flow { emit(searchRepository.getTopTags(repository.source, limit)) },
        filterOptions.asFlow(),
    ) { suggested, options ->
        val all = options.getOrNull()?.availableTags.orEmpty()
        val result = ArrayList<MangaTag>(limit)
        result.addAll(suggested.take(limit))
        if (result.size < limit) {
            result.addAll(all.shuffled().take(limit - result.size))
        }
        if (result.isNotEmpty()) {
            Result.success(result)
        } else {
            options.map { result }
        }
    }.catch {
        emit(Result.failure(it))
    }

    private fun getBottomTags(limit: Int): Flow<Result<List<MangaTag>>> = combine(
        flow { emit(searchRepository.getRareTags(repository.source, limit)) },
        filterOptions.asFlow(),
    ) { suggested, options ->
        val all = options.getOrNull()?.availableTags.orEmpty()
        val result = ArrayList<MangaTag>(limit)
        result.addAll(suggested.take(limit))
        if (result.size < limit) {
            result.addAll(all.shuffled().take(limit - result.size))
        }
        if (result.isNotEmpty()) {
            Result.success(result)
        } else {
            options.map { result }
        }
    }.catch {
        emit(Result.failure(it))
    }

    private fun <T> List<T>.addFirstDistinct(other: Collection<T>): List<T> {
        val result = ArrayDeque<T>(this.size + other.size)
        result.addAll(this)
        for (item in other) {
            if (item !in result) {
                result.addFirst(item)
            }
        }
        return result
    }

    private fun <T> List<T>.addFirstDistinct(item: T): List<T> {
        val result = ArrayDeque<T>(this.size + 1)
        result.addAll(this)
        if (item !in result) {
            result.addFirst(item)
        }
        return result
    }

    data class Snapshot(
        val sortOrder: SortOrder,
        val listFilter: MangaListFilter,
    )

    /** A fresh pair of Mihon filter lists: the user-editable [working] copy and the [defaults] baseline. */
    class WorkingFilters(
        val working: FilterList,
        val defaults: FilterList,
    )

    /** Sort options surfaced by the compact sort picker. */
    data class SortState(
        /** In-app listings for a Mihon source, or the built-in orders of any other source. */
        val inAppOptions: List<SortOrder>,
        /** The selected in-app order, or null while a server-side sort is applied. */
        val inAppSelected: SortOrder?,
        /** The source's own sort control, or null when it has none. */
        val source: Source?,
    ) {

        data class Source(
            val options: List<String>,
            val selectedIndex: Int,
            val isAscending: Boolean,
            val supportsDirection: Boolean,
        )
    }

    interface Owner {

        val filterCoordinator: FilterCoordinator
    }

    companion object {

        private const val TAGS_LIMIT = 12

        /** The listings Mihon serves from dedicated endpoints instead of a search request, in display order. */
        private val IN_APP_SORT_ORDERS = listOf(SortOrder.POPULARITY, SortOrder.UPDATED)
        private val MAX_YEAR = Calendar.getInstance()[Calendar.YEAR] + 1

        fun find(fragment: Fragment): FilterCoordinator? {
            (fragment.activity as? Owner)?.let {
                return it.filterCoordinator
            }
            var f = fragment
            while (true) {
                (f as? Owner)?.let {
                    return it.filterCoordinator
                }
                f = f.parentFragment ?: break
            }
            return null
        }

        fun require(fragment: Fragment): FilterCoordinator {
            return find(fragment) ?: throw IllegalStateException("FilterCoordinator cannot be found")
        }
    }
}
