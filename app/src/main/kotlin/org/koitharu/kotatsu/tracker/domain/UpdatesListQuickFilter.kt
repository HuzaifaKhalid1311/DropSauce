package org.haziffe.dropsauce.tracker.domain

import org.haziffe.dropsauce.core.prefs.AppSettings
import org.haziffe.dropsauce.favourites.domain.FavouritesRepository
import org.haziffe.dropsauce.list.domain.ListFilterOption
import org.haziffe.dropsauce.list.domain.MangaListQuickFilter
import javax.inject.Inject

class UpdatesListQuickFilter @Inject constructor(
	private val favouritesRepository: FavouritesRepository,
	settings: AppSettings,
) : MangaListQuickFilter(settings) {

	override suspend fun getAvailableFilterOptions(): List<ListFilterOption> =
		favouritesRepository.getMostUpdatedCategories(
			limit = 4,
		).map {
			ListFilterOption.Favorite(it)
		}
}
