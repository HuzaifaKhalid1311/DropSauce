package org.koitharu.kotatsu.widget.common

import coil3.ImageLoader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
	val historyRepository: HistoryRepository
	val imageLoader: ImageLoader
	val database: MangaDatabase
	val favouritesRepository: FavouritesRepository
	val settings: AppSettings
}
