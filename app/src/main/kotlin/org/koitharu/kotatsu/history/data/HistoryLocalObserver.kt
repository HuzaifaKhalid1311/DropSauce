package org.haziffe.dropsauce.history.data

import dagger.Reusable
import org.haziffe.dropsauce.core.db.MangaDatabase
import org.haziffe.dropsauce.core.db.entity.toManga
import org.haziffe.dropsauce.core.db.entity.toMangaTags
import org.haziffe.dropsauce.history.domain.model.MangaWithHistory
import org.haziffe.dropsauce.list.domain.ListFilterOption
import org.haziffe.dropsauce.list.domain.ListSortOrder
import org.haziffe.dropsauce.local.data.index.LocalMangaIndex
import org.haziffe.dropsauce.local.domain.LocalObserveMapper
import org.haziffe.dropsauce.parsers.model.Manga
import javax.inject.Inject

@Reusable
class HistoryLocalObserver @Inject constructor(
	localMangaIndex: LocalMangaIndex,
	private val db: MangaDatabase,
) : LocalObserveMapper<HistoryWithManga, MangaWithHistory>(localMangaIndex) {

	fun observeAll(
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int
	) = db.getHistoryDao().observeAll(order, filterOptions, limit).mapToLocal()

	override fun toManga(e: HistoryWithManga) = e.manga.toManga(e.tags.toMangaTags(), null)

	override fun toResult(e: HistoryWithManga, manga: Manga) = MangaWithHistory(
		manga = manga,
		history = e.history.toMangaHistory(),
	)
}
