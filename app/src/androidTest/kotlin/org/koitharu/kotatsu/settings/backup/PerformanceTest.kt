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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koitharu.kotatsu.backup.MihonBackupManager
import org.koitharu.kotatsu.backup.model.MihonBackup
import org.koitharu.kotatsu.backup.model.MihonBackupCategory
import org.koitharu.kotatsu.backup.model.MihonBackupChapter
import org.koitharu.kotatsu.backup.model.MihonBackupManga
import org.koitharu.kotatsu.backup.model.MihonBackupSource
import org.koitharu.kotatsu.backup.model.MihonBackupTracking
import org.koitharu.kotatsu.core.db.MangaDatabase
import java.io.File
import javax.inject.Inject
import kotlin.system.measureTimeMillis

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PerformanceTest {

	@get:Rule
	var hiltRule = HiltAndroidRule(this)

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
	fun benchmarkRestore() = runTest {
		val fixture = createLargeFixture()
		val uri = writeFixture(fixture)

		val time = measureTimeMillis {
			backupManager.restoreBackup(uri)
		}
		println("BENCHMARK RESTORE TIME: $time ms")
		assertTrue(time > 0)
	}

	private fun writeFixture(backup: MihonBackup): Uri {
		val context = InstrumentationRegistry.getInstrumentation().targetContext
		val file = File.createTempFile("mihon_large_", ".tachibk", context.cacheDir)
		val bytes = ProtoBuf.encodeToByteArray(MihonBackup.serializer(), backup)
		file.outputStream().sink().gzip().buffer().use { sink ->
			sink.write(bytes)
		}
		return Uri.fromFile(file)
	}

	private fun createLargeFixture(): MihonBackup {
		val mangaList = (1..500).map { i ->
			val mangaUrl = "https://fixture.example/manga/$i"
			MihonBackupManga(
				source = 123,
				url = mangaUrl,
				title = "Fixture Manga $i",
				thumbnailUrl = "https://fixture.example/cover$i.jpg",
				favorite = true,
				categories = listOf(1),
				chapters = (1..10).map { j ->
					MihonBackupChapter(
						url = "$mangaUrl/chapter-$j",
						name = "Chapter $j",
						read = j < 5,
						bookmark = j == 2,
						lastPageRead = 3,
						chapterNumber = j.toFloat(),
					)
				},
				tracking = listOf(
					MihonBackupTracking(
						syncId = 1,
						libraryId = 100L + i,
						mediaId = 200L + i,
						status = 1,
						score = 8f,
						lastChapterRead = 4f,
						title = "Fixture Manga $i",
					)
				),
			)
		}
		return MihonBackup(
			backupManga = mangaList,
			backupCategories = listOf(MihonBackupCategory(name = "Default", id = 1, order = 0)),
			backupSources = listOf(MihonBackupSource(name = "Fixture Source", sourceId = 123)),
		)
	}
}
