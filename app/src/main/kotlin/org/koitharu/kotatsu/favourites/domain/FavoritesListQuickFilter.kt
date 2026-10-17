package org.koitharu.kotatsu.favourites.domain

import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.MangaListQuickFilter
import org.koitharu.kotatsu.list.ui.model.ExtensionFilter
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.parsers.model.MangaSource

class FavoritesListQuickFilter @AssistedInject constructor(
	@Assisted private val categoryId: Long,
	private val settings: AppSettings,
	private val repository: FavouritesRepository,
	networkState: NetworkState,
	private val mihonExtensionManager: MihonExtensionManager,
) : MangaListQuickFilter(settings) {

	init {
		setFilterOption(ListFilterOption.Downloaded, !networkState.value)
	}

	override suspend fun getAvailableFilterOptions(): List<ListFilterOption> = buildList {
		add(ListFilterOption.Downloaded)
		if (settings.isTrackerEnabled) {
			add(ListFilterOption.Macro.NEW_CHAPTERS)
		}
		add(ListFilterOption.Macro.COMPLETED)
	}

	override suspend fun getAdditionalChips(
		selectedOptions: Set<ListFilterOption>,
	): List<ChipsView.ChipModel> {
		// Not awaited: the list would stay empty until every extension had loaded. Until then the chip
		// shows with nothing to pick yet, so it doesn't pop in late; the list is mapped again once they are.
		mihonExtensionManager.initialize()
		val isReady = mihonExtensionManager.isReady.value
		val sources = repository.findSources(categoryId)
		val options = if (isReady) {
			getSourceOptions(sources).also { pruneStaleSourceFilters(selectedOptions, it) }
		} else {
			emptyList()
		}
		if (options.isEmpty() && (isReady || sources.none { it.name.startsWith("MIHON_") })) {
			return emptyList()
		}
		val selectedSourceNames = selectedOptions
			.filterIsInstance<ListFilterOption.Source>()
			.mapTo(HashSet()) { it.mangaSource.name }
		val selectedSources = options
			.filterTo(LinkedHashSet<ListFilterOption.Source>()) { it.mangaSource.name in selectedSourceNames }
		return listOf(
			ChipsView.ChipModel(
				icon = R.drawable.ic_filter_funnel,
				isChecked = selectedSources.isNotEmpty(),
				isCheckedIconVisible = false,
				isIconOnly = true,
				data = ExtensionFilter(
					options = options,
					selectedOptions = selectedSources,
				),
			),
		)
	}

	private fun getSourceOptions(sources: List<MangaSource>): List<ListFilterOption.Source> {
		val installedSources = mihonExtensionManager.getMihonMangaSources()
		return sources.mapNotNull { source ->
			installedSources.firstOrNull { it == source }
		}.distinctBy {
			it.name
		}.map {
			ListFilterOption.Source(it)
		}
	}

	private fun pruneStaleSourceFilters(
		selectedOptions: Set<ListFilterOption>,
		availableSourceOptions: List<ListFilterOption.Source>,
	) {
		val availableSourceNames = availableSourceOptions.mapTo(HashSet()) { it.mangaSource.name }
		for (selectedOption in selectedOptions.filterIsInstance<ListFilterOption.Source>()) {
			if (selectedOption.mangaSource.name !in availableSourceNames) {
				setFilterOption(selectedOption, isApplied = false)
			}
		}
	}

	@AssistedFactory
	interface Factory {

		fun create(categoryId: Long): FavoritesListQuickFilter
	}
}
