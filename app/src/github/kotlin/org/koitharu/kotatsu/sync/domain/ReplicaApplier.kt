package org.koitharu.kotatsu.sync.domain

import android.util.Log
import androidx.room.withTransaction
import dagger.Reusable
import org.koitharu.kotatsu.backup.local.data.model.MangaBackup
import org.koitharu.kotatsu.backup.local.domain.CustomCoverCodec
import org.koitharu.kotatsu.bookmarks.data.BookmarkEntity
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.MangaPrefsEntity
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.favourites.data.FavouriteCategoryEntity
import org.koitharu.kotatsu.favourites.data.FavouriteEntity
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.history.data.markFeedReadUpTo
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.stats.data.StatsEntity
import org.koitharu.kotatsu.sync.data.db.SyncRowEntity
import org.koitharu.kotatsu.sync.data.db.SyncTriggers
import org.koitharu.kotatsu.sync.data.model.FeedKeys
import org.koitharu.kotatsu.sync.data.model.RBookmark
import org.koitharu.kotatsu.sync.data.model.RCategory
import org.koitharu.kotatsu.sync.data.model.RFavourite
import org.koitharu.kotatsu.sync.data.model.RFeed
import org.koitharu.kotatsu.sync.data.model.RHistory
import org.koitharu.kotatsu.sync.data.model.RMangaPrefs
import org.koitharu.kotatsu.sync.data.model.RPref
import org.koitharu.kotatsu.sync.data.model.RScrobbling
import org.koitharu.kotatsu.sync.data.model.RStats
import org.koitharu.kotatsu.tracker.data.TrackEntity
import org.koitharu.kotatsu.tracker.data.TrackLogEntity
import javax.inject.Inject

/** Everything a merge decided to change locally, per section. */
class SyncChanges(
	val categories: ReplicaMerger.Changes<RCategory>,
	val favourites: ReplicaMerger.Changes<RFavourite>,
	val history: ReplicaMerger.Changes<RHistory>,
	val stats: ReplicaMerger.Changes<RStats>,
	val bookmarks: ReplicaMerger.Changes<RBookmark>,
	val scrobblings: ReplicaMerger.Changes<RScrobbling>,
	val feed: ReplicaMerger.Changes<RFeed>,
	val mangaPrefs: ReplicaMerger.Changes<RMangaPrefs>,
	val prefs: ReplicaMerger.Changes<RPref>,
) {

	val isEmpty
		get() = categories.isEmpty && favourites.isEmpty && history.isEmpty && stats.isEmpty &&
			bookmarks.isEmpty && scrobblings.isEmpty && feed.isEmpty && mangaPrefs.isEmpty && prefs.isEmpty
}

/**
 * Writes merge winners into the local database. Every write is compare-and-set against the row's
 * current version inside a short transaction, so something the user changed while the sync was
 * running is never overwritten. After a write the row takes the remote version, so it isn't echoed
 * back as a local change.
 */
