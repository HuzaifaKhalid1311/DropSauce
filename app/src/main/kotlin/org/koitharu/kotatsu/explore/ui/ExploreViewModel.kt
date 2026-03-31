package org.haziffe.dropsauce.explore.ui

import androidx.collection.LongSet
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.core.model.MangaSourceInfo
import org.haziffe.dropsauce.core.os.AppShortcutManager
import org.haziffe.dropsauce.core.prefs.AppSettings
import org.haziffe.dropsauce.core.prefs.observeAsFlow
import org.haziffe.dropsauce.core.prefs.observeAsStateFlow
import org.haziffe.dropsauce.core.ui.BaseViewModel
import org.haziffe.dropsauce.core.ui.util.ReversibleAction
import org.haziffe.dropsauce.core.util.ext.MutableEventFlow
import org.haziffe.dropsauce.core.util.ext.call
import org.haziffe.dropsauce.core.util.ext.combine
import org.haziffe.dropsauce.explore.data.MangaSourcesRepository
import org.haziffe.dropsauce.explore.domain.ExploreRepository
import org.haziffe.dropsauce.explore.ui.model.ExploreButtons
import org.haziffe.dropsauce.explore.ui.model.MangaSourceItem
import org.haziffe.dropsauce.explore.ui.model.RecommendationsItem
import org.haziffe.dropsauce.list.ui.model.EmptyHint
import org.haziffe.dropsauce.list.ui.model.ListHeader
import org.haziffe.dropsauce.list.ui.model.ListModel
import org.haziffe.dropsauce.list.ui.model.LoadingState
import org.haziffe.dropsauce.list.ui.model.MangaCompactListModel
import org.haziffe.dropsauce.mihon.model.MihonMangaSource
import org.haziffe.dropsauce.parsers.model.Manga
import org.haziffe.dropsauce.parsers.model.MangaParserSource
import org.haziffe.dropsauce.parsers.model.MangaSource
import org.haziffe.dropsauce.parsers.util.runCatchingCancellable
import org.haziffe.dropsauce.suggestions.domain.SuggestionRepository
import javax.inject.Inject

enum class SourceFilterMode {
	LOCAL, EXTERNAL;

	fun next(): SourceFilterMode = entries[(ordinal + 1) % entries.size]
}

