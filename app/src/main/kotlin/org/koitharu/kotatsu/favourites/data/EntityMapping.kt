package org.haziffe.dropsauce.favourites.data

import org.haziffe.dropsauce.core.db.entity.toManga
import org.haziffe.dropsauce.core.db.entity.toMangaTags
import org.haziffe.dropsauce.core.model.FavouriteCategory
import org.haziffe.dropsauce.list.domain.ListSortOrder
import java.time.Instant

fun FavouriteCategoryEntity.toFavouriteCategory(id: Long = categoryId.toLong()) = FavouriteCategory(
	id = id,
	title = title,
	sortKey = sortKey,
	order = ListSortOrder(order, ListSortOrder.NEWEST),
	createdAt = Instant.ofEpochMilli(createdAt),
	isTrackingEnabled = track,
	isVisibleInLibrary = isVisibleInLibrary,
)

fun FavouriteManga.toManga() = manga.toManga(tags.toMangaTags(), null)

fun Collection<FavouriteManga>.toMangaList() = map { it.toManga() }
