package org.koitharu.kotatsu.reader.domain

import android.util.LongSparseArray
import androidx.annotation.CheckResult
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import javax.inject.Inject

private const val PAGES_TRIM_THRESHOLD = 120

@ViewModelScoped
class ChaptersLoader @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
) {

	private val chapters = LongSparseArray<MangaChapter>()
	private val chapterPages = ChapterPages()
	private val mutex = Mutex()

	@Volatile
	private var manga: Manga? = null

	val size: Int
		get() = chapters.size()

	suspend fun init(manga: MangaDetails) = mutex.withLock {
		this.manga = manga.sourceManga
		chapters.clear()
		manga.allChapters.forEach {
			chapters.put(it.id, it)
		}
	}

	suspend fun loadPrevNextChapter(manga: MangaDetails, currentId: Long, isNext: Boolean): Boolean {
		val chapters = manga.allChapters
		val predicate: (MangaChapter) -> Boolean = { it.id == currentId }
		val index = if (isNext) chapters.indexOfFirst(predicate) else chapters.indexOfLast(predicate)
		if (index == -1) return false
		val newChapter = chapters.getOrNull(if (isNext) index + 1 else index - 1) ?: return false
		val newPages = loadChapter(newChapter.id)
		mutex.withLock {
			if (chapterPages.chaptersSize > 1) {
				// trim pages
				if (chapterPages.size > PAGES_TRIM_THRESHOLD) {
					if (isNext) {
						chapterPages.removeFirst()
					} else {
						chapterPages.removeLast()
					}
				}
			}
			if (isNext) {
				chapterPages.addLast(newChapter.id, newPages)
			} else {
				chapterPages.addFirst(newChapter.id, newPages)
			}
		}
		return true
	}

	@CheckResult
	suspend fun loadSingleChapter(chapterId: Long): Boolean {
		val pages = loadChapter(chapterId)
		return mutex.withLock {
			chapterPages.clear()
			chapterPages.addLast(chapterId, pages)
			pages.isNotEmpty()
		}
	}

	fun peekChapter(chapterId: Long): MangaChapter? = chapters[chapterId]

	// All reads below go through synchronized(chapterPages) — the same monitor ChapterPages' own
	// @Synchronized mutators lock on — so a concurrent add/remove can't be observed mid-mutation
	// (e.g. a ConcurrentModificationException from toList()/first()/last() reading the deque while
	// another coroutine is still shifting indices).
	fun hasPages(chapterId: Long): Boolean = synchronized(chapterPages) {
		chapterId in chapterPages
	}

	fun getPages(chapterId: Long): List<MangaPage> = synchronized(chapterPages) {
		chapterPages.subList(chapterId).map { it.toMangaPage() }
	}

	fun getPagesCount(chapterId: Long): Int = synchronized(chapterPages) {
		chapterPages.size(chapterId)
	}

	fun last() = synchronized(chapterPages) { chapterPages.last() }

	fun first() = synchronized(chapterPages) { chapterPages.first() }

	fun snapshot() = synchronized(chapterPages) { chapterPages.toList() }

	private suspend fun loadChapter(chapterId: Long): List<ReaderPage> {
		val chapter = checkNotNull(chapters[chapterId]) { "Requested chapter not found" }
		val repo = mangaRepositoryFactory.create(chapter.source)
		return repo.getPages(checkNotNull(manga), chapter).mapIndexed { index, page ->
			ReaderPage(page, index, chapterId)
		}
	}
}
