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
		val options = getSourceOptions()
		pruneStaleSourceFilters(selectedOptions, options)
		if (options.isEmpty()) {
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

	private suspend fun getSourceOptions(): List<ListFilterOption.Source> {
		// Installs are picked up by the package observer; forcing a reload here re-scanned every
		// package each time a favourites category page was created.
		mihonExtensionManager.ensureReady()
		val installedSources = mihonExtensionManager.getMihonMangaSources()
		return repository.findSources(categoryId).mapNotNull { source ->
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
