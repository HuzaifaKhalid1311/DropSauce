package org.koitharu.kotatsu.sync.domain

import org.koitharu.kotatsu.sync.data.model.RTombstone
import org.koitharu.kotatsu.sync.data.model.SyncRecord

/**
 * Pure, order-independent merge of one section across the local state and any number of remote
 * replicas. Per key the highest version wins. Ties resolve deterministically (a deletion first, then
 * the larger content) so every device picks the same winner and they converge.
 *
 * The one asymmetric case: a device joining the cloud for the first time ([isJoining]) adopts the
 * remote side of version-0 ties — state that predates change tracking — instead of pushing its own
 * defaults. Only the joining side does this, so two devices can't keep swapping values.
 */
object ReplicaMerger {

	class Changes<T : SyncRecord>(
		/** Remote records that must be written locally. */
		val upserts: List<T>,
		/** Remote deletions of records that are live locally. */
		val deletes: List<RTombstone>,
	) {
		val isEmpty get() = upserts.isEmpty() && deletes.isEmpty()
	}

	fun <T : SyncRecord> merge(
		local: Collection<T>,
		localTombstones: Collection<RTombstone>,
		remotes: List<Pair<Collection<T>, Collection<RTombstone>>>,
		isJoining: Boolean = false,
	): Changes<T> {
		val localByKey = HashMap<String, SyncRecord>(local.size + localTombstones.size)
		for (t in localTombstones) localByKey[t.key] = t
		for (r in local) localByKey[r.key] = r
		val best = HashMap<String, SyncRecord>(localByKey)
		for ((records, tombstones) in remotes) {
			for (candidate in tombstones) offer(best, candidate, localByKey, isJoining)
			for (candidate in records) offer(best, candidate, localByKey, isJoining)
		}
		val upserts = ArrayList<T>()
		val deletes = ArrayList<RTombstone>()
		for ((key, winner) in best) {
			val current = localByKey[key]
			if (winner === current || winner == current) continue
			if (winner is RTombstone) {
				if (current != null && current !is RTombstone) deletes += winner
			} else {
				@Suppress("UNCHECKED_CAST")
				upserts += winner as T
			}
		}
		return Changes(upserts, deletes)
	}

	private fun offer(
		best: HashMap<String, SyncRecord>,
		candidate: SyncRecord,
		localByKey: Map<String, SyncRecord>,
		isJoining: Boolean,
	) {
		val current = best[candidate.key]
		if (current == null || beats(candidate, current, isJoining && current === localByKey[candidate.key])) {
			best[candidate.key] = candidate
		}
	}

	/** Whether remote [candidate] should replace [current]; [adoptUnknown] makes a v0 tie go remote. */
	internal fun beats(candidate: SyncRecord, current: SyncRecord, adoptUnknown: Boolean = false): Boolean = when {
		candidate.v != current.v -> candidate.v > current.v
		candidate == current -> false
		adoptUnknown && candidate.v == 0L -> true
		(candidate is RTombstone) != (current is RTombstone) -> candidate is RTombstone
		else -> candidate.toString() > current.toString()
	}
}
