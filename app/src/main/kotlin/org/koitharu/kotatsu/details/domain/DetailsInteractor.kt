package org.haziffe.dropsauce.details.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import org.haziffe.dropsauce.core.model.FavouriteCategory
import org.haziffe.dropsauce.core.model.isNsfw
import org.haziffe.dropsauce.core.prefs.AppSettings
import org.haziffe.dropsauce.core.prefs.TriStateOption
import org.haziffe.dropsauce.core.prefs.observeAsFlow
import org.haziffe.dropsauce.details.data.MangaDetails
import org.haziffe.dropsauce.favourites.domain.FavouritesRepository
import org.haziffe.dropsauce.history.data.HistoryRepository
import org.haziffe.dropsauce.local.data.LocalMangaRepository
import org.haziffe.dropsauce.local.domain.model.LocalManga
import org.haziffe.dropsauce.parsers.model.Manga
import org.haziffe.dropsauce.parsers.util.runCatchingCancellable
import org.haziffe.dropsauce.scrobbling.common.domain.Scrobbler
import org.haziffe.dropsauce.scrobbling.common.domain.model.ScrobblingInfo
import org.haziffe.dropsauce.tracker.domain.TrackingRepository
import javax.inject.Inject

/* TODO: remove */
class DetailsInteractor @Inject constructor(
	private val historyRepository: HistoryRepository,
	private val favouritesRepository: FavouritesRepository,
	private val localMangaRepository: LocalMangaRepository,
	private val trackingRepository: TrackingRepository,
	private val settings: AppSettings,
	private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
) {

	fun observeFavourite(mangaId: Long): Flow<Set<FavouriteCategory>> {
		return favouritesRepository.observeCategories(mangaId)
	}

	fun observeNewChapters(mangaId: Long): Flow<Int> {
		return settings.observeAsFlow(AppSettings.KEY_TRACKER_ENABLED) { isTrackerEnabled }
			.flatMapLatest { isEnabled ->
				if (isEnabled) {
					trackingRepository.observeNewChaptersCount(mangaId)
				} else {
					flowOf(0)
				}
			}
	}

	fun observeScrobblingInfo(mangaId: Long): Flow<List<ScrobblingInfo>> {
		return combine(
			scrobblers.map { it.observeScrobblingInfo(mangaId) },
		) { scrobblingInfo ->
			scrobblingInfo.filterNotNull()
		}
	}

	fun observeIncognitoMode(mangaFlow: Flow<Manga?>): Flow<TriStateOption> {
		return mangaFlow
			.filterNotNull()
			.distinctUntilChangedBy { it.isNsfw() }
			.combine(observeIncognitoMode()) { manga, globalIncognito ->
				when {
					globalIncognito -> TriStateOption.ENABLED
					manga.isNsfw() -> settings.incognitoModeForNsfw
					else -> TriStateOption.DISABLED
				}
			}
	}

	suspend fun updateLocal(subject: MangaDetails?, localManga: LocalManga): MangaDetails? {
		subject ?: return null
		return if (subject.id == localManga.manga.id) {
			if (subject.isLocal) {
				subject.copy(
					manga = localManga.manga,
				)
			} else {
				subject.copy(
					localManga = runCatchingCancellable {
						localManga.copy(
							manga = localMangaRepository.getDetails(localManga.manga),
						)
					}.getOrNull() ?: subject.local,
				)
			}
		} else {
			subject
		}
	}

	suspend fun findRemote(seed: Manga) = localMangaRepository.getRemoteManga(seed)

	private fun observeIncognitoMode() = settings.observeAsFlow(AppSettings.KEY_INCOGNITO_MODE) {
		isIncognitoModeEnabled
	}
}
