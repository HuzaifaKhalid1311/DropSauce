package org.koitharu.kotatsu.download.domain

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.MigrateUseCase
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.toFileNameSafe
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.MangaIndex
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class DownloadsIndexRebuildUseCase @Inject constructor(
	private val favouritesRepository: FavouritesRepository,
	private val storageManager: LocalStorageManager,
	private val mangaDataRepository: MangaDataRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val migrateUseCase: MigrateUseCase,
	private val mihonExtensionManager: MihonExtensionManager,
	private val localMangaIndex: LocalMangaIndex,
) {

	private enum class MigrationChoice {
		MIGRATE,
		KEEP
	}

	suspend fun showMigrationDialog(
		context: Context,
		mangaTitle: String,
		localSource: String,
		favoriteSource: String,
	): Boolean = withContext(Dispatchers.Main) {
		val choice = suspendCancellableCoroutine { continuation ->
			val dialog = buildAlertDialog(context) {
				setTitle(R.string.rebuild_source_mismatch_title)
				setMessage(context.getString(R.string.rebuild_source_mismatch_message, mangaTitle, localSource, favoriteSource))
				setPositiveButton(context.getString(R.string.rebuild_migrate_to, localSource)) { _, _ ->
					if (continuation.isActive) continuation.resume(MigrationChoice.MIGRATE)
				}
				setNegativeButton(context.getString(R.string.rebuild_keep_on, favoriteSource)) { _, _ ->
					if (continuation.isActive) continuation.resume(MigrationChoice.KEEP)
				}
				setOnCancelListener {
					if (continuation.isActive) continuation.resume(MigrationChoice.KEEP)
				}
			}
			dialog.show()
			continuation.invokeOnCancellation {
				dialog.dismiss()
			}
		}
		choice == MigrationChoice.MIGRATE
	}

	private fun getNormalizedSourceName(sourceName: String): String {
		val clean = sourceName.removePrefix("MIHON_").substringBefore(':')
		val sourceId = clean.toLongOrNull()
		if (sourceId != null) {
			val mihonSource = mihonExtensionManager.getMihonMangaSourceById(sourceId)
			if (mihonSource != null) {
				return mihonSource.displayName.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
			}
		}
		return clean.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
	}

	private fun isSameOrSimilarSource(sourceA: String, sourceB: String): Boolean {
		if (sourceA == sourceB) return true
		val normA = getNormalizedSourceName(sourceA)
		val normB = getNormalizedSourceName(sourceB)
		return normA.isNotEmpty() && normB.isNotEmpty() && (normA == normB || normA.contains(normB) || normB.contains(normA))
	}

	private fun extractChapterNumber(filename: String): Float? {
		val name = filename.substringBeforeLast('.')
		val regexChapter = Regex("(?i)\\b(?:c|ch|chap|chapter)\\.?\\s*([0-9]+(?:\\.[0-9]+)?)")
		regexChapter.find(name)?.groupValues?.get(1)?.toFloatOrNull()?.let {
			return it
		}
		val regexNumber = Regex("([0-9]+(?:\\.[0-9]+)?)")
		val matches = regexNumber.findAll(name).toList()
		if (matches.isNotEmpty()) {
			matches.last().groupValues[1].toFloatOrNull()?.let {
				return it
			}
		}
		return null
	}

	private fun matchChapters(
		mangaFolder: File,
		chapters: List<MangaChapter>,
		oldIndexJson: JSONObject?,
	): Map<IndexedValue<MangaChapter>, String> {
		val matched = mutableMapOf<IndexedValue<MangaChapter>, String>()
		val remainingChapters = chapters.mapIndexed { idx, ch -> IndexedValue(idx, ch) }.toMutableList()
		val files = mangaFolder.listFiles()?.filter {
			val ext = it.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
			it.isFile && (ext == "cbz" || ext == "zip")
		}.orEmpty()

		if (oldIndexJson != null) {
			val oldChaptersJson = oldIndexJson.optJSONObject("chapters")
			if (oldChaptersJson != null) {
				val keys = oldChaptersJson.keys()
				while (keys.hasNext()) {
					val key = keys.next()
					val chJson = oldChaptersJson.getJSONObject(key)
					val fileName = chJson.optString("file").nullIfEmpty() ?: continue
					val file = File(mangaFolder, fileName)
					if (!file.exists()) continue

					val number = chJson.optDouble("number", Double.NaN)
					val name = chJson.optString("name").nullIfEmpty()
					val branch = chJson.optString("branch").nullIfEmpty()

					val match = remainingChapters.find {
						(!number.isNaN() && it.value.number == number.toFloat() && (branch == null || it.value.branch == branch))
					} ?: remainingChapters.find {
						(!number.isNaN() && it.value.number == number.toFloat())
					} ?: remainingChapters.find {
						(name != null && it.value.title?.equals(name, ignoreCase = true) == true)
					}

					if (match != null) {
						matched[match] = fileName
						remainingChapters.remove(match)
					}
				}
			}
		}

		val remainingFiles = files.filter { it.name !in matched.values }
		for (file in remainingFiles) {
			val nameWithoutExt = file.name.substringBeforeLast('.')
			val indexPart = nameWithoutExt.substringBefore('_').toIntOrNull() ?: nameWithoutExt.toIntOrNull()
			if (indexPart != null) {
				val match = remainingChapters.find { it.index == indexPart }
				if (match != null) {
					matched[match] = file.name
					remainingChapters.remove(match)
					continue
				}
			}

			val numberPart = extractChapterNumber(file.name)
			if (numberPart != null) {
				val match = remainingChapters.find { it.value.number == numberPart }
				if (match != null) {
					matched[match] = file.name
					remainingChapters.remove(match)
					continue
				}
			}
		}
		return matched
	}

	suspend fun execute(context: Context, onMigrationNeeded: suspend (mangaTitle: String, localSource: String, favoriteSource: String) -> Boolean): Int {
		var updatedCount = 0
		val favorites = favouritesRepository.getAllManga()
		val roots = storageManager.getReadableDirs()
		for (favorite in favorites) {
			val safeTitle = favorite.title.toFileNameSafe()
			for (root in roots) {
				val mangaFolder = File(root, safeTitle)
				if (mangaFolder.isDirectory) {
					val indexFile = File(mangaFolder, "index.json")
					val oldIndexJson = if (indexFile.isFile) {
						runCatching { JSONObject(indexFile.readText()) }.getOrNull()
					} else {
						null
					}

					var currentFavorite = favorite
					var skipFolder = false

					if (oldIndexJson != null) {
						val oldId = oldIndexJson.optLong("id", 0L)
						val oldSource = oldIndexJson.optString("source").nullIfEmpty()
						if (oldId != 0L && oldId != favorite.id && oldSource != null) {
							// Conflict detected!
							if (isSameOrSimilarSource(oldSource, favorite.source.name)) {
								// Auto resolve by overwriting index with favorite's metadata
								// Do nothing here, just keep currentFavorite
							} else {
								// Totally different source: prompt user
								val localMangaInfo = runCatching { LocalMangaParser(mangaFolder).getMangaInfo() }.getOrNull()
								if (localMangaInfo != null) {
									val shouldMigrate = onMigrationNeeded(
										favorite.title,
										localMangaInfo.source.name,
										favorite.source.name,
									)
									if (shouldMigrate) {
										migrateUseCase(oldManga = favorite, newManga = localMangaInfo)
										// After migration, the favorite entry in DropSauce uses localMangaInfo's ID & source
										currentFavorite = localMangaInfo
									} else {
										skipFolder = true
									}
								} else {
									skipFolder = true
								}
							}
						}
					}

					if (skipFolder) continue

					// Fetch or retrieve chapters
					var chapters = mangaDataRepository.findMangaById(currentFavorite.id, withChapters = true)?.chapters.orEmpty()
					if (chapters.isEmpty()) {
						runCatchingCancellable {
							val repo = mangaRepositoryFactory.create(currentFavorite.source)
							val remote = repo.getDetails(currentFavorite)
							mangaDataRepository.storeManga(remote, replaceExisting = true, detailsFetched = true)
							chapters = remote.chapters.orEmpty()
						}.onFailure {
							it.printStackTraceDebug()
						}
					}

					val matched = matchChapters(mangaFolder, chapters, oldIndexJson)

					val newIndex = MangaIndex(null)
					newIndex.setMangaInfo(currentFavorite)

					val oldCoverEntry = oldIndexJson?.optString("cover_entry")?.nullIfEmpty()
					val coverName = when {
						oldCoverEntry != null && File(mangaFolder, oldCoverEntry).exists() -> oldCoverEntry
						File(mangaFolder, "cover.jpg").exists() -> "cover.jpg"
						File(mangaFolder, "cover.png").exists() -> "cover.png"
						else -> null
					}
					if (coverName != null) {
						newIndex.setCoverEntry(coverName)
					}

					for ((chapter, filename) in matched) {
						newIndex.addChapter(chapter, filename)
					}

					runCatching {
						indexFile.writeText(newIndex.toString())
						updatedCount++
					}.onFailure {
						it.printStackTraceDebug()
					}
				}
			}
		}

		localMangaIndex.update()
		return updatedCount
	}
}
