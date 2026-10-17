package org.koitharu.kotatsu.history.domain

import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.MangaListQuickFilter
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import javax.inject.Inject

class HistoryListQuickFilter @Inject constructor(
	private val settings: AppSettings,
	private val repository: HistoryRepository,
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
		add(ListFilterOption.Macro.FAVORITE)
		add(ListFilterOption.NOT_FAVORITE)
		if (!settings.isNsfwContentDisabled) {
			add(ListFilterOption.Macro.NSFW)
		}
		repository.getPopularTags(3).mapTo(this) {
			ListFilterOption.Tag(it)
		}
		// Not awaited: the list would stay empty until every extension had loaded. A chip's name is
		// read on each mapping, and the list is mapped again once they are loaded.
		mihonExtensionManager.initialize()
		repository.getPopularSources(3).mapTo(this) {
			ListFilterOption.Source(it)
		}
	}
}
