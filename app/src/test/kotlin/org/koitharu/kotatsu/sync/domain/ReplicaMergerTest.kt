package org.koitharu.kotatsu.sync.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.sync.data.model.FeedKeys
import org.koitharu.kotatsu.sync.data.model.RHistory
import org.koitharu.kotatsu.sync.data.model.RTombstone

class ReplicaMergerTest {

	private fun history(mangaId: Long, page: Int, v: Long, deletedAt: Long = 0L) = RHistory(
		mangaId = mangaId,
		createdAt = 1L,
		updatedAt = v,
		chapterId = 10L,
		page = page,
		scroll = 0f,
		percent = 0.5f,
		chaptersCount = 20,
		deletedAt = deletedAt,
		v = v,
	)

	private fun merge(local: List<RHistory>, vararg remotes: List<RHistory>, localDead: List<RTombstone> = emptyList()) =
		ReplicaMerger.merge(local, localDead, remotes.map { it to emptyList<RTombstone>() })

	@Test
	fun newerRemoteWins() {
		val changes = merge(listOf(history(1, 3, v = 100)), listOf(history(1, 7, v = 200)))
		assertEquals(listOf(history(1, 7, v = 200)), changes.upserts)
	}

	@Test
	fun newerLocalIsKept() {
		assertTrue(merge(listOf(history(1, 7, v = 300)), listOf(history(1, 3, v = 200))).isEmpty)
	}

	@Test
	fun identicalIsNoop() {
		assertTrue(merge(listOf(history(1, 3, v = 100)), listOf(history(1, 3, v = 100))).isEmpty)
	}

	@Test
	fun joiningDeviceAdoptsRemoteOnUnknownAgeTie() {
		val changes = ReplicaMerger.merge(
			local = listOf(history(1, 3, v = 0)),
			localTombstones = emptyList(),
			remotes = listOf(listOf(history(1, 9, v = 0)) to emptyList()),
			isJoining = true,
		)
		assertEquals(9, changes.upserts.single().page)
	}

	@Test
	fun unknownAgeTieConvergesWhenNotJoining() {
		// Both devices published differing v0 records before seeing each other: they must agree.
		val a = history(1, 3, v = 0)
		val b = history(1, 9, v = 0)
		val onA = merge(listOf(a), listOf(b)).upserts.firstOrNull() ?: a
		val onB = merge(listOf(b), listOf(a)).upserts.firstOrNull() ?: b
		assertEquals(onA, onB)
	}

	@Test
	fun tieIsResolvedTheSameOnBothDevices() {
		val a = history(1, 3, v = 50)
		val b = history(1, 9, v = 50)
		val onA = merge(listOf(a), listOf(b)).upserts.firstOrNull() ?: a
		val onB = merge(listOf(b), listOf(a)).upserts.firstOrNull() ?: b
		assertEquals(onA, onB)
	}

	@Test
	fun highestAcrossManyRemotesWins() {
		val changes = merge(
			listOf(history(1, 1, v = 10)),
			listOf(history(1, 2, v = 30)),
			listOf(history(1, 3, v = 20)),
		)
		assertEquals(2, changes.upserts.single().page)
	}

	@Test
	fun newerTombstoneDeletesLocal() {
		val changes = ReplicaMerger.merge(
			local = listOf(history(1, 1, v = 10)),
			localTombstones = emptyList(),
			remotes = listOf(emptyList<RHistory>() to listOf(RTombstone("history", "1", v = 20))),
		)
		assertEquals("1", changes.deletes.single().recordKey)
	}

	@Test
	fun olderTombstoneDoesNotDelete() {
		val changes = ReplicaMerger.merge(
			local = listOf(history(1, 1, v = 30)),
			localTombstones = emptyList(),
			remotes = listOf(emptyList<RHistory>() to listOf(RTombstone("history", "1", v = 20))),
		)
		assertTrue(changes.isEmpty)
	}

	@Test
	fun recreatedRemotelyAfterLocalDelete() {
		val changes = merge(
			local = emptyList(),
			listOf(history(1, 4, v = 50)),
			localDead = listOf(RTombstone("history", "1", v = 40)),
		)
		assertEquals(4, changes.upserts.single().page)
	}

	@Test
	fun missingLocallyIsAdded() {
		val changes = merge(emptyList(), listOf(history(2, 1, v = 5)))
		assertEquals(2L, changes.upserts.single().mangaId)
	}

	@Test
	fun feedKeyIgnoresOrderAndFormat() {
		assertEquals(FeedKeys.of(5, "3\n1\n2", ""), FeedKeys.of(5, " 2\n3\n1\n", "whatever"))
		assertEquals(FeedKeys.of(5, "", "Ch 2\nch 1"), FeedKeys.of(5, "", "ch 1\nCh 2"))
		assertEquals(FeedKeys.of(-7, "2\n1", ""), FeedKeys.ofRaw("-7:1\n2"))
		assertEquals(FeedKeys.of(7, "", "Ch 1"), FeedKeys.ofRaw("7:t:Ch 1"))
	}
}
