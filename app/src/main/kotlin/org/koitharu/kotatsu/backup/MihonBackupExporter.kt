package org.koitharu.kotatsu.backup

import android.content.Context
import android.net.Uri
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.serialization.protobuf.ProtoBuf
import okio.buffer
import okio.gzip
import okio.sink
import org.koitharu.kotatsu.backup.model.MihonBackup
import org.koitharu.kotatsu.backup.model.MihonBackupCategory
import org.koitharu.kotatsu.backup.model.MihonBackupChapter
import org.koitharu.kotatsu.backup.model.MihonBackupHistory
import org.koitharu.kotatsu.backup.model.MihonBackupManga
import org.koitharu.kotatsu.backup.model.MihonBackupSource
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.ChapterEntity
import org.koitharu.kotatsu.core.db.entity.MangaEntity
import org.koitharu.kotatsu.core.db.entity.TagEntity
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Writes favourites and reading progress into a Mihon-compatible `.tachibk` file — the exact
 * format [MihonBackupManager] reads back, so the two stay symmetric.
 *
 * Only titles from installed Mihon extensions are exported: everything else (local files, EPUBs,
 * novel plugins) has no Mihon source id, and Mihon would import it as a dead entry.
 */
@Reusable
class MihonBackupExporter @Inject constructor(
	@ApplicationContext private val context: Context,
	private val db: MangaDatabase,
	private val mihonExtensionManager: MihonExtensionManager,
) {

	/** Number of titles written, so the caller can tell the user whether anything was skipped. */
	data class Report(
		val exportedCount: Int,
		val skippedCount: Int,
	)

	suspend fun export(uri: Uri): Report = withContext(Dispatchers.IO) {
		val (backup, skipped) = buildBackup()
		val payload = ProtoBuf.encodeToByteArray(MihonBackup.serializer(), backup)
		val output = context.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open $uri")
		output.use { stream ->
			stream.sink().gzip().buffer().use { it.write(payload) }
		}
		Report(
			exportedCount = backup.backupManga.size,
			skippedCount = skipped,
		)
	}

	private suspend fun buildBackup(): Pair<MihonBackup, Int> {
		var skipped = 0
		val categories = db.getFavouriteCategoriesDao().findAll()
		// Mihon references categories by their `order`, not by id — see the restore side's
		// CategoryResolver. Emitting the list position as the order keeps both ends in step.
		val categoryOrderById: Map<Long, Long> = categories
			.mapIndexed { index, category -> category.categoryId.toLong() to index.toLong() }
			.toMap()

		val records = HashMap<Long, Record>()
		db.getFavouritesDao().dump().toList().forEach { favourite ->
			if (favourite.favourite.deletedAt != 0L) return@forEach
			val record = records.getOrPut(favourite.manga.id) { Record(favourite.manga, favourite.tags) }
			record.isFavourite = true
			val createdAt = favourite.favourite.createdAt
			if (createdAt > 0 && (record.dateAdded == 0L || createdAt < record.dateAdded)) {
				record.dateAdded = createdAt
			}
			val categoryOrder = categoryOrderById[favourite.favourite.categoryId]
			if (categoryOrder != null) {
				record.categories.add(categoryOrder)
			}
		}
		db.getHistoryDao().dump().toList().forEach { entry ->
			val record = records.getOrPut(entry.history.mangaId) { Record(entry.manga, entry.tags) }
			record.history = entry.history
		}

		val usedSources = HashMap<Long, String>()
		val manga = records.values.mapNotNull { record ->
			val source = mihonExtensionManager.getMihonMangaSourceByName(record.manga.source)
			if (source == null) {
				skipped++
				return@mapNotNull null
			}
			usedSources[source.sourceId] = source.displayName
			toBackupManga(record, source.sourceId)
		}

		val backup = MihonBackup(
			backupManga = manga,
			backupCategories = categories.mapIndexed { index, category ->
				MihonBackupCategory(
					name = category.title,
					order = index.toLong(),
					id = category.categoryId.toLong(),
				)
			},
			backupSources = usedSources.map { (id, name) -> MihonBackupSource(name = name, sourceId = id) },
		)
		return backup to skipped
	}

	private suspend fun toBackupManga(record: Record, sourceId: Long): MihonBackupManga {
		val manga = record.manga
		val allChapters = db.getChaptersDao().findAll(manga.id)
		val currentIndex = record.history?.let { history ->
			allChapters.indexOfFirst { it.chapterId == history.chapterId }
		} ?: -1
		val readChapters = if (currentIndex >= 0) allChapters.take(currentIndex + 1) else emptyList()
		val lastRead = record.history?.updatedAt ?: 0L

		return MihonBackupManga(
			source = sourceId,
			url = manga.url,
			title = manga.title,
			author = manga.authors,
			description = manga.description,
			genre = record.tags.map { it.title },
			thumbnailUrl = manga.coverUrl,
			dateAdded = record.dateAdded,
			chapters = readChapters.mapIndexed { index, chapter ->
				chapter.toBackupChapter(
					// Mihon numbers chapters newest-first, Kotatsu oldest-first.
					sourceOrder = (allChapters.size - 1 - index).toLong(),
					isRead = index < currentIndex,
					lastPageRead = if (index == currentIndex) record.history?.page?.toLong() ?: 0L else 0L,
					lastRead = if (index == currentIndex) lastRead else 0L,
				)
			},
			categories = record.categories.toList(),
			favorite = record.isFavourite,
			history = readChapters.getOrNull(currentIndex)?.let {
				listOf(MihonBackupHistory(url = it.url, lastRead = lastRead))
			}.orEmpty(),
			lastModifiedAt = lastRead,
			initialized = true,
		)
	}

	private fun ChapterEntity.toBackupChapter(
		sourceOrder: Long,
		isRead: Boolean,
		lastPageRead: Long,
		lastRead: Long,
	) = MihonBackupChapter(
		url = url,
		name = title,
		scanlator = scanlator,
		read = isRead,
		lastPageRead = lastPageRead,
		dateUpload = uploadDate,
		chapterNumber = number,
		sourceOrder = sourceOrder,
		lastModifiedAt = lastRead,
	)

	private class Record(
		val manga: MangaEntity,
		val tags: List<TagEntity>,
	) {
		val categories = LinkedHashSet<Long>()
		var isFavourite = false
		var dateAdded = 0L
		var history: HistoryEntity? = null
	}

	companion object {

		const val MIME_TYPE = "application/octet-stream"

		fun generateFileName(): String = "dropsauce_" +
			SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()) +
			".tachibk"
	}
}
