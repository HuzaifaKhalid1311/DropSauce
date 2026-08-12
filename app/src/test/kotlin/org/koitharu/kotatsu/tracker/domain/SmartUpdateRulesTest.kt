package org.koitharu.kotatsu.tracker.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.model.MangaState

class SmartUpdateRulesTest {

	@Test
	fun `no rules never skips`() {
		assertNull(smartUpdateSkipReason(emptySet(), MangaState.FINISHED, hasHistory = false, newChapters = 3))
	}

	@Test
	fun `each rule only fires on its own condition`() {
		val completed = setOf(AppSettings.SMART_UPDATE_SKIP_COMPLETED)
		assertEquals(
			R.string.smart_update_skip_completed,
			smartUpdateSkipReason(completed, MangaState.FINISHED, hasHistory = true, newChapters = 0),
		)
		assertNull(smartUpdateSkipReason(completed, MangaState.ONGOING, hasHistory = false, newChapters = 5))

		val unstarted = setOf(AppSettings.SMART_UPDATE_SKIP_UNSTARTED)
		assertEquals(
			R.string.smart_update_skip_unstarted,
			smartUpdateSkipReason(unstarted, MangaState.ONGOING, hasHistory = false, newChapters = 0),
		)
		assertNull(smartUpdateSkipReason(unstarted, MangaState.ONGOING, hasHistory = true, newChapters = 0))

		val unread = setOf(AppSettings.SMART_UPDATE_SKIP_UNREAD)
		assertEquals(
			R.string.smart_update_skip_unread,
			smartUpdateSkipReason(unread, MangaState.ONGOING, hasHistory = true, newChapters = 1),
		)
		assertNull(smartUpdateSkipReason(unread, MangaState.ONGOING, hasHistory = true, newChapters = 0))
	}

	@Test
	fun `partially read entry with no new chapters is still checked`() {
		// the bug this replaces: reading progress alone must not mute an ongoing series
		val all = setOf(
			AppSettings.SMART_UPDATE_SKIP_COMPLETED,
			AppSettings.SMART_UPDATE_SKIP_UNSTARTED,
			AppSettings.SMART_UPDATE_SKIP_UNREAD,
		)
		assertNull(smartUpdateSkipReason(all, MangaState.ONGOING, hasHistory = true, newChapters = 0))
	}
}
