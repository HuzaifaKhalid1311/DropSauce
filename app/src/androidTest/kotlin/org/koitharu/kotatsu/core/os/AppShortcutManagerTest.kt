package org.koitharu.kotatsu.core.os

import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.history.data.HistoryRepository
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppShortcutManagerTest {

	@get:Rule
	var hiltRule = HiltAndroidRule(this)

	@Inject
	lateinit var historyRepository: HistoryRepository

	@Inject
	lateinit var appShortcutManager: AppShortcutManager

	@Inject
	lateinit var database: MangaDatabase

	@Before
	fun setUp() {
		hiltRule.inject()
		database.clearAllTables()
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		context.getSystemService<ShortcutManager>()?.removeAllDynamicShortcuts()
	}

	@Test
	fun testUpdateShortcuts() = runTest {
		awaitUpdate()
		assertTrue(getShortcuts().isEmpty())
		historyRepository.addOrUpdate(
			manga = SampleData.mangaDetails,
			chapterId = SampleData.chapter.id,
			page = 4,
			scroll = 2,
			percent = 0.3f,
			force = false,
		)
		awaitUpdate()

		val shortcuts = getShortcuts()
		assertEquals(1, shortcuts.size)
	}

	@Test
	fun testPositionUpdateKeepsShortcuts() = runTest {
		// no cover: the fixture link never loads, and a placeholder icon is retried on every update
		val manga = SampleData.mangaDetails.copy(coverUrl = null)
		historyRepository.addOrUpdate(
			manga = manga,
			chapterId = SampleData.chapter.id,
			page = 4,
			scroll = 2,
			percent = 0.3f,
			force = false,
		)
		awaitUpdate()
		val changedAt = getShortcuts().single().lastChangedTimestamp
		historyRepository.updatePosition(
			manga = manga,
			chapterId = SampleData.chapter.id,
			page = 5,
			scroll = 0,
			percent = 0.4f,
		)
		awaitUpdate()

		// a re-sent shortcut would leave another icon file in the system folder
		assertEquals(changedAt, getShortcuts().single().lastChangedTimestamp)
	}

	private fun getShortcuts(): List<ShortcutInfo> {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val manager = checkNotNull(context.getSystemService<ShortcutManager>())
		return manager.dynamicShortcuts
	}

	private suspend fun awaitUpdate() {
		appShortcutManager.onInvalidated(emptySet())
		appShortcutManager.await()
	}
}
