package org.haziffe.dropsauce.tracker.domain.model

import org.haziffe.dropsauce.parsers.exception.TooManyRequestExceptions
import org.haziffe.dropsauce.parsers.model.Manga
import org.haziffe.dropsauce.parsers.model.MangaChapter
import org.haziffe.dropsauce.parsers.util.ifZero

sealed interface MangaUpdates {

	val manga: Manga

	data class Success(
		override val manga: Manga,
		val branch: String?,
		val newChapters: List<MangaChapter>,
		val isValid: Boolean,
	) : MangaUpdates {

		fun isNotEmpty() = newChapters.isNotEmpty()

		fun lastChapterDate(): Long {
			val lastChapter = newChapters.lastOrNull()
			return lastChapter?.uploadDate?.ifZero { System.currentTimeMillis() }
				?: (manga.chapters?.lastOrNull()?.uploadDate ?: 0L)
		}
	}

	data class Failure(
		override val manga: Manga,
		val error: Throwable?,
	) : MangaUpdates {

		fun shouldRetry() = error is TooManyRequestExceptions
	}
}
