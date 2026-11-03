package org.koitharu.kotatsu.details.domain

import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.withMergedBranches
import org.koitharu.kotatsu.core.model.withSortedChapters
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

class ProgressUpdateUseCase @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val database: MangaDatabase,
	private val localMangaRepository: LocalMangaRepository,
	private val networkState: NetworkState,
	private val mangaDataRepository: MangaDataRepository,
	private val settings: AppSettings,
) {

	suspend operator fun invoke(manga: Manga): Float {
		val history = database.getHistoryDao().find(manga.id) ?: return PROGRESS_NONE
		val seed = if (manga.isLocal) {
			localMangaRepository.getRemoteManga(manga) ?: manga
		} else {
			manga
		}
		if (!seed.isLocal && !networkState.value) {
			return history.percent
		}
		val repo = mangaRepositoryFactory.create(seed.source)
		val details = if (manga.source != seed.source || seed.chapters.isNullOrEmpty()) {
			repo.getDetails(seed)
		} else {
			seed
		}.withMergedBranches(mangaDataRepository.getScanlatorMerge(seed.id))
			.withSortedChapters(settings.getChaptersSortOrder(seed.id))
		val cachedChapterUrl = database.getChaptersDao().findAll(manga.id)
			.firstOrNull { it.chapterId == history.chapterId }
			?.url
		val chapter = details.findChapterById(history.chapterId)
			?: cachedChapterUrl?.let { url ->
				details.chapters?.firstOrNull { it.url == url }
			}
			?: return history.percent
		val chapters = details.getChapters(chapter.branch)
		val chaptersCount = chapters.size
		if (chaptersCount == 0) {
			return history.percent
		}
		val chapterIndex = chapters.indexOfFirst { x -> x.id == chapter.id }
		if (chapterIndex < 0) {
			return history.percent
		}
		val result = (chapterIndex + 1) / chaptersCount.toFloat()
		if (result != history.percent) {
			database.getHistoryDao().update(
				history.copy(
					chapterId = chapter.id,
					percent = result,
				),
			)
		}
		return result
	}
}
