package org.koitharu.kotatsu.sync.domain

import org.koitharu.kotatsu.backup.local.data.model.MangaBackup
import org.koitharu.kotatsu.backup.local.domain.CustomCoverCodec
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.util.ext.toFileOrNull
import org.koitharu.kotatsu.core.util.ext.toUriOrNull
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
import org.koitharu.kotatsu.sync.data.model.RTombstone
import org.koitharu.kotatsu.sync.data.model.Replica
import org.koitharu.kotatsu.sync.data.model.SyncContent
import org.koitharu.kotatsu.sync.data.model.SyncRecord
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Turns the local database (plus already-detected prefs) into this device's [Replica]. */
@Singleton
class ReplicaBuilder @Inject constructor(
	private val database: MangaDatabase,
	private val coverCodec: CustomCoverCodec,
) {

	/** A local custom cover that has to exist in the sync store as `cover_<hash>`; read again on upload. */
	class Cover(val hash: String, val extension: String?, val url: String)

	class Local(val replica: Replica, val covers: List<Cover>)

	private class CoverInfo(val stamp: Long?, val hash: String, val extension: String?)

	// Builds run every few minutes while reading; don't re-read and re-hash unchanged cover files.
	private val coverInfos = HashMap<String, CoverInfo>()

	/**
	 * Local category id → cross-device uid. Guarantees every category has a distinct uid: a category
	 * the triggers never saw (the pre-populated one) gets its title uid, and a uid shared by two local
	 * categories (e.g. a renamed one and a new one taking its old name) stays with the older category.
	 */
	suspend fun categoryUids(): Map<Int, String> {
		val dao = database.getSyncDao()
		val rows = dao.findRows(SyncTriggers.CATEGORIES).associateBy { it.pk }
		val result = HashMap<Int, String>()
		val used = HashSet<String>()
		for (category in database.getFavouriteCategoriesDao().findAllForSync().sortedBy { it.categoryId }) {
			val pk = category.categoryId.toString()
			val row = rows[pk]
			var uid = row?.syncKey ?: ("t:" + category.title.trim(' '))
			if (!used.add(uid)) {
				uid = UUID.randomUUID().toString().replace("-", "")
				used += uid
			}
			when {
				row == null -> dao.upsertRow(SyncRowEntity(SyncTriggers.CATEGORIES, pk, 0L, false, uid))
				row.syncKey != uid -> dao.setKey(SyncTriggers.CATEGORIES, pk, uid)
			}
			result[category.categoryId] = uid
		}
		return result
	}

	suspend fun build(
		deviceId: String,
		content: Set<SyncContent>,
		categoryUids: Map<Int, String>,
		prefs: List<RPref>,
	): Local {
		val syncDao = database.getSyncDao()
		val tombstones = ArrayList<RTombstone>()
		val mangaIds = HashSet<Long>()

		suspend fun versions(table: String): Map<String, SyncRowEntity> {
			val rows = syncDao.findRows(table)
			for (row in rows) {
				if (!row.isDeleted) continue
				val key = when (table) {
					SyncTriggers.FAVOURITES -> {
						row.pk.substringAfter(':').toIntOrNull()
							?.let { categoryUids[it] }
							?.let { row.pk.substringBefore(':') + ':' + it }
					}

					SyncTriggers.CATEGORIES -> row.syncKey
					SyncTriggers.FEED -> row.syncKey?.let(FeedKeys::ofRaw)
					else -> row.pk
				} ?: continue
				tombstones += RTombstone(table, key, row.version)
			}
			return rows.associateBy { it.pk }
		}

		var categories = emptyList<RCategory>()
		var favourites = emptyList<RFavourite>()
		if (SyncContent.FAVOURITES in content) {
			val v = versions(SyncTriggers.CATEGORIES)
			categories = database.getFavouriteCategoriesDao().findAllForSync().mapNotNull { c ->
				RCategory(
					uid = categoryUids[c.categoryId] ?: return@mapNotNull null,
					title = c.title,
					sortKey = c.sortKey,
					order = c.order,
					track = c.track,
					downloadNewChapters = c.downloadNewChapters,
					isVisibleInLibrary = c.isVisibleInLibrary,
					createdAt = c.createdAt,
					deletedAt = c.deletedAt,
					v = v[c.categoryId.toString()]?.version ?: 0L,
				)
			}
			val fv = versions(SyncTriggers.FAVOURITES)
			favourites = database.getFavouritesDao().findAllForSync().mapNotNull { f ->
				mangaIds += f.mangaId
				RFavourite(
					mangaId = f.mangaId,
					categoryUid = categoryUids[f.categoryId.toInt()] ?: return@mapNotNull null,
					sortKey = f.sortKey,
					isPinned = f.isPinned,
					createdAt = f.createdAt,
					deletedAt = f.deletedAt,
					v = fv["${f.mangaId}:${f.categoryId}"]?.version ?: 0L,
				)
			}
		}

		var history = emptyList<RHistory>()
		if (SyncContent.HISTORY in content) {
			val v = versions(SyncTriggers.HISTORY)
			history = database.getHistoryDao().findAllForSync().map { h ->
				mangaIds += h.mangaId
				RHistory(
					mangaId = h.mangaId,
					createdAt = h.createdAt,
					updatedAt = h.updatedAt,
					chapterId = h.chapterId,
					page = h.page,
					scroll = h.scroll,
					percent = h.percent,
					chaptersCount = h.chaptersCount,
					deletedAt = h.deletedAt,
					v = v[h.mangaId.toString()]?.version ?: 0L,
				)
			}
		}

		var stats = emptyList<RStats>()
		if (SyncContent.STATS in content) {
			val v = versions(SyncTriggers.STATS)
			stats = syncDao.findAllStats().map { s ->
				RStats(s.mangaId, s.startedAt, s.duration, s.pages, s.chapters, v["${s.mangaId}:${s.startedAt}"]?.version ?: 0L)
			}
		}

		var bookmarks = emptyList<RBookmark>()
		if (SyncContent.BOOKMARKS in content) {
			val v = versions(SyncTriggers.BOOKMARKS)
			bookmarks = syncDao.findAllBookmarks().map { b ->
				mangaIds += b.mangaId
				RBookmark(
					mangaId = b.mangaId,
					pageId = b.pageId,
					chapterId = b.chapterId,
					page = b.page,
					scroll = b.scroll,
					imageUrl = b.imageUrl,
					createdAt = b.createdAt,
					percent = b.percent,
					v = v["${b.mangaId}:${b.pageId}"]?.version ?: 0L,
				)
			}
		}

		var scrobblings = emptyList<RScrobbling>()
		if (SyncContent.TRACKING in content) {
			val v = versions(SyncTriggers.SCROBBLINGS)
			scrobblings = syncDao.findAllScrobblings().map { s ->
				RScrobbling(
					scrobbler = s.scrobbler,
					id = s.id,
					mangaId = s.mangaId,
					targetId = s.targetId,
					status = s.status,
					chapter = s.chapter,
					comment = s.comment,
					rating = s.rating,
					v = v["${s.scrobbler}:${s.id}:${s.mangaId}"]?.version ?: 0L,
				)
			}
		}

		var feed = emptyList<RFeed>()
		if (SyncContent.FEED in content) {
			val v = versions(SyncTriggers.FEED)
			feed = database.getTrackLogsDao().findAllForSync().map { log ->
				mangaIds += log.mangaId
				RFeed(log.mangaId, log.chapters, log.chapterIds, log.createdAt, log.isUnread, v[log.id.toString()]?.version ?: 0L)
			}.distinctBy { it.key } // duplicates are collapsed by the applier's feed normalization
		}

		var mangaPrefs = emptyList<RMangaPrefs>()
		val covers = ArrayList<Cover>()
		if (SyncContent.CUSTOM_COVERS in content) {
			val v = versions(SyncTriggers.MANGA_PREFS)
			mangaPrefs = syncDao.findAllMangaPrefs().map { p ->
				mangaIds += p.mangaId
				val cover = coverInfo(p.coverUrlOverride)
				val coverHash = cover?.hash
				if (cover != null) {
					covers += Cover(cover.hash, cover.extension, checkNotNull(p.coverUrlOverride))
				}
				RMangaPrefs(
					mangaId = p.mangaId,
					mode = p.mode,
					cfBrightness = p.cfBrightness,
					cfContrast = p.cfContrast,
					cfInvert = p.cfInvert,
					cfGrayscale = p.cfGrayscale,
					cfBookEffect = p.cfBookEffect,
					titleOverride = p.titleOverride,
					descriptionOverride = p.descriptionOverride,
					coverUrl = p.coverUrlOverride.takeIf { cover == null && coverCodec.isPortableCoverUrl(it) },
					coverHash = coverHash,
					coverExtension = cover?.extension,
					contentRatingOverride = p.contentRatingOverride,
					mergeScanlators = p.mergeScanlators,
					v = v[p.mangaId.toString()]?.version ?: 0L,
				)
			}
		}

		// Manga imported from files point at this device's storage and can't open anywhere else.
		val localOnly = syncDao.findMangaIds(LocalMangaSource.name).toHashSet()
		mangaIds.removeAll(localOnly)
		val manga = mangaIds.chunked(500).flatMap { syncDao.findManga(it) }.map(::MangaBackup).sortedBy { it.id }
		val replica = Replica(
			deviceId = deviceId,
			content = content.mapTo(sortedSetOf()) { it.key },
			manga = manga,
			categories = categories.sortedByKey(),
			favourites = favourites.filterNot { it.mangaId in localOnly }.sortedByKey(),
			history = history.filterNot { it.mangaId in localOnly }.sortedByKey(),
			bookmarks = bookmarks.filterNot { it.mangaId in localOnly }.sortedByKey(),
			scrobblings = scrobblings.filterNot { it.mangaId in localOnly }.sortedByKey(),
			stats = stats.filterNot { it.mangaId in localOnly }.sortedByKey(),
			feed = feed.filterNot { it.mangaId in localOnly }.sortedByKey(),
			mangaPrefs = mangaPrefs.filterNot { it.mangaId in localOnly }.sortedByKey(),
			prefs = if (SyncContent.SETTINGS in content) prefs.sortedByKey() else emptyList(),
			tombstones = tombstones.sortedWith(compareBy({ it.table }, { it.recordKey })),
		)
		return Local(replica, covers.distinctBy { it.hash })
	}

	suspend fun coverBytes(cover: Cover): ByteArray? = coverCodec.readRaw(cover.url)?.bytes

	private suspend fun coverInfo(url: String?): CoverInfo? {
		if (url == null) return null
		val file = url.toUriOrNull()?.toFileOrNull()?.takeIf { it.isFile }
		val stamp = file?.let { it.length() * 31 + it.lastModified() }
		coverInfos[url]?.let { if (stamp != null && it.stamp == stamp) return it }
		val raw = coverCodec.readRaw(url) ?: return null
		return CoverInfo(stamp, CustomCoverCodec.sha256(raw.bytes), raw.extension).also { coverInfos[url] = it }
	}

	private fun <T : SyncRecord> List<T>.sortedByKey() = sortedBy { it.key }
}
