package org.koitharu.kotatsu.list.domain

import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode.CHAPTERS_READ
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode.NONE
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode.PERCENT_READ

data class ReadingProgress(
	val percent: Float,
	val totalChapters: Int,
	val mode: ProgressIndicatorMode,
) {

	fun isValid() = when (mode) {
		NONE -> false
		PERCENT_READ -> percent in 0f..1f
		CHAPTERS_READ -> percent in 0f..1f && totalChapters > 0
	}

	fun isCompleted() = isCompleted(percent)

	companion object {

		const val PROGRESS_NONE = -1f
		const val PROGRESS_COMPLETED = 1f
		private const val PROGRESS_COMPLETED_THRESHOLD = 0.99999f

		fun isValid(percent: Float) = percent in 0f..1f

		fun isCompleted(percent: Float) = percent >= PROGRESS_COMPLETED_THRESHOLD

		fun calculatePercent(
			chapterIndex: Int,
			chaptersCount: Int,
			pageIndex: Int,
			pagesCount: Int,
		): Float {
			if (chapterIndex !in 0 until chaptersCount) {
				return PROGRESS_NONE
			}
			if (chaptersCount > 1) {
				return (chapterIndex + 1) / chaptersCount.toFloat()
			}
			if (pagesCount <= 0) {
				return 0f
			}
			if (pagesCount == 1) {
				return PROGRESS_COMPLETED
			}
			return pageIndex.coerceIn(0, pagesCount - 1) / (pagesCount - 1).toFloat()
		}

		fun percentToString(percent: Float): String = if (isValid(percent)) {
			if (isCompleted(percent)) "100" else (percent * 100f).toInt().toString()
		} else {
			"0"
		}
	}
}