@Reusable
class ReplicaApplier @Inject constructor(
	private val database: MangaDatabase,
	private val coverCodec: CustomCoverCodec,
	private val syncPrefs: SyncPrefs,
) {

	private val syncDao get() = database.getSyncDao()

	/**
	 * @return false when something could not be applied (e.g. a cover failed to download), so the
	 * caller re-merges those replicas next time.
	 */
	suspend fun apply(
		changes: SyncChanges,
		manga: Map<Long, MangaBackup>,
		categoryUids: Map<Int, String>,
		fetchCover: suspend (hash: String) -> ByteArray?,
	): Boolean {
		var isComplete = true
		val ensured = HashSet<Long>()

		suspend fun ensureManga(id: Long): Boolean {
			if (id in ensured) return true
			val remote = manga[id]
			val localDetailsAt = syncDao.findDetailsUpdatedAt(id)
			// Manga imported from files point at another device's storage.
			if (localDetailsAt == null && remote?.source == LocalMangaSource.name) return false
			if (remote != null && (localDetailsAt == null || remote.detailsUpdatedAt > localDetailsAt)) {
				val tags = remote.tags.map { it.toEntity() }
				if (tags.isNotEmpty()) database.getTagsDao().upsert(tags)
				database.getMangaDao().upsert(remote.toEntity(), tags)
			} else if (localDetailsAt == null) {
				return false
			}
			ensured += id
			return true
		}

		// Categories first: favourites refer to them.
		val categoryIds = categoryUids.entries.associateTo(HashMap()) { it.value to it.key }
		inChunks(changes.categories.upserts) { c ->
			val existing = categoryIds[c.uid]
			if (existing == null && c.deletedAt != 0L) return@inChunks // nothing here to delete
			if (existing != null && !canWrite(SyncTriggers.CATEGORIES, existing.toString(), c.v)) return@inChunks
			val entity = FavouriteCategoryEntity(
				categoryId = existing ?: 0,
				createdAt = c.createdAt,
				sortKey = c.sortKey,
				title = c.title,
				order = c.order,
				track = c.track,
				downloadNewChapters = c.downloadNewChapters,
				isVisibleInLibrary = c.isVisibleInLibrary,
				deletedAt = c.deletedAt,
			)
			val id = if (existing != null) {
				database.getFavouriteCategoriesDao().upsert(entity)
				existing
			} else {
				database.getFavouriteCategoriesDao().insert(entity).toInt()
			}
			categoryIds[c.uid] = id
			stamp(SyncTriggers.CATEGORIES, id.toString(), c.v, c.uid)
		}
		inChunks(changes.categories.deletes) { t ->
			val id = categoryIds[t.recordKey] ?: return@inChunks
			if (!canWrite(SyncTriggers.CATEGORIES, id.toString(), t.v)) return@inChunks
			syncDao.softDeleteCategory(id, t.v)
			syncDao.setVersion(SyncTriggers.CATEGORIES, id.toString(), t.v, false)
		}

		inChunks(changes.favourites.upserts) { f ->
			val categoryId = categoryIds[f.categoryUid] ?: return@inChunks
			val pk = "${f.mangaId}:$categoryId"
			if (f.deletedAt != 0L && syncDao.findRow(SyncTriggers.FAVOURITES, pk) == null) return@inChunks
			if (!canWrite(SyncTriggers.FAVOURITES, pk, f.v) || !ensureManga(f.mangaId)) return@inChunks
			database.getFavouritesDao().upsert(
				FavouriteEntity(f.mangaId, categoryId.toLong(), f.sortKey, f.isPinned, f.createdAt, f.deletedAt),
			)
			stamp(SyncTriggers.FAVOURITES, pk, f.v)
		}
		inChunks(changes.favourites.deletes) { t ->
			val mangaId = t.recordKey.substringBefore(':').toLongOrNull() ?: return@inChunks
			val categoryId = categoryIds[t.recordKey.substringAfter(':')] ?: return@inChunks
			val pk = "$mangaId:$categoryId"
			if (!canWrite(SyncTriggers.FAVOURITES, pk, t.v)) return@inChunks
			syncDao.softDeleteFavourite(mangaId, categoryId.toLong(), t.v)
			syncDao.setVersion(SyncTriggers.FAVOURITES, pk, t.v, false)
		}

		val progress = ArrayList<RHistory>()
		inChunks(changes.history.upserts) { h ->
			val pk = h.mangaId.toString()
			if (h.deletedAt != 0L && syncDao.findRow(SyncTriggers.HISTORY, pk) == null) return@inChunks
			if (!canWrite(SyncTriggers.HISTORY, pk, h.v) || !ensureManga(h.mangaId)) return@inChunks
			database.getHistoryDao().upsertForSync(
				HistoryEntity(
					mangaId = h.mangaId,
					createdAt = h.createdAt,
					updatedAt = h.updatedAt,
					chapterId = h.chapterId,
					page = h.page,
					scroll = h.scroll,
					percent = h.percent,
					deletedAt = h.deletedAt,
					chaptersCount = h.chaptersCount,
				),
			)
			stamp(SyncTriggers.HISTORY, pk, h.v)
			if (h.deletedAt == 0L) progress += h
		}
		inChunks(changes.history.deletes) { t ->
			val mangaId = t.recordKey.toLongOrNull() ?: return@inChunks
			if (!canWrite(SyncTriggers.HISTORY, t.recordKey, t.v)) return@inChunks
			syncDao.softDeleteHistory(mangaId, t.v)
			syncDao.setVersion(SyncTriggers.HISTORY, t.recordKey, t.v, false)
		}

		// Stats reference history, so they go after it.
		inChunks(changes.stats.upserts) { s ->
			if (!canWrite(SyncTriggers.STATS, s.key, s.v)) return@inChunks
			if (database.getHistoryDao().findIncludingDeleted(s.mangaId) == null) return@inChunks
			database.getStatsDao().upsert(StatsEntity(s.mangaId, s.startedAt, s.duration, s.pages, s.chapters))
			stamp(SyncTriggers.STATS, s.key, s.v)
		}
		inChunks(changes.stats.deletes) { t ->
			val mangaId = t.recordKey.substringBefore(':').toLongOrNull() ?: return@inChunks
			val startedAt = t.recordKey.substringAfter(':').toLongOrNull() ?: return@inChunks
			if (!canWrite(SyncTriggers.STATS, t.recordKey, t.v)) return@inChunks
			syncDao.deleteStats(mangaId, startedAt)
			syncDao.setVersion(SyncTriggers.STATS, t.recordKey, t.v, true)
		}

		inChunks(changes.bookmarks.upserts) { b ->
			if (!canWrite(SyncTriggers.BOOKMARKS, b.key, b.v) || !ensureManga(b.mangaId)) return@inChunks
			database.getBookmarksDao().upsert(
				listOf(BookmarkEntity(b.mangaId, b.pageId, b.chapterId, b.page, b.scroll, b.imageUrl, b.createdAt, b.percent)),
			)
			stamp(SyncTriggers.BOOKMARKS, b.key, b.v)
		}
		inChunks(changes.bookmarks.deletes) { t ->
			val mangaId = t.recordKey.substringBefore(':').toLongOrNull() ?: return@inChunks
			val pageId = t.recordKey.substringAfter(':').toLongOrNull() ?: return@inChunks
			if (!canWrite(SyncTriggers.BOOKMARKS, t.recordKey, t.v)) return@inChunks
			syncDao.deleteBookmark(mangaId, pageId)
			syncDao.setVersion(SyncTriggers.BOOKMARKS, t.recordKey, t.v, true)
		}

		inChunks(changes.scrobblings.upserts) { s ->
			if (!canWrite(SyncTriggers.SCROBBLINGS, s.key, s.v)) return@inChunks
			database.getScrobblingDao().upsert(
				ScrobblingEntity(s.scrobbler, s.id, s.mangaId, s.targetId, s.status, s.chapter, s.comment, s.rating),
			)
			stamp(SyncTriggers.SCROBBLINGS, s.key, s.v)
		}
		inChunks(changes.scrobblings.deletes) { t ->
			val parts = t.recordKey.split(':')
			val scrobbler = parts.getOrNull(0)?.toIntOrNull() ?: return@inChunks
			val id = parts.getOrNull(1)?.toIntOrNull() ?: return@inChunks
			val mangaId = parts.getOrNull(2)?.toLongOrNull() ?: return@inChunks
			if (!canWrite(SyncTriggers.SCROBBLINGS, t.recordKey, t.v)) return@inChunks
			syncDao.deleteScrobbling(scrobbler, id, mangaId)
			syncDao.setVersion(SyncTriggers.SCROBBLINGS, t.recordKey, t.v, true)
		}

		val touchedFeed = HashSet<Long>()
		if (!changes.feed.isEmpty) {
			// Feed rows have local ids; find them by content.
			val feedIds = HashMap<String, Long>()
			for (row in syncDao.findRows(SyncTriggers.FEED)) {
				if (row.isDeleted) continue
				val key = row.syncKey?.let(FeedKeys::ofRaw) ?: continue
				val id = row.pk.toLongOrNull() ?: continue
				feedIds.merge(key, id) { a, b -> minOf(a, b) }
			}
			inChunks(changes.feed.upserts) { f ->
				val existing = feedIds[f.key]
				if (existing != null && !canWrite(SyncTriggers.FEED, existing.toString(), f.v)) return@inChunks
				// The tracker's GC drops feed entries of manga without a track row (i.e. not in this
				// library) and that deletion would propagate back, so only take what this library keeps.
				if (!syncDao.isInLibrary(f.mangaId) || !ensureManga(f.mangaId)) return@inChunks
				if (database.getTracksDao().find(f.mangaId) == null) {
					database.getTracksDao().upsert(TrackEntity.create(f.mangaId))
				}
				val id = database.getTrackLogsDao().insert(
					TrackLogEntity(
						id = existing ?: 0L,
						mangaId = f.mangaId,
						chapters = f.chapters,
						chapterIds = f.chapterIds,
						createdAt = f.createdAt,
						isUnread = f.isUnread,
					),
				)
				val rawKey = "${f.mangaId}:" + f.chapterIds.ifEmpty { "t:" + f.chapters }
				stamp(SyncTriggers.FEED, id.toString(), f.v, rawKey)
				touchedFeed += f.mangaId
			}
			inChunks(changes.feed.deletes) { t ->
				val id = feedIds[t.recordKey] ?: return@inChunks
				if (!canWrite(SyncTriggers.FEED, id.toString(), t.v)) return@inChunks
				database.getTrackLogsDao().delete(id)
				syncDao.setVersion(SyncTriggers.FEED, id.toString(), t.v, true)
			}
		}

		if (!changes.mangaPrefs.isEmpty) {
			// Network stays out of the transactions below.
			val covers = HashMap<String, ByteArray>()
			for (hash in changes.mangaPrefs.upserts.mapNotNullTo(HashSet()) { it.coverHash }) {
				// null = not in Drive at all, nothing to wait for; a failed download is retried next time.
				runCatchingCancellable { fetchCover(hash) }
					.onSuccess { bytes -> if (bytes != null) covers[hash] = bytes }
					.onFailure { isComplete = false }
			}
			inChunks(changes.mangaPrefs.upserts) { p ->
				val pk = p.mangaId.toString()
				if (!canWrite(SyncTriggers.MANGA_PREFS, pk, p.v) || !ensureManga(p.mangaId)) return@inChunks
				val currentCover = database.getPreferencesDao().find(p.mangaId)?.coverUrlOverride
				val cover = if (p.coverHash != null) {
					val currentHash = coverCodec.readRaw(currentCover)?.let { CustomCoverCodec.sha256(it.bytes) }
					if (currentHash == p.coverHash) {
						currentCover
					} else {
						val bytes = covers[p.coverHash] ?: return@inChunks
						coverCodec.materialize(p.mangaId, bytes, p.coverExtension, currentCover) ?: return@inChunks
					}
				} else {
					p.coverUrl
				}
				database.getPreferencesDao().upsert(
					MangaPrefsEntity(
						mangaId = p.mangaId,
						mode = p.mode,
						cfBrightness = p.cfBrightness,
						cfContrast = p.cfContrast,
						cfInvert = p.cfInvert,
						cfGrayscale = p.cfGrayscale,
						cfBookEffect = p.cfBookEffect,
						titleOverride = p.titleOverride,
						coverUrlOverride = cover,
						descriptionOverride = p.descriptionOverride,
						contentRatingOverride = p.contentRatingOverride,
						mergeScanlators = p.mergeScanlators,
						mergedScanlators = p.mergedScanlators,
					),
				)
				stamp(SyncTriggers.MANGA_PREFS, pk, p.v)
			}
			inChunks(changes.mangaPrefs.deletes) { t ->
				val mangaId = t.recordKey.toLongOrNull() ?: return@inChunks
				if (!canWrite(SyncTriggers.MANGA_PREFS, t.recordKey, t.v)) return@inChunks
				database.getPreferencesDao().delete(mangaId)
				syncDao.setVersion(SyncTriggers.MANGA_PREFS, t.recordKey, t.v, true)
			}
		}

		if (!changes.prefs.isEmpty) {
			syncPrefs.apply(changes.prefs.upserts, categoryIds)
		}

		// Reading progress from another device has the same effects as reading here.
		for (h in progress) {
			runCatchingCancellable {
				database.markFeedReadUpTo(h.mangaId, h.chapterId)
				lowerNewChaptersCounter(h.mangaId, h.chapterId)
			}.onFailure { Log.w(TAG, "progress side effects failed for ${h.mangaId}", it) }
		}
		for (mangaId in touchedFeed) {
			runCatchingCancellable { normalizeFeed(mangaId) }
				.onFailure { Log.w(TAG, "feed normalization failed for $mangaId", it) }
		}
		return isComplete
	}

	/** The new-chapters badge can only go down: chapters after the synced position in its branch. */
	private suspend fun lowerNewChaptersCounter(mangaId: Long, chapterId: Long) {
		val track = database.getTracksDao().find(mangaId) ?: return
		if (track.newChapters == 0) return
		val chapters = database.getChaptersDao().findAll(mangaId)
		val current = chapters.firstOrNull { it.chapterId == chapterId } ?: return
		val branch = chapters.filter { it.branch == current.branch }
		val after = branch.size - 1 - branch.indexOf(current)
		if (after in 0 until track.newChapters) {
			database.getTracksDao().setCounter(mangaId, after)
		}
	}

	/**
	 * Two devices detect the same update independently, often grouped differently ({11}, {12} here
	 * vs {11, 12} there). An entry whose chapters are all covered by another entry is redundant.
	 */
	private suspend fun normalizeFeed(mangaId: Long) {
		val logs = syncDao.findFeed(mangaId).map { it to FeedKeys.chapterIds(it.chapterIds) }.filter { it.second.isNotEmpty() }
		for ((log, ids) in logs) {
			val redundant = logs.any { (other, otherIds) ->
				other.id != log.id && otherIds.containsAll(ids) && (otherIds.size > ids.size || other.id < log.id)
			}
			if (redundant) {
				database.getTrackLogsDao().delete(log.id)
			}
		}
	}

	private suspend fun canWrite(table: String, pk: String, version: Long): Boolean {
		val row = syncDao.findRow(table, pk) ?: return true
		return row.version <= version
	}

	private suspend fun stamp(table: String, pk: String, version: Long, key: String? = null) {
		syncDao.upsertRow(SyncRowEntity(table, pk, version, false, key))
	}

	private suspend fun <T> inChunks(items: List<T>, block: suspend (T) -> Unit) {
		for (chunk in items.chunked(CHUNK_SIZE)) {
			database.withTransaction {
				for (item in chunk) {
					runCatchingCancellable { block(item) }
						.onFailure { Log.w(TAG, "failed to apply $item", it) }
				}
			}
		}
	}

	private companion object {

		const val TAG = "GDriveSync"
		const val CHUNK_SIZE = 100
	}
}
