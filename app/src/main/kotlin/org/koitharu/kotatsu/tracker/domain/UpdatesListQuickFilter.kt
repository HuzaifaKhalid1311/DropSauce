package org.koitharu.kotatsu.tracker.domain

import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.MangaListQuickFilter
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import javax.inject.Inject

class UpdatesListQuickFilter @Inject constructor(
	private val favouritesRepository: FavouritesRepository,
	private val trackingRepository: TrackingRepository,
	private val mihonExtensionManager: MihonExtensionManager,
	settings: AppSettings,
) : MangaListQuickFilter(settings) {

	override suspend fun getAvailableFilterOptions(): List<ListFilterOption> = buildList {
		favouritesRepository.getMostUpdatedCategories(limit = 4).mapTo(this) {
			ListFilterOption.Favorite(it)
		}
		mihonExtensionManager.initialize()
		trackingRepository.getPopularFeedSources(limit = 4).mapTo(this) {
			ListFilterOption.Source(it)
		}
	}
}
