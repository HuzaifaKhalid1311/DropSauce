package org.koitharu.kotatsu.history.domain

import dagger.Reusable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.koitharu.kotatsu.core.model.withMergedBranches
import org.koitharu.kotatsu.core.model.withSortedChapters
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

@Reusable
class MarkAsReadUseCase @Inject constructor(
	private val historyRepository: HistoryRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val mangaDataRepository: MangaDataRepository,
	private val settings: AppSettings,
) {

	/**
	 * Read = last page of the last chapter; unread = 0% at the start of the first chapter, kept in history.
	 * Either way the title keeps its place and date in History; a title with no history already is unread.
	 */
	suspend operator fun invoke(manga: Manga, isRead: Boolean) {
		if (!isRead && historyRepository.getOne(manga) == null) {
			return
		}
		val repo = mangaRepositoryFactory.create(manga.source)
		val details = if (manga.chapters.isNullOrEmpty()) {
			repo.getDetails(manga)
		} else {
			manga
		}.withMergedBranches(mangaDataRepository.getScanlatorMerge(manga.id))
			.withSortedChapters(settings.getChaptersSortOrder(manga.id))
		val chapters = details.chapters ?: return
		val chapter = (if (isRead) chapters.lastOrNull() else chapters.firstOrNull()) ?: return
		historyRepository.addOrUpdate(
			manga = details,
			chapterId = chapter.id,
			page = if (isRead) maxOf(0, repo.getPages(details, chapter).lastIndex) else 0,
			scroll = 0,
			percent = if (isRead) 1f else 0f,
			force = true,
			// Like Mihon, unread never sends trackers back to chapter 1.
			notify = isRead,
			keepDate = true,
		)
	}

	suspend operator fun invoke(manga: Collection<Manga>, isRead: Boolean) {
		when (manga.size) {
			0 -> Unit
			1 -> invoke(manga.first(), isRead)
			else -> supervisorScope {
				manga.map {
					launch {
						invoke(it, isRead)
					}
				}.joinAll()
			}
		}
	}
}
