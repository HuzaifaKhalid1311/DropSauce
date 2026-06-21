package org.koitharu.kotatsu.alternatives.domain

import org.koitharu.kotatsu.parsers.util.longHashCode
import androidx.room.withTransaction
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.details.domain.ProgressUpdateUseCase
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.history.data.toMangaHistory
import org.koitharu.kotatsu.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import org.koitharu.kotatsu.tracker.data.TrackEntity
import javax.inject.Inject

class MigrateUseCase
@Inject
constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val mangaDataRepository: MangaDataRepository,
	private val database: MangaDatabase,
	private val progressUpdateUseCase: ProgressUpdateUseCase,
	private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
) {
	suspend operator fun invoke(
		oldManga: Manga,
		newManga: Manga,
	) {
		val oldDetails = if (oldManga.chapters.isNullOrEmpty()) {
			runCatchingCancellable {
				mangaRepositoryFactory.create(oldManga.source).getDetails(oldManga)
			}.getOrDefault(oldManga)
		} else {
			oldManga
		}
		val newDetails = if (newManga.chapters.isNullOrEmpty()) {
			mangaRepositoryFactory.create(newManga.source).getDetails(newManga)
		} else {
			newManga
		}

		mangaDataRepository.storeManga(newDetails, replaceExisting = true)
		database.withTransaction {
			// replace favorites
			val favoritesDao = database.getFavouritesDao()
			val oldFavourites = favoritesDao.findAllRaw(oldDetails.id)
			val existingFavourites = favoritesDao.findAllRaw(newDetails.id)
			val existingCategoryIds = existingFavourites.map { it.categoryId }.toSet()
			if (oldFavourites.isNotEmpty()) {
				favoritesDao.delete(oldManga.id)
				for (f in oldFavourites) {
					if (f.categoryId in existingCategoryIds) {
						continue
					}
					val e =
						f.copy(
							mangaId = newDetails.id,
						)
					favoritesDao.upsert(e)
				}
			}
			// replace bookmarks
			val bookmarksDao = database.getBookmarksDao()
			val oldBookmarks = bookmarksDao.findAll(oldDetails.id)
			if (oldBookmarks.isNotEmpty()) {
				for (bookmark in oldBookmarks) {
					val oldChapters = oldDetails.chapters
					val newChapters = newDetails.chapters
					val oldChapter = oldChapters?.firstOrNull { it.id == bookmark.chapterId }
					val newChapter = if (oldChapter != null && newChapters != null) {
						val branch = oldChapter.branch
						val newBranch = if (newChapters.any { it.branch == branch }) {
							branch
						} else {
							newDetails.getPreferredBranch(null)
						}
						val newBranchChapters = newChapters.filter { it.branch == newBranch }
						newBranchChapters.firstOrNull { it.volume == oldChapter.volume && it.number == oldChapter.number }
							?: newBranchChapters.getOrNull(oldChapters.indexOf(oldChapter))
							?: newBranchChapters.firstOrNull()
					} else {
						null
					}
					if (newChapter != null) {
						bookmarksDao.delete(bookmark.pageId)
						val newPageId = if (newDetails.source is org.koitharu.kotatsu.mihon.model.MihonMangaSource) {
							val mihonSource = newDetails.source as org.koitharu.kotatsu.mihon.model.MihonMangaSource
							val sourceKey = "mihon:${mihonSource.pkgName}:${mihonSource.catalogueSource.name}"
							"$sourceKey|page|${newChapter.url}|${bookmark.page}".longHashCode()
						} else {
							"${newDetails.id}|${newChapter.id}|${bookmark.page}".longHashCode()
						}
						val newBookmark = bookmark.copy(
							mangaId = newDetails.id,
							pageId = newPageId,
							chapterId = newChapter.id,
						)
						bookmarksDao.insert(newBookmark)
					}
				}
			}
			// replace history
			val historyDao = database.getHistoryDao()
			val oldHistory = historyDao.find(oldDetails.id)
			val newHistory =
				if (oldHistory != null) {
					val newHistory = makeNewHistory(oldDetails, newDetails, oldHistory)
					historyDao.delete(oldDetails.id)
					historyDao.upsert(newHistory)
					newHistory
				} else {
					null
				}
			// track
			val tracksDao = database.getTracksDao()
			val oldTrack = tracksDao.find(oldDetails.id)
			if (oldTrack != null) {
				val lastChapter = newDetails.chapters?.lastOrNull()
				val newTrack =
					TrackEntity(
						mangaId = newDetails.id,
						lastChapterId = lastChapter?.id ?: 0L,
						newChapters = 0,
						lastCheckTime = System.currentTimeMillis(),
						lastChapterDate = lastChapter?.uploadDate ?: 0L,
						lastResult = TrackEntity.RESULT_EXTERNAL_MODIFICATION,
						lastError = null,
					)
				tracksDao.delete(oldDetails.id)
				tracksDao.upsert(newTrack)
			}
			// scrobbling
			for (scrobbler in scrobblers) {
				if (!scrobbler.isEnabled) {
					continue
				}
				val prevInfo = scrobbler.getScrobblingInfoOrNull(oldDetails.id) ?: continue
				scrobbler.unregisterScrobbling(oldDetails.id)
				scrobbler.linkManga(newDetails.id, prevInfo.targetId)
				scrobbler.updateScrobblingInfo(
					mangaId = newDetails.id,
					rating = prevInfo.rating,
					status =
						prevInfo.status ?: when {
							newHistory == null -> ScrobblingStatus.PLANNED
							newHistory.percent == 1f -> ScrobblingStatus.COMPLETED
							else -> ScrobblingStatus.READING
						},
					comment = prevInfo.comment,
				)
				if (newHistory != null) {
					scrobbler.scrobble(
						manga = newDetails,
						chapterId = newHistory.chapterId,
					)
				}
			}
		}
		progressUpdateUseCase(newDetails)
	}

	private fun makeNewHistory(
		oldManga: Manga,
		newManga: Manga,
		history: HistoryEntity,
	): HistoryEntity {
		if (oldManga.chapters.isNullOrEmpty()) { // probably broken manga/source
			val branch = newManga.getPreferredBranch(null)
			val chapters = checkNotNull(newManga.getChapters(branch))
			if (chapters.isEmpty()) {
				return HistoryEntity(
					mangaId = newManga.id,
					createdAt = history.createdAt,
					updatedAt = history.updatedAt,
					chapterId = 0L,
					page = history.page,
					scroll = history.scroll,
					percent = history.percent,
					deletedAt = 0,
					chaptersCount = 0,
				)
			}
			val currentChapter =
				if (history.percent in 0f..1f) {
					chapters[(chapters.lastIndex * history.percent).toInt()]
				} else {
					chapters.first()
				}
			return HistoryEntity(
				mangaId = newManga.id,
				createdAt = history.createdAt,
				updatedAt = history.updatedAt,
				chapterId = currentChapter.id,
				page = history.page,
				scroll = history.scroll,
				percent = history.percent,
				deletedAt = 0,
				chaptersCount = chapters.count { it.branch == currentChapter.branch },
			)
		}
		val branch = oldManga.getPreferredBranch(history.toMangaHistory())
		val oldChapters = checkNotNull(oldManga.getChapters(branch))
		var index = oldChapters.indexOfFirst { it.id == history.chapterId }
		if (index < 0) {
			index =
				if (history.percent in 0f..1f) {
					(oldChapters.lastIndex * history.percent).toInt()
				} else {
					0
				}
		}
		val newChapters = checkNotNull(newManga.chapters).groupBy { it.branch }
		val newBranch =
			if (newChapters.containsKey(branch)) {
				branch
			} else {
				newManga.getPreferredBranch(null)
			}
		val newChapterId =
			checkNotNull(newChapters[newBranch])
				.let {
					val oldChapter = oldChapters[index]
					it.findByNumber(oldChapter.volume, oldChapter.number) ?: it.getOrNull(index) ?: it.last()
				}.id

		return HistoryEntity(
			mangaId = newManga.id,
			createdAt = history.createdAt,
			updatedAt = history.updatedAt,
			chapterId = newChapterId,
			page = history.page,
			scroll = history.scroll,
			percent = history.percent,
			deletedAt = 0,
			chaptersCount = checkNotNull(newChapters[newBranch]).size,
		)
	}

	private fun List<MangaChapter>.findByNumber(
		volume: Int,
		number: Float,
	): MangaChapter? =
		if (number <= 0f) {
			null
		} else {
			firstOrNull { it.volume == volume && it.number == number }
		}
}
