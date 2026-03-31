package org.haziffe.dropsauce.favourites.domain

import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import org.haziffe.dropsauce.core.db.MangaDatabase
import org.haziffe.dropsauce.core.db.entity.toManga
import org.haziffe.dropsauce.core.db.entity.toMangaTags
import org.haziffe.dropsauce.favourites.data.FavouriteManga
import org.haziffe.dropsauce.list.domain.ListFilterOption
import org.haziffe.dropsauce.list.domain.ListSortOrder
import org.haziffe.dropsauce.local.data.index.LocalMangaIndex
import org.haziffe.dropsauce.local.domain.LocalObserveMapper
import org.haziffe.dropsauce.parsers.model.Manga
import javax.inject.Inject

@Reusable
class LocalFavoritesObserver @Inject constructor(
	localMangaIndex: LocalMangaIndex,
	private val db: MangaDatabase,
) : LocalObserveMapper<FavouriteManga, Manga>(localMangaIndex) {

	fun observeAll(
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int
	): Flow<List<Manga>> = db.getFavouritesDao().observeAll(order, filterOptions, limit).mapToLocal()

	fun observeAll(
		categoryId: Long,
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int
	): Flow<List<Manga>> = db.getFavouritesDao().observeAll(categoryId, order, filterOptions, limit).mapToLocal()

	override fun toManga(e: FavouriteManga) = e.manga.toManga(e.tags.toMangaTags(), null)

	override fun toResult(e: FavouriteManga, manga: Manga) = manga
}
