package org.koitharu.kotatsu.explore.ui

import android.content.Context
import androidx.collection.LongSet
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.prefs.observeAsStateFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.explore.domain.ExploreRepository
import org.koitharu.kotatsu.explore.ui.model.ExploreButtons
import org.koitharu.kotatsu.explore.ui.model.MangaSourceItem
import org.koitharu.kotatsu.explore.ui.model.RecommendationsItem
import org.koitharu.kotatsu.list.ui.model.EmptyHint
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.MangaCompactListModel
import org.koitharu.kotatsu.list.ui.model.TipModel
import org.koitharu.kotatsu.mihon.MihonExtensionLoader
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionInstallMode
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreManager
import org.koitharu.kotatsu.settings.sources.catalog.StoreHealth
import org.koitharu.kotatsu.settings.sources.catalog.isNewerThan
import org.koitharu.kotatsu.suggestions.domain.SuggestionRepository
import javax.inject.Inject

@HiltViewModel
class ExploreViewModel @Inject constructor(
	@ApplicationContext private val appContext: Context,
	private val settings: AppSettings,
	private val suggestionRepository: SuggestionRepository,
	private val exploreRepository: ExploreRepository,
	private val sourcesRepository: MangaSourcesRepository,
	private val shortcutManager: AppShortcutManager,
	private val mihonExtensionLoader: MihonExtensionLoader,
	private val extensionStoreManager: ExtensionStoreManager,
) : BaseViewModel() {

	val isGrid = settings.observeAsStateFlow(
		key = AppSettings.KEY_SOURCES_GRID,
		scope = viewModelScope + Dispatchers.IO,
		valueProducer = { isSourcesGridMode },
	)

	private val isSuggestionsEnabled = settings.observeAsFlow(
		key = AppSettings.KEY_SUGGESTIONS,
		valueProducer = { isSuggestionsEnabled },
	)

	val onOpenManga = MutableEventFlow<Manga>()
	val onActionDone = MutableEventFlow<ReversibleAction>()
	val onShowSuggestionsTip = MutableEventFlow<Unit>()
	private val mutableRandomLoading = MutableStateFlow(false)
	val isRandomLoading = mutableRandomLoading.asStateFlow()

	private val hasExtensionUpdates: StateFlow<Boolean> = combine(
		extensionStoreManager.states,
		settings.observeAsFlow(AppSettings.KEY_PRIVATE_INSTALLER) { isPrivateInstallEnabled },
	) { stores, privateMode ->
		val mode = if (privateMode) ExtensionInstallMode.SANDBOX else ExtensionInstallMode.SYSTEM
		mihonExtensionLoader.getInstalledExtensions(appContext, privateMode).any { local ->
			val owner = extensionStoreManager.owner(mode, local) ?: return@any false
			val state = stores.firstOrNull { it.store.id == owner.id } ?: return@any false
			owner.enabled &&
				state.health == StoreHealth.AVAILABLE &&
				state.catalog.any { it.packageName == local.pkgName && it.isNewerThan(local) }
		}
	}.stateIn(viewModelScope + Dispatchers.IO, SharingStarted.Eagerly, false)

	val content: StateFlow<List<ListModel>> = isLoading.flatMapLatest { loading ->
		if (loading) {
			flowOf(getLoadingStateList())
		} else {
			createContentFlow()
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, getLoadingStateList())

	init {
		launchJob(Dispatchers.IO) {
			extensionStoreManager.initialize()
		}
		launchJob(Dispatchers.Default) {
			// Ensure extensions are loaded so the source list is populated.
			// This is a no-op if extensions are already loading or ready.
			sourcesRepository.reloadMihonSources()
		}
		launchJob(Dispatchers.Default) {
			if (!settings.isSuggestionsEnabled && settings.isTipEnabled(TIP_SUGGESTIONS)) {
				onShowSuggestionsTip.call(Unit)
			}
		}
	}

	fun openRandom() {
		if (mutableRandomLoading.value) {
			return
		}
		launchJob(Dispatchers.Default) {
			mutableRandomLoading.value = true
			try {
				val manga = exploreRepository.findRandomManga(tagsLimit = 8)
				onOpenManga.call(manga)
			} finally {
				mutableRandomLoading.value = false
			}
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
		return content.value.mapNotNull {
			(it as? MangaSourceItem)?.takeIf { x -> x.id in ids }?.source
		}
	}

	@Suppress("UNCHECKED_CAST")
	private fun createContentFlow() = kotlinx.coroutines.flow.combine(
		sourcesRepository.observeEnabledSources(),
		sourcesRepository.observeMihonLoadingState(),
		getSuggestionFlow(),
		isGrid,
		sourcesRepository.observeHasMultiLanguageSources(),
		settings.observeAsFlow(AppSettings.KEY_TIPS_CLOSED) { isTipEnabled(TIP_LANGUAGES) },
		hasExtensionUpdates,
	) { args ->
		buildList(
			sources = args[0] as List<MangaSourceInfo>,
			isExtensionsLoading = args[1] as Boolean,
			recommendation = args[2] as List<Manga>,
			isGrid = args[3] as Boolean,
			hasMultiLanguageSources = args[4] as Boolean,
			isLanguageTipEnabled = args[5] as Boolean,
			hasExtensionUpdates = args[6] as Boolean,
		)
	}.withErrorHandling()

	private fun buildList(
		sources: List<MangaSourceInfo>,
		isExtensionsLoading: Boolean,
		recommendation: List<Manga>,
		isGrid: Boolean,
		hasMultiLanguageSources: Boolean,
		isLanguageTipEnabled: Boolean,
		hasExtensionUpdates: Boolean,
	): List<ListModel> {
		val result = ArrayList<ListModel>(sources.size + 4)
		result += ExploreButtons
		if (recommendation.isNotEmpty()) {
			result += ListHeader(R.string.suggestions, R.string.more, R.id.nav_suggestions)
			result += RecommendationsItem(recommendation.toRecommendationList())
		}

		result += ListHeader(
			textRes = R.string.remote_sources,
			buttonTextRes = R.string.manage,
			buttonStyle = ListHeader.ButtonStyle.TEXT,
			badge = if (hasExtensionUpdates) "" else null,
		)
		when {
			sources.isNotEmpty() -> sources.mapTo(result) { MangaSourceItem(it, isGrid) }
			isExtensionsLoading -> result += LoadingState  // still loading — don't show "not installed"
			else -> result += EmptyHint(
				icon = R.drawable.ic_empty_common,
				textPrimary = R.string.no_external_source_installed,
				textSecondary = R.string.manage_manga_extensions_from_settings_icon,
				actionStringRes = NO_ACTION_STRING_RES,
			)
		}
		// Footer note: only relevant when a multi-language source is installed and not dismissed.
		if (sources.isNotEmpty() && hasMultiLanguageSources && isLanguageTipEnabled) {
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

	private fun getLoadingStateList() = listOf(
		ExploreButtons,
		LoadingState,
	)

	private fun getSuggestionFlow() = isSuggestionsEnabled.flatMapLatest { isEnabled ->
		if (isEnabled) {
			// Observe the suggestions reactively so the carousel refreshes in place when suggestions
			// are regenerated (e.g. from the Suggestions screen) instead of staying stale until restart.
			suggestionRepository.observeRandomList(SUGGESTIONS_COUNT)
				.catch { emit(emptyList()) }
		} else {
			flowOf(emptyList())
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
		private const val TIP_LANGUAGES = "languages_note"
		private const val SUGGESTIONS_COUNT = 8
		private const val NO_ACTION_STRING_RES = 0
	}
}
