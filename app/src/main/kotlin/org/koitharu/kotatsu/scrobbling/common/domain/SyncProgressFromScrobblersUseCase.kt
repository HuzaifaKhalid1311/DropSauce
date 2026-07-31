package org.koitharu.kotatsu.scrobbling.common.domain

import dagger.Reusable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@Reusable
class SyncProgressFromScrobblersUseCase @Inject constructor(
	private val historyRepository: HistoryRepository,
	private val settings: AppSettings,
	private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
) {

	suspend operator fun invoke(manga: Manga, branch: String?): Int? {
		if (!settings.isScrobblingProgressSyncEnabled || settings.isIncognitoModeEnabled(manga.isNsfw())) {
			return null
		}
		val chapters = manga.getChapters(branch).orEmpty()
		if (chapters.isEmpty()) {
			return null
		}
		val remoteChapter = supervisorScope {
			scrobblers.filter { it.isEnabled }.map { scrobbler ->
				async {
					runCatchingCancellable {
						scrobbler.refreshScrobblingOrNull(manga.id)
							?.takeUnless { scrobbler.isNotStarted(it.status) }
							?.chapter
					}.onFailure {
						it.printStackTraceDebug()
					}.getOrNull()
				}
			}.awaitAll().filterNotNull().maxOrNull()
		} ?: return null
		val targetIndex = findRemoteProgressIndex(chapters, remoteChapter)
		if (targetIndex < 0) {
			return null
		}
		if (!historyRepository.advanceFromTracking(manga, chapters, targetIndex)) {
			return null
		}
		return chapters[targetIndex].number.takeIf { it > 0f }?.toInt() ?: targetIndex + 1
	}
}

internal fun findRemoteProgressIndex(chapters: List<MangaChapter>, remoteChapter: Int): Int {
	if (remoteChapter <= 0) {
		return -1
	}
	var previousNumber = 0f
	var result = -1
	for ((index, chapter) in chapters.withIndex()) {
		val number = chapter.number.takeIf { it > 0f } ?: (index + 1).toFloat()
		if (number < previousNumber || number > remoteChapter) {
			break
		}
		previousNumber = number
		result = index
	}
	return result
}
