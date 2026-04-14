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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.SampleData
import org.koitharu.kotatsu.backup.MihonBackupManager
import org.koitharu.kotatsu.backup.model.MihonBackup
import org.koitharu.kotatsu.backup.model.MihonBackupCategory
import org.koitharu.kotatsu.backup.model.MihonBackupChapter
import org.koitharu.kotatsu.backup.model.MihonBackupManga
import org.koitharu.kotatsu.backup.model.MihonBackupSource
import org.koitharu.kotatsu.backup.model.MihonBackupTracking
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.parsers.util.longHashCode
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

		val mangaId = "MIHON_123:https://fixture.example/manga/1".longHashCode()
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
		assertTrue(report.skippedItems.any { it.contains("99") })
	}

	@Test
	fun restoreMihonFixture_mapsAllCategoriesToMihonCategory() = runTest {
		val fixture = createFixture(
			trackerItems = emptyList(),
			categories = listOf(
				MihonBackupCategory(name = "Default", id = 1, order = 0),
				MihonBackupCategory(name = "Read later", id = 2, order = 1),
			),
			mangaCategoryIds = listOf(1, 2),
		)
		val report = backupManager.restoreBackup(writeFixture(fixture))

		val mangaId = "MIHON_123:https://fixture.example/manga/1".longHashCode()
		val categoryIds = database.getFavouritesDao().findCategoriesIds(mangaId)
		val restoredTitles = database.getFavouriteCategoriesDao()
			.findAll()
			.filter { it.categoryId.toLong() in categoryIds }
			.map { it.title }
			.toSet()

		assertEquals(setOf("mihon"), restoredTitles)
		assertEquals(1, report.restoredMangaCount)
	}

	@Test
	fun restoreMihonFixture_restoresProgressWithoutExplicitHistoryBlock() = runTest {
		val fixture = createFixture(trackerItems = emptyList())
		backupManager.restoreBackup(writeFixture(fixture))

		val mangaId = "MIHON_123:https://fixture.example/manga/1".longHashCode()
		val history = database.getHistoryDao().find(mangaId)

		assertNotNull(history)
		assertTrue((history?.percent ?: 0f) > 0f)
	}

	@Test
	fun restoreMihonFixture_createsNewMihonCategoryEachTime() = runTest {
		val fixture = createFixture(trackerItems = emptyList())
		val uri = writeFixture(fixture)

		backupManager.restoreBackup(uri)
		backupManager.restoreBackup(uri)

		val mihonCategoryCount = database.getFavouriteCategoriesDao()
			.findAll()
			.count { it.title == "mihon" }
		assertEquals(2, mihonCategoryCount)
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
					chapters = listOf(
						MihonBackupChapter(
							url = "$mangaUrl/chapter-1",
							name = "Chapter 1",
							read = true,
							bookmark = true,
							lastPageRead = 3,
							chapterNumber = 1f,
						),
					),
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
