package org.koitharu.kotatsu.download.ui.dialog

import androidx.collection.ArraySet
import androidx.collection.LongLongMap
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet

interface ChaptersSelectMacro {

	fun getChaptersIds(mangaId: Long, chapters: List<MangaChapter>): Set<Long>?

	class WholeManga(
		val chaptersCount: Int,
	) : ChaptersSelectMacro {

		override fun getChaptersIds(mangaId: Long, chapters: List<MangaChapter>): Set<Long>? = null
	}

	class WholeBranch(
		val branches: Map<String?, Int>,
		val selectedBranch: String?,
	) : ChaptersSelectMacro {

		val chaptersCount: Int = branches[selectedBranch] ?: 0

		override fun getChaptersIds(
			mangaId: Long,
			chapters: List<MangaChapter>
		): Set<Long> = chapters.mapNotNullToSet { c ->
			if (c.branch == selectedBranch) {
				c.id
			} else {
				null
			}
		}

		fun copy(branch: String?) = WholeBranch(branches, branch)
	}

	class FirstChapters(
		val chaptersCount: Int,
		val maxAvailableCount: Int,
		val branch: String?,
	) : ChaptersSelectMacro {

		override fun getChaptersIds(mangaId: Long, chapters: List<MangaChapter>): Set<Long> {
			val result = ArraySet<Long>(minOf(chaptersCount, chapters.size))
			for (c in chapters) {
				if (c.branch == branch) {
					result.add(c.id)
					if (result.size >= chaptersCount) {
						break
					}
				}
			}
			return result
		}

		fun copy(count: Int) = FirstChapters(count, maxAvailableCount, branch)
	}

	class UnreadChapters(
		val chaptersCount: Int,
		val maxAvailableCount: Int,
		private val currentChaptersIds: LongLongMap,
		private val defaultBranches: Map<Long, String?>,
	) : ChaptersSelectMacro {

		override fun getChaptersIds(mangaId: Long, chapters: List<MangaChapter>): Set<Long>? {
			if (chapters.isEmpty()) {
				return null
			}
			val currentIndex = if (currentChaptersIds.containsKey(mangaId)) {
				chapters.indexOfFirst { it.id == currentChaptersIds[mangaId] }
			} else {
				-1
			}
			// no history (or its chapter is gone): start from the first chapter of the preferred branch
			val branch = when {
				currentIndex >= 0 -> chapters[currentIndex].branch
				defaultBranches.containsKey(mangaId) -> defaultBranches[mangaId]
				else -> chapters.first().branch
			}
			val result = ArraySet<Long>(minOf(chaptersCount, chapters.size))
			for (i in currentIndex + 1 until chapters.size) {
				val c = chapters[i]
				if (c.branch == branch) {
					result.add(c.id)
					if (result.size >= chaptersCount) {
						break
					}
				}
			}
			return result
		}

		fun copy(count: Int) = UnreadChapters(count, maxAvailableCount, currentChaptersIds, defaultBranches)
	}
}
