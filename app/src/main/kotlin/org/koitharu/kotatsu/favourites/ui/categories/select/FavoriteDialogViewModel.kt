package org.koitharu.kotatsu.favourites.ui.categories.select

import androidx.collection.MutableLongObjectMap
import androidx.collection.MutableLongSet
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.android.material.checkbox.MaterialCheckBox
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.MigrateUseCase
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.ids
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.EventFlow
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.domain.model.DuplicateGroup
import org.koitharu.kotatsu.favourites.ui.categories.select.model.MangaCategoryItem
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@HiltViewModel
class FavoriteDialogViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val favouritesRepository: FavouritesRepository,
	private val migrateUseCase: MigrateUseCase,
	private val mihonExtensionManager: MihonExtensionManager,
	settings: AppSettings,
) : BaseViewModel() {

	val manga = savedStateHandle.require<List<ParcelableManga>>(AppRouter.KEY_MANGA_LIST).map {
		it.manga
	}

	private val duplicatesFlow = MutableStateFlow<List<DuplicateGroup>>(emptyList())

	/**
	 * Possible duplicates for the manga being added. Starts empty so the category picker is usable
	 * immediately; results slide in once the (local-only) check finishes.
	 */
	val duplicates: StateFlow<List<DuplicateGroup>> = duplicatesFlow.asStateFlow()

	private val migrationFlow = MutableStateFlow<MigrationRequest?>(null)

	/** Non-null while the migration confirmation is on screen. */
	val migration: StateFlow<MigrationRequest?> = migrationFlow.asStateFlow()

	private val migratedEvent = MutableEventFlow<Unit>()
	val onMigrated: EventFlow<Unit> = migratedEvent

	private val dismissEvent = MutableEventFlow<Unit>()
	val onDismissRequested: EventFlow<Unit> = dismissEvent

	private val excludedMangaIds = MutableStateFlow(emptySet<Long>())

	init {
		launchJob(Dispatchers.Default) {
			// Detection is an assist, never a gate: on failure the dialog just behaves as before.
			runCatchingCancellable {
				favouritesRepository.findDuplicates(manga)
			}.onFailure {
				it.printStackTraceDebug()
			}.onSuccess { found ->
				duplicatesFlow.value = found.map { group ->
					group.copy(
						target = resolveSource(group.target),
						matches = group.matches.map { m -> m.copy(manga = resolveSource(m.manga)) },
					)
				}
			}
		}
	}

	private val refreshTrigger = MutableStateFlow(Any())
	val content = combine(
		favouritesRepository.observeCategories(),
		refreshTrigger,
		excludedMangaIds,
		settings.observeAsFlow(AppSettings.KEY_TRACKER_ENABLED) { isTrackerEnabled },
	) { categories, _, excluded, tracker ->
		mapList(categories, excluded, tracker)
	}.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	/** The manga that will actually be added — everything the user did not skip or migrate away. */
	fun getActiveManga(): List<Manga> = manga.filterNot { it.id in excludedMangaIds.value }

	fun setChecked(categoryId: Long, isChecked: Boolean) {
		launchJob(Dispatchers.Default) {
			val activeManga = getActiveManga()
			if (activeManga.isEmpty()) {
				return@launchJob
			}
			if (isChecked) {
				favouritesRepository.addToCategory(categoryId, activeManga)
			} else {
				favouritesRepository.removeFromCategory(categoryId, activeManga.ids())
			}
			refreshTrigger.value = Any()
		}
	}

	/** Dismiss the warning for [targetId] and keep it in the batch. */
	fun addAnyway(targetId: Long) {
		duplicatesFlow.update { list -> list.filterNot { it.target.id == targetId } }
	}

	fun addAllAnyway() {
		duplicatesFlow.value = emptyList()
	}

	/** Drop [targetId] from the batch entirely. */
	fun skip(targetId: Long) {
		excludedMangaIds.update { it + targetId }
		duplicatesFlow.update { list -> list.filterNot { it.target.id == targetId } }
		if (getActiveManga().isEmpty()) {
			dismissEvent.call(Unit)
		}
	}

	fun requestMigration(target: Manga, existing: Manga) {
		if (existing.isLocal) {
			return // migrating away from an imported copy would lose the local files
		}
		migrationFlow.value = MigrationRequest(target = target, existing = existing)
	}

	fun cancelMigration() {
		migrationFlow.update { if (it?.isRunning == true) it else null }
	}

	fun confirmMigration() {
		val request = migrationFlow.value ?: return
		if (request.isRunning) {
			return
		}
		migrationFlow.value = request.copy(isRunning = true)
		launchJob(Dispatchers.Default) {
			try {
				migrateUseCase(oldManga = request.existing, newManga = request.target)
			} catch (e: Throwable) {
				// Always release the confirmation screen, otherwise its buttons stay disabled forever.
				migrationFlow.value = request.copy(isRunning = false)
				throw e
			}
			migrationFlow.value = null
			excludedMangaIds.update { it + request.target.id }
			duplicatesFlow.update { list -> list.filterNot { it.target.id == request.target.id } }
			migratedEvent.call(Unit)
			if (getActiveManga().isEmpty()) {
				dismissEvent.call(Unit)
			}
		}
	}

	private suspend fun mapList(
		categories: List<FavouriteCategory>,
		excluded: Set<Long>,
		tracker: Boolean,
	): List<ListModel> {
		if (categories.isEmpty()) {
			return listOf(
				EmptyState(
					icon = 0,
					textPrimary = R.string.empty_favourite_categories,
					textSecondary = 0,
					actionStringRes = 0,
				),
			)
		}
		val activeManga = manga.filterNot { it.id in excluded }
		if (activeManga.isEmpty()) {
			return emptyList()
		}
		val cats = MutableLongObjectMap<MutableLongSet>(categories.size)
		categories.forEach { cats[it.id] = MutableLongSet(activeManga.size) }
		for (m in activeManga) {
			val ids = favouritesRepository.getCategoriesIds(m.id)
			ids.forEach { id -> cats[id]?.add(m.id) }
		}
		return categories.map { cat ->
			MangaCategoryItem(
				category = cat,
				checkedState = when (cats[cat.id]?.size ?: 0) {
					0 -> MaterialCheckBox.STATE_UNCHECKED
					activeManga.size -> MaterialCheckBox.STATE_CHECKED
					else -> MaterialCheckBox.STATE_INDETERMINATE
				},
				isTrackerEnabled = tracker,
			)
		}
	}

	/**
	 * Manga restored from the database carry a placeholder `MIHON_<id>` source; swap in the live
	 * extension source so the card can render a proper source name.
	 */
	private suspend fun resolveSource(manga: Manga): Manga {
		val name = manga.source.name
		if (manga.source is MihonMangaSource || !name.startsWith(MIHON_SOURCE_PREFIX)) {
			return manga
		}
		val isReady = runCatchingCancellable {
			mihonExtensionManager.ensureReady()
		}.onFailure {
			it.printStackTraceDebug()
		}.isSuccess
		if (!isReady) {
			return manga
		}
		val sourceId = name.removePrefix(MIHON_SOURCE_PREFIX).substringBefore(':').toLongOrNull()
		val resolved = sourceId?.let { mihonExtensionManager.getMihonMangaSourceById(it) }
			?: mihonExtensionManager.getMihonMangaSourceByName(name)
		return if (resolved != null) manga.copy(source = resolved) else manga
	}

	data class MigrationRequest(
		val target: Manga,
		val existing: Manga,
		val isRunning: Boolean = false,
	)

	private companion object {

		const val MIHON_SOURCE_PREFIX = "MIHON_"
	}
}