@HiltViewModel
class ExploreViewModel @Inject constructor(
	private val settings: AppSettings,
	private val suggestionRepository: SuggestionRepository,
	private val exploreRepository: ExploreRepository,
	private val sourcesRepository: MangaSourcesRepository,
	private val shortcutManager: AppShortcutManager,
) : BaseViewModel() {

	val isGrid = settings.observeAsStateFlow(
		key = AppSettings.KEY_SOURCES_GRID,
		scope = viewModelScope + Dispatchers.IO,
		valueProducer = { isSourcesGridMode },
	)

	val isAllSourcesEnabled = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.IO,
		key = AppSettings.KEY_SOURCES_ENABLED_ALL,
		valueProducer = { isAllSourcesEnabled },
	)

	private val isSuggestionsEnabled = settings.observeAsFlow(
		key = AppSettings.KEY_SUGGESTIONS,
		valueProducer = { isSuggestionsEnabled },
	)

	val onOpenManga = MutableEventFlow<Manga>()
	val onActionDone = MutableEventFlow<ReversibleAction>()
	val onShowSuggestionsTip = MutableEventFlow<Unit>()
	private val isRandomLoading = MutableStateFlow(false)
	val sourceFilterMode = MutableStateFlow(SourceFilterMode.LOCAL)

	val content: StateFlow<List<ListModel>> = isLoading.flatMapLatest { loading ->
		if (loading) {
			flowOf(getLoadingStateList())
		} else {
			createContentFlow()
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, getLoadingStateList())

	init {
		launchJob(Dispatchers.Default) {
			if (!settings.isSuggestionsEnabled && settings.isTipEnabled(TIP_SUGGESTIONS)) {
				onShowSuggestionsTip.call(Unit)
			}
		}
	}

	fun setSourceFilter(mode: SourceFilterMode) {
		sourceFilterMode.value = mode
	}

	fun openRandom() {
		if (isRandomLoading.value) {
			return
		}
		launchJob(Dispatchers.Default) {
			isRandomLoading.value = true
			try {
				val manga = exploreRepository.findRandomManga(tagsLimit = 8)
				onOpenManga.call(manga)
			} finally {
				isRandomLoading.value = false
			}
		}
	}

	fun disableSources(sources: Collection<MangaSource>) {
		launchJob(Dispatchers.Default) {
			val rollback = sourcesRepository.setSourcesEnabled(sources, isEnabled = false)
			val message = if (sources.size == 1) R.string.source_disabled else R.string.sources_disabled
			onActionDone.call(ReversibleAction(message, rollback))
		}
	}

	fun requestPinShortcut(source: MangaSource) {
		launchLoadingJob(Dispatchers.Default) {
			shortcutManager.requestPinShortcut(source)
		}
	}

	fun setSourcesPinned(sources: Collection<MangaSource>, isPinned: Boolean) {
		launchJob(Dispatchers.Default) {
			sourcesRepository.setIsPinned(sources, isPinned)
			val message = if (sources.size == 1) {
				if (isPinned) R.string.source_pinned else R.string.source_unpinned
			} else {
				if (isPinned) R.string.sources_pinned else R.string.sources_unpinned
			}
			onActionDone.call(ReversibleAction(message, null))
		}
	}

	fun respondSuggestionTip(isAccepted: Boolean) {
		settings.isSuggestionsEnabled = isAccepted
		settings.closeTip(TIP_SUGGESTIONS)
	}

	fun sourcesSnapshot(ids: LongSet): List<MangaSourceInfo> {
		return content.value.mapNotNull {
			(it as? MangaSourceItem)?.takeIf { x -> x.id in ids }?.source
		}
	}

	private fun createContentFlow() = combine(
		sourcesRepository.observeEnabledSources(),
		getSuggestionFlow(),
		isGrid,
		isRandomLoading,
		isAllSourcesEnabled,
		sourcesRepository.observeHasNewSourcesForBadge(),
		sourceFilterMode,
	) { content, suggestions, grid, randomLoading, allSourcesEnabled, newSources, filterMode ->
		buildList(content, suggestions, grid, randomLoading, allSourcesEnabled, newSources, filterMode)
	}.withErrorHandling()

	private fun buildList(
		sources: List<MangaSourceInfo>,
		recommendation: List<Manga>,
		isGrid: Boolean,
		randomLoading: Boolean,
		allSourcesEnabled: Boolean,
		hasNewSources: Boolean,
		filterMode: SourceFilterMode,
	): List<ListModel> {
		val result = ArrayList<ListModel>(sources.size + 3)
		result += ExploreButtons(randomLoading)
		if (recommendation.isNotEmpty()) {
			result += ListHeader(R.string.suggestions, R.string.more, R.id.nav_suggestions)
			result += RecommendationsItem(recommendation.toRecommendationList())
		}
		
		val filteredSources = when (filterMode) {
			SourceFilterMode.LOCAL -> sources.filter { it.mangaSource is MangaParserSource }
			SourceFilterMode.EXTERNAL -> sources.filter {
				it.mangaSource is MihonMangaSource || it.mangaSource is org.haziffe.dropsauce.core.parser.external.ExternalMangaSource
			}
		}

		val headerButtonRes = when (filterMode) {
			SourceFilterMode.LOCAL -> R.string.catalog
			SourceFilterMode.EXTERNAL -> R.string.manage
		}

		if (filteredSources.isNotEmpty()) {
			result += ListHeader(
				textRes = R.string.remote_sources,
				buttonTextRes = headerButtonRes,
				badge = if (!allSourcesEnabled && hasNewSources) "" else null,
				filterMode = filterMode,
			)
			filteredSources.mapTo(result) { MangaSourceItem(it, isGrid) }
		} else {
			result += ListHeader(
				textRes = R.string.remote_sources,
				buttonTextRes = headerButtonRes,
				badge = if (!allSourcesEnabled && hasNewSources) "" else null,
				filterMode = filterMode,
			)
			result += EmptyHint(
				icon = R.drawable.ic_empty_common,
				textPrimary = if (filterMode == SourceFilterMode.EXTERNAL) {
					R.string.no_external_source_installed
				} else {
					R.string.no_manga_source_enabled
				},
				textSecondary = if (filterMode == SourceFilterMode.EXTERNAL) {
					R.string.manage_manga_extensions_from_settings_icon
				} else {
					R.string.enable_manga_sources_from_settings_icon
				},
				actionStringRes = NO_ACTION_STRING_RES,
			)
		}
		return result
	}

	private fun getLoadingStateList() = listOf(
		ExploreButtons(isRandomLoading.value),
		LoadingState,
	)

	private fun getSuggestionFlow() = isSuggestionsEnabled.mapLatest { isEnabled ->
		if (isEnabled) {
			runCatchingCancellable {
				suggestionRepository.getRandomList(SUGGESTIONS_COUNT)
			}.getOrDefault(emptyList())
		} else {
			emptyList()
		}
	}

	private fun List<Manga>.toRecommendationList() = map { manga ->
		MangaCompactListModel(
			manga = manga,
			override = null,
			subtitle = manga.tags.joinToString { it.title },
			counter = 0,
		)
	}

	companion object {

		private const val TIP_SUGGESTIONS = "suggestions"
		private const val SUGGESTIONS_COUNT = 8
		private const val NO_ACTION_STRING_RES = 0
	}
}
