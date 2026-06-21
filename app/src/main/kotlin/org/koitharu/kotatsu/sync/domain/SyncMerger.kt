package org.koitharu.kotatsu.sync.domain

import org.koitharu.kotatsu.backup.local.data.model.BookmarkBackup
import org.koitharu.kotatsu.backup.local.data.model.ScrobblingBackup
import org.koitharu.kotatsu.backup.local.data.model.StatsBackup
import org.koitharu.kotatsu.sync.data.model.SyncCategory
import org.koitharu.kotatsu.sync.data.model.SyncConfig
import org.koitharu.kotatsu.sync.data.model.SyncFavourite
import org.koitharu.kotatsu.sync.data.model.SyncHistory
import org.koitharu.kotatsu.sync.data.model.SyncSnapshot
import org.koitharu.kotatsu.sync.data.model.SyncTrack

/**
 * Pure, side-effect-free merge of two snapshot halves. Records are unioned by their natural key and
 * the one with the newer "effective timestamp" wins — including its soft-delete state, which is how
 * deletions propagate across devices. On an exact timestamp tie the local record is kept (callers
 * pass local first), so a device never clobbers its own state with an equally-old remote copy.
 */
object SyncMerger {

	fun mergeCategories(local: List<SyncCategory>, remote: List<SyncCategory>): List<SyncCategory> =
		mergeBy(local, remote, key = { it.categoryId }, timestamp = { maxOf(it.createdAt, it.deletedAt) })

	fun mergeFavourites(local: List<SyncFavourite>, remote: List<SyncFavourite>): List<SyncFavourite> =
		mergeBy(
			local,
			remote,
			key = { it.mangaId to it.categoryId },
			timestamp = { maxOf(it.createdAt, it.deletedAt) },
		)

	fun mergeHistory(local: List<SyncHistory>, remote: List<SyncHistory>): List<SyncHistory> =
		mergeBy(
			local,
			remote,
			key = { it.mangaId },
			timestamp = { maxOf(it.updatedAt, it.createdAt, it.deletedAt) },
		)

	fun mergeTracks(local: List<SyncTrack>, remote: List<SyncTrack>): List<SyncTrack> =
		mergeBy(local, remote, key = { it.mangaId }, timestamp = { it.lastCheckTime })

	fun mergeStats(local: List<StatsBackup>, remote: List<StatsBackup>): List<StatsBackup> =
		mergeBy(local, remote, key = { it.mangaId to it.startedAt }, timestamp = { it.startedAt })

	// Scrobblings carry no timestamp column, so we use reading progress (chapter) as the effective
	// clock: progress only moves forward, so the higher chapter is the newer state. This lets
	// progress updates propagate across devices (the old "always keep local" never updated them).
	// Pure status/rating edits that don't advance the chapter still resolve to local on a tie.
	fun mergeScrobblings(
		local: List<ScrobblingBackup>,
		remote: List<ScrobblingBackup>,
	): List<ScrobblingBackup> =
		mergeBy(
			local,
			remote,
			key = { Triple(it.scrobbler, it.id, it.mangaId) },
			timestamp = { it.updatedAt },
		)

	/** Union of bookmark groups by manga, and of items within a group by page (local wins). */
	fun mergeBookmarks(local: List<BookmarkBackup>, remote: List<BookmarkBackup>): List<BookmarkBackup> {
		val byManga = LinkedHashMap<Long, BookmarkBackup>(local.size + remote.size)
		for (group in local) {
			byManga[group.manga.id] = group
		}
		for (group in remote) {
			val existing = byManga[group.manga.id]
			if (existing == null) {
				byManga[group.manga.id] = group
			} else {
				val items = LinkedHashMap<Long, BookmarkBackup.Item>()
				for (item in existing.bookmarks) items[item.pageId] = item
				for (item in group.bookmarks) if (!items.containsKey(item.pageId)) items[item.pageId] = item
				byManga[group.manga.id] = BookmarkBackup(existing.manga, items.values.toList())
			}
		}
		return byManga.values.toList()
	}

	/**
	 * Combines several remote snapshots (duplicate Drive files from a first-run race) into one, using
	 * the same per-record rules. Row data is unioned; [SyncConfig] is taken from the higher revision.
	 * Folding is associative enough for this purpose — ties are vanishingly rare across duplicates.
	 */
	fun combine(snapshots: List<SyncSnapshot>): SyncSnapshot? = when {
		snapshots.isEmpty() -> null
		snapshots.size == 1 -> snapshots.single()
		else -> snapshots.reduce(::mergeSnapshots)
	}

	private fun mergeSnapshots(a: SyncSnapshot, b: SyncSnapshot): SyncSnapshot {
		val config = when {
			a.config == null -> b.config
			b.config == null -> a.config
			b.config.revision > a.config.revision -> b.config
			else -> a.config
		}
		return SyncSnapshot(
			deviceId = a.deviceId,
			syncedAt = maxOf(a.syncedAt, b.syncedAt),
			categories = mergeCategories(a.categories, b.categories),
			favourites = mergeFavourites(a.favourites, b.favourites),
			history = mergeHistory(a.history, b.history),
			bookmarks = mergeBookmarks(a.bookmarks, b.bookmarks),
			scrobblings = mergeScrobblings(a.scrobblings, b.scrobblings),
			tracks = mergeTracks(a.tracks, b.tracks),
			stats = mergeStats(a.stats, b.stats),
			config = config,
		)
	}

	private inline fun <T, K> mergeBy(
		local: List<T>,
		remote: List<T>,
		key: (T) -> K,
		timestamp: (T) -> Long,
	): List<T> {
		val merged = LinkedHashMap<K, T>(local.size + remote.size)
		for (item in local) {
			merged[key(item)] = item
		}
		for (item in remote) {
			val k = key(item)
			val existing = merged[k]
			if (existing == null || timestamp(item) > timestamp(existing)) {
				merged[k] = item
			}
		}
		return merged.values.toList()
	}
}
