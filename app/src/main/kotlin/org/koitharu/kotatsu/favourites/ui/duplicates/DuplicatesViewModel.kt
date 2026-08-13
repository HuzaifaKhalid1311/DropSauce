package org.koitharu.kotatsu.favourites.ui.duplicates

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.koitharu.kotatsu.alternatives.domain.MigrateUseCase
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.favourites.domain.DuplicatesUseCase
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.domain.MangaDuplicate
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

/**
 * Drives the duplicate sheet for a whole batch of manga at once.
 *
 * Manga that are already favourited, or that clash with nothing, never reach the UI — they go
 * straight into [accepted] and end up in the category dialog once the queue is drained. Manga the
 * user replaces are dropped from [accepted]: migration already carried the old entry's categories
 * over, so asking for a category again would be wrong.
 */
@HiltViewModel
class DuplicatesViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val duplicatesUseCase: DuplicatesUseCase,
	private val migrateUseCase: MigrateUseCase,
	private val favouritesRepository: FavouritesRepository,
	private val settings: AppSettings,
) : BaseViewModel() {

	private val input: List<Manga> = savedStateHandle
		.require<List<ParcelableManga>>(AppRouter.KEY_MANGA_LIST)
		.map { it.manga }

	private val accepted = ArrayList<Manga>(input.size)
	private val queue = ArrayList<Clash>()
	private var chaptersJob: Job? = null

	private val _state = MutableStateFlow<DuplicatesState>(DuplicatesState.Checking)
	val state: StateFlow<DuplicatesState> = _state

	val onFinished = MutableEventFlow<List<Manga>>()
	val onMigrated = MutableEventFlow<MigrationResult>()

	init {
		launchJob(Dispatchers.Default) {
			if (!settings.isDuplicateCheckEnabled) {
				accepted.addAll(input)
				advance()
				return@launchJob
			}
			for (manga in input) {
				// Editing the categories of something already in the library isn't "adding a duplicate".
				if (favouritesRepository.getCategoriesIds(manga.id).isNotEmpty()) {
					accepted.add(manga)
					continue
				}
				val duplicates = duplicatesUseCase(manga)
				if (duplicates.isEmpty()) {
					accepted.add(manga)
				} else {
					queue.add(Clash(manga, duplicates))
				}
			}
			advance()
		}
	}

	/**
	 * Turns the check off from the sheet's overflow menu and lets the rest of this batch through
	 * untouched — the user has just said they don't want to be asked.
	 */
	fun disableDuplicateCheck() {
		if (isBusy()) return
		settings.isDuplicateCheckEnabled = false
		launchJob(Dispatchers.Default) {
			queue.forEach { accepted.add(it.manga) }
			queue.clear()
			advance()
		}
	}

	fun skip() {
		if (isBusy()) return
		queue.removeFirstOrNull()
		launchJob(Dispatchers.Default) { advance() }
	}

	fun addAnyway() {
		if (isBusy()) return
		queue.removeFirstOrNull()?.let { accepted.add(it.manga) }
		launchJob(Dispatchers.Default) { advance() }
	}

	fun replaceWith(existing: Manga) {
		if (isBusy()) return
		val current = queue.firstOrNull() ?: return
		setCardsBusy(existing.id)
		launchLoadingJob(Dispatchers.Default) {
			try {
				migrateUseCase(oldManga = existing, newManga = current.manga)
			} catch (e: Throwable) {
				setCardsBusy(null)
				throw e
			}
			onMigrated.call(
				MigrationResult(
					title = current.manga.title,
					fromSource = existing.source,
					toSource = current.manga.source,
				),
			)
			queue.removeFirstOrNull()
			advance()
		}
	}

	/** True while a migration is in flight — every other action has to wait it out. */
	private fun isBusy(): Boolean = (_state.value as? DuplicatesState.Ask)?.isMigrating == true

	private fun setCardsBusy(migratingId: Long?) {
		_state.update { current ->
			if (current !is DuplicatesState.Ask) {
				current
			} else {
				current.copy(
					cards = current.cards.map {
						it.copy(isMigrating = it.manga.id == migratingId, isBlocked = migratingId != null)
					},
				)
			}
		}
	}

	private suspend fun advance() {
		chaptersJob?.cancel()
		val next = queue.firstOrNull()
		if (next == null) {
			onFinished.call(accepted)
			return
		}
		val known = duplicatesUseCase.getLocalChaptersCount(next.manga)
		_state.value = DuplicatesState.Ask(
			incoming = next.manga,
			cards = next.duplicates.map { DuplicateCardModel(it, known, isMigrating = false, isBlocked = false) },
			remaining = queue.size - 1,
		)
		if (known == null) {
			resolveIncomingChapters(next.manga)
		}
	}

	/**
	 * Fills in the chapter-difference arrows once the source answers. Purely cosmetic, so failures
	 * are swallowed and the sheet simply keeps showing counts without arrows.
	 */
	private fun resolveIncomingChapters(manga: Manga) {
		chaptersJob = launchJob(Dispatchers.Default + SkipErrors) {
			val count = duplicatesUseCase.fetchChaptersCount(manga) ?: return@launchJob
			_state.update { current ->
				if (current !is DuplicatesState.Ask || current.incoming.id != manga.id) {
					current
				} else {
					current.copy(cards = current.cards.map { it.copy(incomingChapters = count) })
				}
			}
		}
	}

	private class Clash(
		val manga: Manga,
		val duplicates: List<MangaDuplicate>,
	)
}
