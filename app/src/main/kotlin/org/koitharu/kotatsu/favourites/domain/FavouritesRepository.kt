package org.koitharu.kotatsu.favourites.domain

import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITES
import org.koitharu.kotatsu.core.db.TABLE_FAVOURITE_CATEGORIES
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.entity.toManga as toMangaWithTags
import org.koitharu.kotatsu.core.db.entity.toMangaList


import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.toMangaSources
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.util.ext.mapItems
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.favourites.data.toFavouriteCategory
import org.koitharu.kotatsu.favourites.data.toManga
import org.koitharu.kotatsu.favourites.data.toMangaList

import org.koitharu.kotatsu.favourites.domain.model.Cover
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.levenshteinDistance
import org.koitharu.kotatsu.search.domain.SearchKind
import javax.inject.Inject

import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.model.MihonMangaSource

@Reusable
class FavouritesRepository @Inject constructor(
	private val db: MangaDatabase,
	private val localObserver: LocalFavoritesObserver,
	private val mihonExtensionManager: MihonExtensionManager,
) {


	suspend fun getAllManga(): List<Manga> {
		val entities = db.getFavouritesDao().findAll()
		return entities.toMangaList()
	}

	suspend fun getLastManga(limit: Int): List<Manga> {
		val entities = db.getFavouritesDao().findLast(limit)
		return entities.toMangaList()
	}

	suspend fun search(query: String, kind: SearchKind, limit: Int): List<Manga> {
		val dao = db.getFavouritesDao()
		val q = "%$query%"
		val entities = when (kind) {
			SearchKind.SIMPLE,
			SearchKind.TITLE -> dao.searchByTitle(q, limit).sortedBy { it.manga.title.levenshteinDistance(query) }

			SearchKind.AUTHOR -> dao.searchByAuthor(q, limit)
			SearchKind.TAG -> dao.searchByTag(q, limit)
		}
		return entities.toMangaList()
	}

	fun observeAll(
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int,
		pinned: List<Long> = emptyList(),
	): Flow<List<Manga>> {
		if (ListFilterOption.Downloaded in filterOptions) {
			// ponytail: pins not applied to the downloaded-only local list
			return localObserver.observeAll(order, filterOptions, limit)
		}
		return db.getFavouritesDao().observeAll(order, filterOptions, limit, pinned)
			.map { it.toMangaList() }
	}

	suspend fun getManga(categoryId: Long): List<Manga> {
		val entities = db.getFavouritesDao().findAll(categoryId)
		return entities.toMangaList()
	}

	fun observeAll(
		categoryId: Long,
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int,
		pinned: List<Long> = emptyList(),
	): Flow<List<Manga>> {
		if (ListFilterOption.Downloaded in filterOptions) {
			return localObserver.observeAll(categoryId, order, filterOptions, limit)
		}
		return db.getFavouritesDao().observeAll(categoryId, order, filterOptions, limit, pinned)
			.map { it.toMangaList() }
	}

	fun observeAll(
		categoryId: Long,
		filterOptions: Set<ListFilterOption>,
		limit: Int,
		pinned: List<Long> = emptyList(),
	): Flow<List<Manga>> {
		return observeOrder(categoryId)
			.flatMapLatest { order -> observeAll(categoryId, order, filterOptions, limit, pinned) }
	}

	fun observeMangaCount(): Flow<Int> {
		return db.getFavouritesDao().observeMangaCount()
			.distinctUntilChanged()
	}

	fun observeCategories(): Flow<List<FavouriteCategory>> {
		return db.getFavouriteCategoriesDao().observeAll().mapItems {
			it.toFavouriteCategory()
		}.distinctUntilChanged()
	}

	fun observeCategoriesForLibrary(): Flow<List<FavouriteCategory>> {
		return db.getFavouriteCategoriesDao().observeAllVisible().mapItems {
			it.toFavouriteCategory()
		}.distinctUntilChanged()
	}

	fun observeCategoriesWithCovers(): Flow<Map<FavouriteCategory, List<Cover>>> {
		return db.invalidationTracker.createFlow(
			TABLE_FAVOURITES,
			TABLE_FAVOURITE_CATEGORIES,
			emitInitialState = true,
		).mapLatest {
			db.withTransaction {
				val categories = db.getFavouriteCategoriesDao().findAll()
				val res = LinkedHashMap<FavouriteCategory, List<Cover>>(categories.size)
				for (entity in categories) {
					val cat = entity.toFavouriteCategory()
					res[cat] = db.getFavouritesDao().findCovers(
						categoryId = cat.id,
						order = cat.order,
					)
				}
				res
			}
		}.distinctUntilChanged()
	}

	suspend fun getAllFavoritesCovers(order: ListSortOrder, limit: Int): List<Cover> {
		return db.getFavouritesDao().findCovers(order, limit)
	}

	fun observeCategory(id: Long): Flow<FavouriteCategory?> {
		return db.getFavouriteCategoriesDao().observe(id)
			.map { it?.toFavouriteCategory() }
	}

	fun observeCategories(mangaId: Long): Flow<Set<FavouriteCategory>> {
		return db.getFavouritesDao().observeCategories(mangaId).map {
			it.mapTo(LinkedHashSet(it.size)) { x -> x.toFavouriteCategory() }
		}
	}

	suspend fun getCategory(id: Long): FavouriteCategory {
		return db.getFavouriteCategoriesDao().find(id.toInt()).toFavouriteCategory()
	}

	suspend fun isFavorite(mangaId: Long): Boolean {
		return db.getFavouritesDao().findCategoriesCount(mangaId) != 0
	}

	suspend fun getCategoriesIds(mangaId: Long): Set<Long> {
		return db.getFavouritesDao().findCategoriesIds(mangaId).toSet()
	}

	suspend fun getDuplicates(manga: Manga): List<Manga> {
		mihonExtensionManager.ensureReady()
		val targetTitle = manga.title.lowercase().trim()
		if (targetTitle.isBlank()) return emptyList()

		val scrobbleDuplicates = db.getFavouritesDao().findDuplicatesByScrobbling(manga.id)
		val titleDuplicates = db.getFavouritesDao().findDuplicatesByTitle(manga.title, manga.id)

		val favouriteEntities = (scrobbleDuplicates + titleDuplicates).distinctBy { it.manga.id }
		if (favouriteEntities.isEmpty()) {
			return emptyList()
		}

		return favouriteEntities.map { favouriteManga ->
			val chapters = db.getChaptersDao().findAll(favouriteManga.manga.id)
			val history = db.getHistoryDao().find(favouriteManga.manga.id)
			val prefs = db.getPreferencesDao().find(favouriteManga.manga.id)
			val fullMangaWithTags = db.getMangaDao().find(favouriteManga.manga.id)
			var mangaObj: Manga = (fullMangaWithTags?.toMangaWithTags(chapters.ifEmpty { null })
				?: favouriteManga.toManga(chapters.ifEmpty { null }))

			val coverOverride = prefs?.coverUrlOverride
			if (!coverOverride.isNullOrEmpty()) {
				mangaObj = mangaObj.copy(coverUrl = coverOverride)
			}
			val sourceName = mangaObj.source.name
			if (mangaObj.source !is MihonMangaSource && sourceName.startsWith("MIHON_")) {
				val sourceId = sourceName.removePrefix("MIHON_").substringBefore(':').toLongOrNull()
				val resolved = sourceId?.let { mihonExtensionManager.getMihonMangaSourceById(it) }
					?: mihonExtensionManager.getMihonMangaSourceByName(sourceName)
				if (resolved != null) {
					mangaObj = mangaObj.copy(source = resolved)
				}
			}
			if (mangaObj.chapters.isNullOrEmpty() && history != null && history.chaptersCount > 0) {
				val dummyChapters = List(history.chaptersCount) { index ->
					org.koitharu.kotatsu.parsers.model.MangaChapter(
						id = (index + 1).toLong(),
						title = null,
						number = (index + 1).toFloat(),
						volume = 0,
						url = "",
						scanlator = null,
						uploadDate = 0L,
						branch = null,
						source = mangaObj.source,
					)
				}
				mangaObj.copy(chapters = dummyChapters)
			} else {
				mangaObj
			}
		}
	}

	private fun String?.normalizeTitle(): String? {
		if (this.isNullOrBlank()) return null
		var s = this.lowercase().trim()
		for (prefix in TITLE_PREFIXES) {
			if (s.startsWith(prefix)) {
				s = s.substring(prefix.length).trim()
				break
			}
		}
		val cleaned = s.replace(NON_ALPHANUMERIC_REGEX, "")
		return cleaned.ifBlank { null }
	}

	companion object {
		private val TITLE_PREFIXES = listOf("the ", "a ", "an ")
		private val NON_ALPHANUMERIC_REGEX = Regex("[^a-z0-9]")
	}






	suspend fun findPopularSources(categoryId: Long, limit: Int): List<MangaSource> {
		return db.getFavouritesDao().run {
			if (categoryId == 0L) {
				findPopularSources(limit)
			} else {
				findPopularSources(categoryId, limit)
			}
		}.toMangaSources()
	}

	suspend fun findSources(categoryId: Long): List<MangaSource> {
		return findPopularSources(categoryId, Int.MAX_VALUE)
	}

	suspend fun createCategory(
		title: String,
		sortOrder: ListSortOrder,
		isTrackerEnabled: Boolean,
		isNewChaptersDownloadEnabled: Boolean,
		isVisibleOnShelf: Boolean,
	): FavouriteCategory {
		val entity = FavouriteCategoryEntity(
			title = title,
			createdAt = System.currentTimeMillis(),
			sortKey = db.getFavouriteCategoriesDao().getNextSortKey(),
			categoryId = 0,
			order = sortOrder.name,
			track = isTrackerEnabled,
			downloadNewChapters = isNewChaptersDownloadEnabled,
			deletedAt = 0L,
			isVisibleInLibrary = isVisibleOnShelf,
		)
		val id = db.getFavouriteCategoriesDao().insert(entity)
		val category = entity.toFavouriteCategory(id)
		return category
	}

	suspend fun updateCategory(
		id: Long,
		title: String,
		sortOrder: ListSortOrder,
		isTrackerEnabled: Boolean,
		isNewChaptersDownloadEnabled: Boolean,
		isVisibleOnShelf: Boolean,
	) {
		db.getFavouriteCategoriesDao().update(
			id = id,
			title = title,
			order = sortOrder.name,
			tracker = isTrackerEnabled,
			downloadNewChapters = isNewChaptersDownloadEnabled,
			onShelf = isVisibleOnShelf,
		)
	}

	suspend fun updateCategory(id: Long, isVisibleInLibrary: Boolean) {
		db.getFavouriteCategoriesDao().updateVisibility(id, isVisibleInLibrary)
	}

	suspend fun updateCategoryTracking(id: Long, isTrackingEnabled: Boolean) {
		db.getFavouriteCategoriesDao().updateTracking(id, isTrackingEnabled)
	}

	suspend fun setNewChaptersDownloadCategories(ids: Set<Long>) {
		db.withTransaction {
			val dao = db.getFavouriteCategoriesDao()
			dao.clearNewChaptersDownload()
			for (id in ids) {
				dao.updateNewChaptersDownload(id, true)
			}
		}
	}

	suspend fun enableNewChaptersDownloadForTrackedCategories() {
		db.getFavouriteCategoriesDao().enableNewChaptersDownloadForTracked()
	}

	suspend fun isNewChaptersDownloadEnabled(mangaId: Long): Boolean {
		return db.getFavouritesDao().isNewChaptersDownloadEnabled(mangaId)
	}

	suspend fun removeCategories(ids: Collection<Long>) {
		db.withTransaction {
			for (id in ids) {
				db.getFavouritesDao().deleteAll(id)
				db.getFavouriteCategoriesDao().delete(id)
			}
			db.getChaptersDao().gc()
		}
	}

	suspend fun setCategoryOrder(id: Long, order: ListSortOrder) {
		db.getFavouriteCategoriesDao().updateOrder(id, order.name)
	}

	suspend fun reorderCategories(orderedIds: List<Long>) {
		val dao = db.getFavouriteCategoriesDao()
		db.withTransaction {
			for ((i, id) in orderedIds.withIndex()) {
				dao.updateSortKey(id, i)
			}
		}
	}

	suspend fun addToCategory(categoryId: Long, mangas: Collection<Manga>) {
		db.withTransaction {
			for (manga in mangas) {
				val tags = manga.tags.toEntities()
				db.getTagsDao().upsert(tags)
				db.getMangaDao().upsert(manga.toEntity(), tags)
				val entity = FavouriteEntity(
					mangaId = manga.id,
					categoryId = categoryId,
					createdAt = System.currentTimeMillis(),
					sortKey = 0,
					deletedAt = 0L,
					isPinned = false,
				)
				db.getFavouritesDao().insert(entity)
			}
		}
	}

	suspend fun removeFromFavourites(ids: Collection<Long>): ReversibleHandle {
		db.withTransaction {
			for (id in ids) {
				db.getFavouritesDao().delete(mangaId = id)
			}
			db.getChaptersDao().gc()
		}
		return ReversibleHandle { recoverToFavourites(ids) }
	}

	suspend fun removeFromCategory(categoryId: Long, ids: Collection<Long>): ReversibleHandle {
		db.withTransaction {
			for (id in ids) {
				db.getFavouritesDao().delete(categoryId = categoryId, mangaId = id)
			}
			db.getChaptersDao().gc()
		}
		return ReversibleHandle { recoverToCategory(categoryId, ids) }
	}

	private fun observeOrder(categoryId: Long): Flow<ListSortOrder> {
		return db.getFavouriteCategoriesDao().observe(categoryId)
			.filterNotNull()
			.map { x -> ListSortOrder(x.order, ListSortOrder.NEWEST) }
			.distinctUntilChanged()
	}

	suspend fun getMostUpdatedCategories(limit: Int): List<FavouriteCategory> {
		return db.getFavouriteCategoriesDao().getMostUpdatedCategories(limit).map {
			it.toFavouriteCategory()
		}
	}

	private suspend fun recoverToFavourites(ids: Collection<Long>) {
		db.withTransaction {
			for (id in ids) {
				db.getFavouritesDao().recover(mangaId = id)
			}
		}
	}

	private suspend fun recoverToCategory(categoryId: Long, ids: Collection<Long>) {
		db.withTransaction {
			for (id in ids) {
				db.getFavouritesDao().recover(mangaId = id, categoryId = categoryId)
			}
		}
	}
}
