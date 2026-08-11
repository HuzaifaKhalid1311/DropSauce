package org.koitharu.kotatsu.favourites.data

import androidx.room.ColumnInfo

/**
 * Lightweight projection of a favourite manga's titles, used by duplicate detection so that
 * matching does not need to load full [FavouriteManga] rows for the whole library.
 */
class FavouriteTitles(
	@ColumnInfo(name = "manga_id") val mangaId: Long,
	@ColumnInfo(name = "title") val title: String,
	@ColumnInfo(name = "alt_title") val altTitles: String?,
)
