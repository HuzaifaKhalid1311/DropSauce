package org.haziffe.dropsauce.alternatives.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.alternatives.domain.AlternativesUseCase
import org.haziffe.dropsauce.alternatives.domain.MigrateUseCase
import org.haziffe.dropsauce.core.model.chaptersCount
import org.haziffe.dropsauce.core.model.parcelable.ParcelableManga
import org.haziffe.dropsauce.core.nav.AppRouter
import org.haziffe.dropsauce.core.parser.MangaRepository
import org.haziffe.dropsauce.core.prefs.ListMode
import org.haziffe.dropsauce.core.ui.BaseViewModel
import org.haziffe.dropsauce.core.util.ext.MutableEventFlow
import org.haziffe.dropsauce.core.util.ext.append
import org.haziffe.dropsauce.core.util.ext.call
import org.haziffe.dropsauce.core.util.ext.require
import org.haziffe.dropsauce.list.domain.MangaListMapper
import org.haziffe.dropsauce.list.ui.model.ButtonFooter
import org.haziffe.dropsauce.list.ui.model.EmptyState
import org.haziffe.dropsauce.list.ui.model.ListModel
import org.haziffe.dropsauce.list.ui.model.LoadingFooter
import org.haziffe.dropsauce.list.ui.model.LoadingState
import org.haziffe.dropsauce.list.ui.model.MangaGridModel
import org.haziffe.dropsauce.parsers.model.Manga
import org.haziffe.dropsauce.parsers.util.suspendlazy.getOrDefault
import org.haziffe.dropsauce.parsers.util.suspendlazy.suspendLazy
import javax.inject.Inject

@HiltViewModel
class AlternativesViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val alternativesUseCase: AlternativesUseCase,
	private val migrateUseCase: MigrateUseCase,
	private val mangaListMapper: MangaListMapper,
) : BaseViewModel() {

	val manga = savedStateHandle.require<ParcelableManga>(AppRouter.KEY_MANGA).manga

	private var includeDisabledSources = MutableStateFlow(false)
	private val results = MutableStateFlow<List<MangaAlternativeModel>>(emptyList())

	private var migrationJob: Job? = null
	private var searchJob: Job? = null

	private val mangaDetails = suspendLazy {
		mangaRepositoryFactory.create(manga.source).getDetails(manga)
	}

	val onMigrated = MutableEventFlow<Manga>()

	val list: StateFlow<List<ListModel>> = combine(
		results,
		isLoading,
		includeDisabledSources,
	) { list, loading, includeDisabled ->
		when {
			list.isEmpty() -> listOf(
				when {
					loading -> LoadingState
					else -> EmptyState(
						icon = R.drawable.ic_empty_common,
						textPrimary = R.string.nothing_found,
						textSecondary = R.string.text_search_holder_secondary,
						actionStringRes = 0,
					)
				},
			)

			loading -> list + LoadingFooter()
			includeDisabled -> list
			else -> list + ButtonFooter(R.string.search_disabled_sources)
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	init {
		doSearch(throughDisabledSources = false)
	}

	fun retry() {
		searchJob?.cancel()
		results.value = emptyList()
		includeDisabledSources.value = false
		doSearch(throughDisabledSources = false)
	}

	fun continueSearch() {
		if (includeDisabledSources.value) {
			return
		}
		val prevJob = searchJob
		searchJob = launchLoadingJob(Dispatchers.Default) {
			includeDisabledSources.value = true
			prevJob?.join()
			doSearch(throughDisabledSources = true)
		}
	}

	fun migrate(target: Manga) {
		if (migrationJob?.isActive == true) {
			return
		}
		migrationJob = launchLoadingJob(Dispatchers.Default) {
			migrateUseCase(manga, target)
			onMigrated.call(target)
		}
	}

	private fun doSearch(throughDisabledSources: Boolean) {
		val prevJob = searchJob
		searchJob = launchLoadingJob(Dispatchers.Default) {
			prevJob?.cancelAndJoin()
			val ref = mangaDetails.getOrDefault(manga)
			val refCount = ref.chaptersCount()
			alternativesUseCase.invoke(ref, throughDisabledSources)
				.collect {
					val model = MangaAlternativeModel(
						mangaModel = mangaListMapper.toListModel(it, ListMode.GRID) as MangaGridModel,
						referenceChapters = refCount,
					)
					results.append(model)
				}
		}
	}
}
