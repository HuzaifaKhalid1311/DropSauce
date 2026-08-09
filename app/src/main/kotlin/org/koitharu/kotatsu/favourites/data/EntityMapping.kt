package org.koitharu.kotatsu.favourites.data

import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.db.entity.toMangaTags
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.list.domain.ListSortOrder
import java.time.Instant

import org.koitharu.kotatsu.core.db.entity.ChapterEntity

fun FavouriteCategoryEntity.toFavouriteCategory(id: Long = categoryId.toLong()) = FavouriteCategory(
	id = id,
	title = title,
	sortKey = sortKey,
	order = ListSortOrder(order, ListSortOrder.NEWEST),
	createdAt = Instant.ofEpochMilli(createdAt),
	isTrackingEnabled = track,
	isNewChaptersDownloadEnabled = downloadNewChapters,
	isVisibleInLibrary = isVisibleInLibrary,
)

fun FavouriteManga.toManga(chapters: List<ChapterEntity>? = null) = manga.toManga(tags.toMangaTags(), chapters)

fun Collection<FavouriteManga>.toMangaList() = map { it.toManga() }

