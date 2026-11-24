package org.koitharu.kotatsu.explore.ui

import androidx.collection.LongSet
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.prefs.observeAsStateFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.explore.data.ExtensionLayout
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.explore.data.SourceLanguage
import org.koitharu.kotatsu.explore.ui.model.MangaSourceItem
import org.koitharu.kotatsu.explore.ui.model.ExploreSources
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.MangaCompactListModel
import org.koitharu.kotatsu.list.ui.model.TipModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreManager
import org.koitharu.kotatsu.suggestions.domain.SuggestionRepository
import org.koitharu.kotatsu.suggestions.ui.SuggestionsWorker
import javax.inject.Inject
import org.koitharu.kotatsu.mihon.model.MihonMangaSource

@HiltViewModel
class ExploreViewModel @Inject constructor(
	private val settings: AppSettings,
	private val suggestionRepository: SuggestionRepository,
	private val sourcesRepository: MangaSourcesRepository,
	private val shortcutManager: AppShortcutManager,
	private val extensionStoreManager: ExtensionStoreManager,
	private val suggestionsScheduler: SuggestionsWorker.Scheduler,
) : BaseViewModel() {

	val layout = settings.observeAsStateFlow(
		key = AppSettings.KEY_SOURCES_LAYOUT,
		scope = viewModelScope + Dispatchers.IO,
		valueProducer = { sourcesLayout },
	)

	val gridColumns = settings.observeAsStateFlow(
		key = AppSettings.KEY_SOURCES_GRID_COLUMNS,
		scope = viewModelScope + Dispatchers.IO,
		valueProducer = { sourcesGridColumns },
	)

	val novelTabFirst = settings.observeAsStateFlow(
		key = AppSettings.KEY_EXPLORE_NOVEL_TAB_FIRST,
		scope = viewModelScope + Dispatchers.IO,
		valueProducer = { isNovelTabFirst },
	)

	val singleKind = settings.observeAsStateFlow(
		key = AppSettings.KEY_EXPLORE_SINGLE_KIND,
		scope = viewModelScope + Dispatchers.IO,
		valueProducer = { isExploreSingleKind },
	)

	private val isSuggestionsEnabled = settings.observeAsFlow(
		key = AppSettings.KEY_SUGGESTIONS,
		valueProducer = { isSuggestionsEnabled },
	)

	val onActionDone = MutableEventFlow<ReversibleAction>()
	val onShowSuggestionsTip = MutableEventFlow<Unit>()

	val hasExtensionUpdates: StateFlow<Boolean> = extensionStoreManager.hasUpdates
		.stateIn(viewModelScope + Dispatchers.IO, SharingStarted.Eagerly, false)

	/** True while a pull-to-refresh is regenerating the suggestions. */
	private val isRefreshingSuggestions = MutableStateFlow(false)

	/**
	 * The suggestions carousel: `null` hides it, an empty list shows the skeleton (still loading, so
	 * the extensions don't jump down once it arrives), anything else is the carousel.
	 */
	val suggestions: StateFlow<List<MangaCompactListModel>?> = combine(
		getSuggestionFlow(),
		isRefreshingSuggestions,
	) { recommendation, isRefreshing ->
		// Drop the stale carousel while refreshing: the skeleton shows the pull visibly does something
		when {
			isRefreshing || recommendation == null -> emptyList()
			recommendation.isEmpty() -> null
			else -> recommendation.toRecommendationList()
		}
	}.withErrorHandling()
		.stateIn(
			viewModelScope + Dispatchers.Default,
			SharingStarted.Eagerly,
			if (settings.isSuggestionsEnabled) emptyList() else null,
		)

	/** Which of the two Explore tabs sits on the left (and is therefore the one that opens first). */
	var isNovelTabFirst: Boolean
		get() = settings.isNovelTabFirst
		set(value) {
			settings.isNovelTabFirst = value
		}

	var isSingleKind: Boolean
		get() = settings.isExploreSingleKind
		set(value) {
			settings.isExploreSingleKind = value
		}

	val sources: StateFlow<ExploreSources> = createSourcesFlow()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, loadingSources)

	init {
		launchJob(Dispatchers.IO) {
			extensionStoreManager.initialize()
		}
		launchJob(Dispatchers.Default) {
			// Ensure extensions are loaded so the source list is populated.
			// This is a no-op if extensions are already loading or ready.
			sourcesRepository.ensureMihonSourcesLoaded()
		}
		launchJob(Dispatchers.Default) {
			if (!settings.isSuggestionsEnabled && settings.isTipEnabled(TIP_SUGGESTIONS)) {
				onShowSuggestionsTip.call(Unit)
			}
		}
	}

	/** Cycles Explore's extension layout between Chips -> Grid -> List right from the header. */
	fun cycleLayout() {
		val entries = ExtensionLayout.entries
		settings.sourcesLayout = entries[(entries.indexOf(settings.sourcesLayout) + 1) % entries.size]
	}

	/** Pull-to-refresh: reload the installed extensions and regenerate the suggestions carousel. */
	fun refresh() {
		launchLoadingJob(Dispatchers.Default) {
			isRefreshingSuggestions.value = settings.isSuggestionsEnabled
			try {
				sourcesRepository.reloadMihonSources()
				if (settings.isSuggestionsEnabled) {
					suggestionsScheduler.runNow()
				}
			} finally {
				isRefreshingSuggestions.value = false
			}
		}
	}

	/** Languages offered by the installed sources, for the Explore language filter. */
	fun sourceLanguages(): List<SourceLanguage> = sourcesRepository.getSourceLanguages()

	fun setHiddenLanguages(codes: Set<String>) {
		sourcesRepository.setHiddenLanguages(codes)
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

	fun hideSources(sources: Collection<MangaSource>) {
		launchJob(Dispatchers.Default) {
			val handle = sourcesRepository.setSourcesHidden(sources, hidden = true)
			val message = if (sources.size == 1) R.string.extension_hidden else R.string.extensions_hidden
			onActionDone.call(ReversibleAction(message, handle))
		}
	}

	fun respondSuggestionTip(isAccepted: Boolean) {
		settings.isSuggestionsEnabled = isAccepted
		settings.closeTip(TIP_SUGGESTIONS)
	}

	/** Permanently dismisses the multi-language note at the bottom of Explore. */
	fun dismissLanguageTip() {
		settings.closeTip(TIP_LANGUAGES)
	}

	fun sourcesSnapshot(ids: LongSet): List<MangaSourceInfo> {
		val content = sources.value
		return (content.manga + content.novel).mapNotNull {
			(it as? MangaSourceItem)?.takeIf { x -> x.id in ids }?.source
		}
	}

	@Suppress("UNCHECKED_CAST")
	private fun createSourcesFlow() = kotlinx.coroutines.flow.combine(
		sourcesRepository.observeEnabledSources(),
		sourcesRepository.observeMihonLoadingState(),
		sourcesRepository.observeHasMultiLanguageSources(),
		settings.observeAsFlow(AppSettings.KEY_TIPS_CLOSED) { isTipEnabled(TIP_LANGUAGES) },
		extensionStoreManager.packagesWithUpdates,
	) { args ->
		val allSources = args[0] as List<MangaSourceInfo>
		val isExtensionsLoading = args[1] as Boolean
		val hasMultiLanguageSources = args[2] as Boolean
		val isLanguageTipEnabled = args[3] as Boolean
		val packagesWithUpdates = args[4] as Set<String>
		ExploreSources(
			manga = buildSourcesPage(
				allSources, isExtensionsLoading, hasMultiLanguageSources, isLanguageTipEnabled, packagesWithUpdates, false,
			),
			novel = buildSourcesPage(
				allSources, isExtensionsLoading, hasMultiLanguageSources, isLanguageTipEnabled, packagesWithUpdates, true,
			),
		)
	}.withErrorHandling()

	private fun buildSourcesPage(
		sources: List<MangaSourceInfo>,
		isExtensionsLoading: Boolean,
		hasMultiLanguageSources: Boolean,
		isLanguageTipEnabled: Boolean,
		packagesWithUpdates: Set<String>,
		isNovelShown: Boolean,
	): List<ListModel> {
		val result = ArrayList<ListModel>(sources.size + 2)
		val shown = sources.filter { it.isNovelSource == isNovelShown }
		when {
			shown.isNotEmpty() -> shown.mapTo(result) {
				val pkgName = (it.mangaSource as? MihonMangaSource)?.pkgName
				MangaSourceItem(it, hasUpdate = pkgName != null && pkgName in packagesWithUpdates)
			}
			// Novels can also come from extension APKs now, so the spinner belongs on both tabs —
			// otherwise the novels tab claims nothing is installed while the scan is still running.
			isExtensionsLoading -> result += LoadingState
			else -> result += EmptyState(
				icon = R.drawable.ic_empty_common,
				textPrimary = if (isNovelShown) {
					R.string.no_novel_extension_installed
				} else {
					R.string.no_external_source_installed
				},
				textSecondary = R.string.manage_manga_extensions_from_settings_icon,
				actionStringRes = NO_ACTION_STRING_RES,
			)
		}
		// Footer note: only relevant when a multi-language source is installed and not dismissed.
		if (shown.isNotEmpty() && hasMultiLanguageSources && isLanguageTipEnabled) {
			result += TipModel(
				key = TIP_LANGUAGES,
				title = R.string.multi_language_sources,
				text = R.string.explore_language_note,
				icon = R.drawable.ic_language,
				primaryButtonText = NO_ACTION_STRING_RES,
				secondaryButtonText = NO_ACTION_STRING_RES,
				isClosable = true,
			)
		}
		return result
	}

	private fun getSuggestionFlow(): Flow<List<Manga>?> = isSuggestionsEnabled.flatMapLatest { isEnabled ->
		if (isEnabled) {
			// Observe the suggestions reactively so the carousel refreshes in place when suggestions
			// are regenerated (e.g. from the Suggestions screen) instead of staying stale until restart.
			suggestionRepository.observeRandomList(SUGGESTIONS_COUNT)
				.catch { emit(emptyList()) }
				.onStart<List<Manga>?> { emit(null) }
		} else {
			flowOf(emptyList())
		}
	}

	private fun List<Manga>.toRecommendationList() = map { manga ->
		MangaCompactListModel(
			manga = manga,
			override = null,
			subtitle = manga.authors.joinToString(", "),
			counter = 0,
		)
	}

	companion object {

		private const val TIP_SUGGESTIONS = "suggestions"
		private const val TIP_LANGUAGES = "languages_note"
		const val SUGGESTIONS_COUNT = 12
		private const val NO_ACTION_STRING_RES = 0

		private val loadingSources = ExploreSources(listOf(LoadingState), listOf(LoadingState))
	}
}
