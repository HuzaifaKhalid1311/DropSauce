package org.koitharu.kotatsu.details.domain

import org.koitharu.kotatsu.core.model.MangaHistory
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.data.ReadingTime
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.stats.data.StatsRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.ceil

class ReadingTimeUseCase @Inject constructor(
	private val settings: AppSettings,
	private val statsRepository: StatsRepository,
) {

	suspend operator fun invoke(manga: MangaDetails?, branch: String?, history: MangaHistory?): ReadingTime? {
		if (!settings.isReadingTimeEstimationEnabled) {
			return null
		}
		val chapters = manga?.chapters?.get(branch)
		if (chapters.isNullOrEmpty()) {
			return null
		}
		val isOnHistoryBranch = history != null && chapters.findById(history.chapterId) != null
		val averageChapterMillis = statsRepository.getAverageTimePerChapterMillis()
		if (averageChapterMillis <= 0L) {
			return null
		}
		val remainingChapters = if (isOnHistoryBranch) {
			ceil(chapters.size * (1f - history.percent).coerceIn(0f, 1f).toDouble()).toInt()
		} else {
			chapters.size
		}
		if (remainingChapters <= 0) {
			return null
		}
		val estimatedTimeSec = TimeUnit.MILLISECONDS.toSeconds(averageChapterMillis * remainingChapters)
		if (estimatedTimeSec < 60) return null
		return ReadingTime(
			minutes = ((estimatedTimeSec / 60) % 60).toInt(),
			hours = (estimatedTimeSec / 3600).toInt(),
			isContinue = isOnHistoryBranch,
		)
	}
}
