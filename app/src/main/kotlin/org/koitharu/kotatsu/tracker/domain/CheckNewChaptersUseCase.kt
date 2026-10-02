package org.koitharu.kotatsu.tracker.domain

import android.util.Log
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.model.ScanlatorMerge
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.withMergedBranches
import org.koitharu.kotatsu.core.parser.FreshMangaDetailsRepository
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.MultiMutex
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.toInstantOrNull
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.tracker.domain.model.MangaTracking
import org.koitharu.kotatsu.tracker.domain.model.MangaUpdates
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CheckNewChaptersUseCase @Inject constructor(
	private val repository: TrackingRepository,
	private val historyRepository: HistoryRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val localMangaRepository: LocalMangaRepository,
	private val settings: AppSettings,
	private val mangaDataRepository: MangaDataRepository,
) {

	private val mutex = MultiMutex<Long>()

	suspend operator fun invoke(manga: Manga): MangaUpdates = mutex.withLock(manga.id) {
		if (!settings.isTrackerEnabled) {
			return@withLock MangaUpdates.Failure(manga = manga, error = null)
		}
		repository.updateTracks()
		val tracking = repository.getTrackOrNull(manga) ?: return@withLock MangaUpdates.Failure(
			manga = manga,
			error = null,
		)
		invokeImpl(tracking)
	}

	suspend operator fun invoke(track: MangaTracking): MangaUpdates = mutex.withLock(track.manga.id) {
		invokeImpl(track)
	}

	suspend operator fun invoke(manga: Manga, currentChapterId: Long) = mutex.withLock(manga.id) {
		runCatchingCancellable {
			repository.updateTracks()
			val details = getFullManga(manga).withMergedBranches(mangaDataRepository.getScanlatorMerge(manga.id))
			var track = repository.getTrackOrNull(manga) ?: return@withLock
			val branch = checkNotNull(details.chapters?.findById(currentChapterId)).branch
			val chapters = details.getChapters(branch)
			if (!track.isEmpty()) {
				// Chapters that appeared since the last background check would otherwise be
				// re-baselined below without ever reaching the updates feed
				val unseen = chapters.takeLastWhile { x -> x.id != track.lastChapterId }
				if (unseen.isNotEmpty() && unseen.size < chapters.size) {
					repository.saveUpdates(MangaUpdates.Success(details, branch, unseen, isValid = true))
					track = repository.getTrackOrNull(manga) ?: return@withLock
				}
			}
			val chapterIndex = chapters.indexOfFirst { x -> x.id == currentChapterId }
			val lastNewChapterIndex = chapters.size - track.newChapters
			val lastChapter = chapters.lastOrNull()
			val tracking = MangaTracking(
				manga = details,
				lastChapterId = lastChapter?.id ?: 0L,
				lastCheck = Instant.now(),
				lastChapterDate = lastChapter?.uploadDate?.toInstantOrNull() ?: track.lastChapterDate,
				newChapters = when {
					track.newChapters == 0 -> 0
					chapterIndex < 0 -> track.newChapters
					chapterIndex >= lastNewChapterIndex -> chapters.lastIndex - chapterIndex
					else -> track.newChapters
				},
			)
			repository.mergeWith(tracking)
		}.onFailure { e ->
			e.printStackTraceDebug()
		}.isSuccess
	}

	private suspend fun invokeImpl(track: MangaTracking): MangaUpdates = runCatchingCancellable {
		val merge = mangaDataRepository.getScanlatorMerge(track.manga.id)
		val details = getFullManga(track.manga).withMergedBranches(merge)
		val branch = if (merge == ScanlatorMerge.All) null else getBranch(details, track.lastChapterId)
		compare(track, details, branch)
	}.getOrElse { error ->
		MangaUpdates.Failure(
			manga = track.manga,
			error = error,
		)
	}.also { updates ->
		repository.saveUpdates(updates)
	}

	private suspend fun getBranch(manga: Manga, trackChapterId: Long): String? {
		historyRepository.getOne(manga)?.let {
			manga.chapters?.findById(it.chapterId)
		}?.let {
			return it.branch
		}
		manga.chapters?.findById(trackChapterId)?.let {
			return it.branch
		}
		// fallback
		return manga.getPreferredBranch(null)
	}

	private suspend fun getFullManga(manga: Manga): Manga = when {
		manga.isLocal -> fetchDetails(
			requireNotNull(localMangaRepository.getRemoteManga(manga)) {
				"Local manga is not supported"
			},
		)

		manga.chapters.isNullOrEmpty() -> fetchDetails(manga)
		else -> manga
	}

	private suspend fun fetchDetails(manga: Manga): Manga {
		val repo = mangaRepositoryFactory.create(manga.source)
		val details = if (repo is FreshMangaDetailsRepository) {
			repo.getFreshDetails(manga)
		} else {
			repo.getDetails(manga)
		}
		// Persist so opening the manga later shows cached details instead of a cold load
		mangaDataRepository.storeManga(details, replaceExisting = true, stripAppliedOverride = false, detailsFetched = true)
		return details
	}

	private fun compare(
		track: MangaTracking,
		manga: Manga,
		branch: String?,
	): MangaUpdates {
		val chapters = requireNotNull(manga.getChapters(branch))
		if (track.isEmpty()) {
			// First check of a newly tracked manga: baseline silently. Flagging pre-existing
			// chapters here spammed notifications/downloads for manga the user just added
			return MangaUpdates.Success(manga, branch, newChapters = emptyList(), isValid = true)
		}
		if (chapters.isEmpty()) {
			// A "successful" but empty response (site glitch, layout change) must not wipe the track
			return MangaUpdates.Failure(manga, IllegalStateException("Source returned an empty chapter list"))
		}
		if (BuildConfig.DEBUG && chapters.findById(track.lastChapterId) == null) {
			Log.e("Tracker", "Chapter ${track.lastChapterId} not found")
		}
		val newChapters = chapters.takeLastWhile { x -> x.id != track.lastChapterId }
		return when {
			newChapters.isEmpty() -> {
				MangaUpdates.Success(manga, branch, newChapters = emptyList(), isValid = true)
			}

			newChapters.size == chapters.size -> {
				// The last known chapter is not in this branch: scanlator switch or the
				// site/extension re-keyed chapter ids. Never flag anything here — upload dates
				// and id diffs are both unreliable (fake dates flagged a whole second scanlator
				// as new). isValid=false re-baselines to the current list, keeping the counter
				MangaUpdates.Success(manga, branch, newChapters = emptyList(), isValid = false)
			}

			else -> {
				MangaUpdates.Success(manga, branch, newChapters, isValid = true)
			}
		}
	}
}
