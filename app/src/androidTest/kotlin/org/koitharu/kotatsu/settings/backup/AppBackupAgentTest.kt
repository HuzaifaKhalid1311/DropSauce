package org.koitharu.kotatsu.settings.backup

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.coroutines.test.runTest
import okio.buffer
import okio.gzip
import okio.sink
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.backup.MihonBackupManager
import org.koitharu.kotatsu.backup.model.MihonBackup
import org.koitharu.kotatsu.backup.model.MihonBackupCategory
import org.koitharu.kotatsu.backup.model.MihonBackupChapter
import org.koitharu.kotatsu.backup.model.MihonBackupHistory
import org.koitharu.kotatsu.backup.model.MihonBackupManga
import org.koitharu.kotatsu.backup.model.MihonBackupSource
import org.koitharu.kotatsu.backup.model.MihonBackupTracking
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.mihon.model.mihonChapterId
import org.koitharu.kotatsu.mihon.model.mihonMangaId
import java.io.File
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppBackupAgentTest {

	@get:Rule
	var hiltRule = HiltAndroidRule(this)

	@Inject
	lateinit var historyRepository: HistoryRepository

	@Inject
	lateinit var favouritesRepository: FavouritesRepository

	@Inject
	lateinit var backupManager: MihonBackupManager

	@Inject
	lateinit var database: MangaDatabase

	@Before
	fun setUp() {
		hiltRule.inject()
		database.clearAllTables()
	}

	@Test
	fun backupAndRestore() = runTest {
		val fixture = createFixture(trackerItems = emptyList())
		backupManager.restoreBackup(writeFixture(fixture))

		assertTrue(favouritesRepository.getAllManga().isNotEmpty())
		assertTrue(historyRepository.getLastOrNull() != null)
	}

	@Test
	fun restoreMihonFixture_trackerStatusPermutations() = runTest {
		val fixture = createFixture(
			trackerItems = listOf(
				1 to 1,
				1 to 2,
				1 to 3,
				1 to 4,
				1 to 5,
				1 to 6,
				1 to 0,
			),
		)
		val uri = writeFixture(fixture)
		val report = backupManager.restoreBackup(uri)

		val mangaId = mihonMangaId("MIHON_123", "https://fixture.example/manga/1")
		val tracks = database.getScrobblingDao().findAll(mangaId)
		assertEquals(7, tracks.size)
		assertTrue(report.missingTrackers.isEmpty())
		assertNotNull(database.getHistoryDao().find(mangaId))
	}

	@Test
	fun restoreMihonFixture_missingTrackerDiagnostics() = runTest {
		val fixture = createFixture(
			trackerItems = listOf(
				1 to 2,
				99 to 2,
			),
		)
		val uri = writeFixture(fixture)

		val analysis = backupManager.analyzeBackup(uri)
		assertTrue(99 in analysis.missingTrackers)

		val report = backupManager.restoreBackup(uri)
		assertTrue(99 in report.missingTrackers)
	}

	@Test
	fun restoreMihonFixture_restoresMappedCategories() = runTest {
		val fixture = createFixture(
			trackerItems = emptyList(),
			categories = listOf(
				MihonBackupCategory(name = "Default", id = 1, order = 0),
				MihonBackupCategory(name = "Read later", id = 2, order = 1),
			),
			mangaCategoryIds = listOf(0, 1),
		)
		val report = backupManager.restoreBackup(writeFixture(fixture))

		val mangaId = mihonMangaId("MIHON_123", "https://fixture.example/manga/1")
		val categoryIds = database.getFavouritesDao().findCategoriesIds(mangaId)
		val restoredTitles = database.getFavouriteCategoriesDao()
			.findAll()
			.filter { it.categoryId.toLong() in categoryIds }
			.map { it.title }
			.toSet()

		assertEquals(setOf("Default", "Read later"), restoredTitles)
		assertEquals(1, report.restoredMangaCount)
	}

	@Test
	fun restoreMihonFixture_restoresProgressWithoutExplicitHistoryBlock() = runTest {
		val fixture = createFixture(trackerItems = emptyList())
		backupManager.restoreBackup(writeFixture(fixture))

		val mangaId = mihonMangaId("MIHON_123", "https://fixture.example/manga/1")
		val history = database.getHistoryDao().find(mangaId)

		assertNotNull(history)
		assertTrue((history?.percent ?: 0f) > 0f)
	}

	@Test
	fun restoreMihonFixture_ignoresResetHistoryForUnreadManga() = runTest {
		val mangaUrl = "https://fixture.example/manga/1"
		val chapters = listOf(
			MihonBackupChapter(
				url = "$mangaUrl/chapter-1",
				name = "Chapter 1",
				sourceOrder = 1,
			),
			MihonBackupChapter(
				url = "$mangaUrl/chapter-2",
				name = "Chapter 2",
				sourceOrder = 0,
			),
		)
		val fixture = createFixture(
			trackerItems = emptyList(),
			chapters = chapters,
			history = listOf(MihonBackupHistory(chapters.last().url, lastRead = 0)),
		)

		backupManager.restoreBackup(writeFixture(fixture))

		val mangaId = mihonMangaId("MIHON_123", mangaUrl)
		assertNull(database.getHistoryDao().find(mangaId))
	}

	@Test
	fun restoreMihonFixture_prefersHistoryOverLaterSampledChapter() = runTest {
		val mangaUrl = "https://fixture.example/manga/1"
		val chapters = listOf(
			MihonBackupChapter(
				url = "$mangaUrl/chapter-1",
				name = "Chapter 1",
				read = true,
				chapterNumber = 1f,
				sourceOrder = 2,
			),
			MihonBackupChapter(
				url = "$mangaUrl/chapter-2",
				name = "Chapter 2",
				read = true,
				chapterNumber = 2f,
				sourceOrder = 1,
			),
			MihonBackupChapter(
				url = "$mangaUrl/chapter-3",
				name = "Chapter 3",
				lastPageRead = 3,
				chapterNumber = 3f,
				sourceOrder = 0,
			),
		)
		val fixture = createFixture(
			trackerItems = emptyList(),
			chapters = chapters,
			history = listOf(MihonBackupHistory(chapters[1].url, lastRead = 1_000)),
		)

		backupManager.restoreBackup(writeFixture(fixture))

		val mangaId = mihonMangaId("MIHON_123", mangaUrl)
		val history = database.getHistoryDao().find(mangaId)
		assertEquals(mihonChapterId("MIHON_123", chapters[1].url), history?.chapterId)
		assertEquals(2f / 3f, history?.percent)
	}

	@Test
	fun restoreMihonFixture_reusesExistingCategoryByTitle() = runTest {
		val fixture = createFixture(trackerItems = emptyList())
		val uri = writeFixture(fixture)

		backupManager.restoreBackup(uri)
		backupManager.restoreBackup(uri)

		val defaultCategoryCount = database.getFavouriteCategoriesDao()
			.findAll()
			.count { it.title == "Default" }
		assertEquals(1, defaultCategoryCount)
	}

	private fun writeFixture(backup: MihonBackup): Uri {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val file = File.createTempFile("mihon_fixture_", ".tachibk", context.cacheDir)
		val bytes = ProtoBuf.encodeToByteArray(MihonBackup.serializer(), backup)
		file.outputStream().sink().gzip().buffer().use { sink ->
			sink.write(bytes)
		}
		return Uri.fromFile(file)
	}

	private fun createFixture(
		trackerItems: List<Pair<Int, Int>>,
		categories: List<MihonBackupCategory> = listOf(MihonBackupCategory(name = "Default", id = 1, order = 0)),
		mangaCategoryIds: List<Long> = listOf(1),
		chapters: List<MihonBackupChapter>? = null,
		history: List<MihonBackupHistory> = emptyList(),
	): MihonBackup {
		val mangaUrl = "https://fixture.example/manga/1"
		return MihonBackup(
			backupManga = listOf(
				MihonBackupManga(
					source = 123,
					url = mangaUrl,
					title = "Fixture Manga",
					thumbnailUrl = "https://fixture.example/cover.jpg",
					favorite = true,
					categories = mangaCategoryIds,
					chapters = chapters ?: listOf(
						MihonBackupChapter(
							url = "$mangaUrl/chapter-1",
							name = "Chapter 1",
							read = true,
							bookmark = true,
							lastPageRead = 3,
							chapterNumber = 1f,
						),
					),
					history = history,
					tracking = trackerItems.mapIndexed { index, (syncId, status) ->
						MihonBackupTracking(
							syncId = syncId,
							libraryId = (100 + index).toLong(),
							mediaId = (200 + index).toLong(),
							status = status,
							score = 8f,
							lastChapterRead = 1f,
							title = "Fixture Manga",
						)
					},
				),
			),
			backupCategories = categories,
			backupSources = listOf(MihonBackupSource(name = "Fixture Source", sourceId = 123)),
		)
	}
}
