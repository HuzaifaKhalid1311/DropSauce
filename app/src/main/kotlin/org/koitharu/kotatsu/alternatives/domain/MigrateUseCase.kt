package org.koitharu.kotatsu.alternatives.domain

import androidx.room.withTransaction
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.details.domain.ProgressUpdateUseCase
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.history.data.toMangaHistory
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
	/**
	 * @param migrateProgress when false the new manga simply takes the old one's place in the library:
	 * favourites and per-manga preferences still move, but reading history, bookmarks, the update
	 * tracker and scrobbler links are dropped along with the entry being replaced.
	 */
	suspend operator fun invoke(
		oldManga: Manga,
		newManga: Manga,
		migrateProgress: Boolean = true,
	) {
		// Only the progress migration needs the old chapter list; without it this network call is waste.
		val oldDetails = if (migrateProgress && oldManga.chapters.isNullOrEmpty()) {
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
			if (oldFavourites.isNotEmpty()) {
				favoritesDao.delete(oldDetails.id)
				for (f in oldFavourites) {
					val e =
						f.copy(
							mangaId = newDetails.id,
						)
					favoritesDao.upsert(e)
				}
			}
			// per-manga preferences: reading mode, colour filter, title/cover overrides
			val preferencesDao = database.getPreferencesDao()
			preferencesDao.find(oldDetails.id)?.let { prefs ->
				preferencesDao.delete(oldDetails.id)
				preferencesDao.upsert(prefs.copy(mangaId = newDetails.id))
			}
			if (!migrateProgress) {
				// Plain replacement: whatever the old entry had read, bookmarked or tracked goes with it.
				database.getBookmarksDao().deleteAll(oldDetails.id)
				database.getHistoryDao().delete(oldDetails.id)
				database.getTracksDao().delete(oldDetails.id)
				for (scrobbler in scrobblers) {
					if (scrobbler.isEnabled) {
						scrobbler.unregisterScrobbling(oldDetails.id)
					}
				}
				return@withTransaction
			}
			// bookmarks, re-pointed at the matching chapter of the new source
			val bookmarksDao = database.getBookmarksDao()
			val oldBookmarks = bookmarksDao.findAll(oldDetails.id)
			if (oldBookmarks.isNotEmpty()) {
				val chapterIds = mapChapterIds(oldDetails, newDetails)
				// The page id and thumbnail belong to the old source and can't be recomputed, but the
				// reader finds a bookmark by manga + chapter + page, so those keep working.
				val migrated = oldBookmarks.mapNotNull { bookmark ->
					val newChapterId = chapterIds[bookmark.chapterId] ?: return@mapNotNull null
					bookmark.copy(mangaId = newDetails.id, chapterId = newChapterId)
				}
				bookmarksDao.deleteAll(oldDetails.id)
				if (migrated.isNotEmpty()) {
					bookmarksDao.upsert(migrated)
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
				val status = prevInfo.status ?: when {
					newHistory == null -> ScrobblingStatus.PLANNED
					newHistory.percent == 1f -> ScrobblingStatus.COMPLETED
					else -> ScrobblingStatus.READING
				}
				scrobbler.unregisterScrobbling(oldDetails.id)
				scrobbler.linkManga(newDetails.id, prevInfo.targetId, status)
				// The remote entry is the same one, so this carries the old rating and note across too
				scrobbler.updateScrobblingInfo(
					mangaId = newDetails.id,
					rating = prevInfo.rating,
					status = status,
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
		progressUpdateUseCase(newManga)
	}

	private fun makeNewHistory(
		oldManga: Manga,
		newManga: Manga,
		history: HistoryEntity,
	): HistoryEntity {
		// Nothing to point the progress at: fail the migration (the transaction rolls back and the old
		// entry stays intact) rather than migrating it with its reading history silently dropped.
		check(!newManga.chapters.isNullOrEmpty()) { "${newManga.title} has no chapters on the new source" }
		if (oldManga.chapters.isNullOrEmpty()) { // probably broken manga/source
			val branch = newManga.getPreferredBranch(null)
			val chapters = newManga.getChapters(branch).ifEmpty { newManga.chapters.orEmpty() }
			val currentChapter =
				if (history.percent in 0f..1f) {
					chapters[(chapters.lastIndex * history.percent).toInt().coerceIn(0, chapters.lastIndex)]
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
		val oldChapters = oldManga.getChapters(branch).ifEmpty { oldManga.chapters.orEmpty() }
		var index = oldChapters.indexOfFirst { it.id == history.chapterId }
		if (index < 0) {
			index =
				if (history.percent in 0f..1f) {
					(oldChapters.lastIndex * history.percent).toInt().coerceIn(0, oldChapters.lastIndex)
				} else {
					0
				}
		}
		val newChapters = newManga.chapters.orEmpty().groupBy { it.branch }
		val newBranch =
			if (newChapters.containsKey(branch)) {
				branch
			} else {
				newManga.getPreferredBranch(null)
			}
		val branchChapters = newChapters[newBranch].orEmpty().ifEmpty { newManga.chapters.orEmpty() }
		val oldChapter = oldChapters[index]
		val newChapterId = (branchChapters.findByNumber(oldChapter.volume, oldChapter.number)
			?: branchChapters.getOrNull(index)
			?: branchChapters.last()).id

		return HistoryEntity(
			mangaId = newManga.id,
			createdAt = history.createdAt,
			updatedAt = history.updatedAt,
			chapterId = newChapterId,
			page = history.page,
			scroll = history.scroll,
			// Keep the progress the reader already earned; the equivalent chapter was just found above,
			// so resetting it here would wipe the progress bar for no reason.
			percent = history.percent,
			deletedAt = 0,
			chaptersCount = branchChapters.size,
		)
	}

	/**
	 * Old chapter id -> new chapter id, matched by volume and chapter number across every branch.
	 * Chapters with no counterpart are simply absent, so callers drop whatever hangs off them.
	 */
	private fun mapChapterIds(
		oldManga: Manga,
		newManga: Manga,
	): Map<Long, Long> {
		val newChapters = newManga.chapters
		if (newChapters.isNullOrEmpty()) {
			return emptyMap()
		}
		val byNumber = HashMap<Pair<Int, Float>, Long>(newChapters.size)
		for (chapter in newChapters) {
			if (chapter.number > 0f) {
				byNumber.putIfAbsent(chapter.volume to chapter.number, chapter.id)
			}
		}
		val oldChapters = oldManga.chapters ?: return emptyMap()
		val result = HashMap<Long, Long>(oldChapters.size)
		for (chapter in oldChapters) {
			byNumber[chapter.volume to chapter.number]?.let { result[chapter.id] = it }
		}
		return result
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
