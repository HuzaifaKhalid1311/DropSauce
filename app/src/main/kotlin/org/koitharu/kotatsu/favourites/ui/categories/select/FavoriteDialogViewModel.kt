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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.alternatives.domain.MigrateUseCase
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.ids
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.ui.categories.select.model.MangaCategoryItem
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toMangaChapters
import org.koitharu.kotatsu.core.parser.FreshMangaDetailsRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.model.MihonMangaSource

@HiltViewModel
class FavoriteDialogViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val favouritesRepository: FavouritesRepository,
	private val migrateUseCase: MigrateUseCase,
	private val mihonExtensionManager: MihonExtensionManager,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val db: MangaDatabase,
	settings: AppSettings,
) : BaseViewModel() {


	val manga = savedStateHandle.require<List<ParcelableManga>>(AppRouter.KEY_MANGA_LIST).map {
		it.manga
	}

	val duplicatesState = MutableStateFlow<List<Pair<Manga, List<Manga>>>?>(null)

	init {
		launchJob(Dispatchers.Default) {
			checkDuplicates()
		}
	}

	private suspend fun checkDuplicates() {
		val resultList = mutableListOf<Pair<Manga, List<Manga>>>()
		for (m in manga) {
			if (favouritesRepository.getCategoriesIds(m.id).isNotEmpty()) {
				continue
			}
			val resolvedManga = prepareManga(m)
			val duplicates = favouritesRepository.getDuplicates(resolvedManga)
			if (duplicates.isNotEmpty()) {
				resultList.add(resolvedManga to duplicates)
			}
		}
		if (resultList.isNotEmpty()) {
			duplicatesState.value = resultList
			fetchDetailsAsync(resultList)
		} else {
			duplicatesState.value = null
		}
	}

	private val refreshTrigger = MutableStateFlow(Any())
	val content = combine(
		favouritesRepository.observeCategories(),
		refreshTrigger,
		settings.observeAsFlow(AppSettings.KEY_TRACKER_ENABLED) { isTrackerEnabled },
	) { categories, _, tracker ->
		mapList(categories, tracker)
	}.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	private val skippedMangaIds = mutableSetOf<Long>()
	private val migratedMangaIds = mutableSetOf<Long>()
	private val approvedMangaIds = mutableSetOf<Long>()

	fun getActiveManga(): List<Manga> {
		return manga.filterNot { skippedMangaIds.contains(it.id) || migratedMangaIds.contains(it.id) }
	}

	fun setChecked(categoryId: Long, isChecked: Boolean) {
		launchJob(Dispatchers.Default) {
			val activeManga = manga.filterNot { skippedMangaIds.contains(it.id) || migratedMangaIds.contains(it.id) }
			if (activeManga.isEmpty()) return@launchJob

			if (isChecked) {
				favouritesRepository.addToCategory(categoryId, activeManga)
			} else {
				favouritesRepository.removeFromCategory(categoryId, activeManga.ids())
			}
			refreshTrigger.value = Any()
		}
	}

	fun confirmAddDuplicate() {
		duplicatesState.value = null
	}

	fun confirmAddIndividualAnyway(targetManga: Manga) {
		approvedMangaIds.add(targetManga.id)
		val current = duplicatesState.value.orEmpty().filterNot { it.first.id == targetManga.id }
		duplicatesState.value = current.ifEmpty { null }
	}

	fun skipIndividualDuplicate(targetManga: Manga, onCompleteIfEmpty: () -> Unit) {
		skippedMangaIds.add(targetManga.id)
		val current = duplicatesState.value.orEmpty().filterNot { it.first.id == targetManga.id }
		duplicatesState.value = current.ifEmpty { null }
		refreshTrigger.value = Any()
		val remainingActive = manga.filterNot { skippedMangaIds.contains(it.id) || migratedMangaIds.contains(it.id) }
		if (remainingActive.isEmpty()) {
			onCompleteIfEmpty()
		}
	}

	fun skipAllDuplicates(onCompleteIfEmpty: () -> Unit) {
		val currentDuplicates = duplicatesState.value.orEmpty()
		for ((target, _) in currentDuplicates) {
			skippedMangaIds.add(target.id)
		}
		duplicatesState.value = null
		refreshTrigger.value = Any()
		val remainingActive = manga.filterNot { skippedMangaIds.contains(it.id) || migratedMangaIds.contains(it.id) }
		if (remainingActive.isEmpty()) {
			onCompleteIfEmpty()
		}
	}

	fun migrateDuplicate(targetManga: Manga, existingManga: Manga, onCompleteIfFinished: () -> Unit) {
		launchJob(Dispatchers.Default) {
			migrateUseCase(oldManga = existingManga, newManga = targetManga)
			migratedMangaIds.add(targetManga.id)
			val current = duplicatesState.value.orEmpty().filterNot { it.first.id == targetManga.id }
			duplicatesState.value = current.ifEmpty { null }
			refreshTrigger.value = Any()
			val remainingActive = manga.filterNot { skippedMangaIds.contains(it.id) || migratedMangaIds.contains(it.id) }
			if (remainingActive.isEmpty()) {
				onCompleteIfFinished()
			}
		}
	}

	fun dismissDuplicate(onDismissAll: () -> Unit) {
		duplicatesState.value = null
		onDismissAll()
	}

	private suspend fun mapList(categories: List<FavouriteCategory>, tracker: Boolean): List<ListModel> {
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
		val activeManga = manga.filterNot { skippedMangaIds.contains(it.id) || migratedMangaIds.contains(it.id) }
		if (activeManga.isEmpty()) {
			return emptyList()
		}
		val existingCategoryIds = mutableSetOf<Long>()
		for (m in activeManga) {
			val catIds = favouritesRepository.getCategoriesIds(m.id)
			existingCategoryIds.addAll(catIds)
		}
		if (existingCategoryIds.isNotEmpty()) {
			for (m in activeManga) {
				val currentCats = favouritesRepository.getCategoriesIds(m.id)
				if (currentCats.isEmpty()) {
					for (catId in existingCategoryIds) {
						favouritesRepository.addToCategory(catId, listOf(m))
					}
				}
			}
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

	private suspend fun prepareManga(manga: Manga): Manga {
		var resolved = resolveManga(manga)
		if (resolved.chapters == null) {
			val dbChapters = runCatchingCancellable { db.getChaptersDao().findAll(resolved.id) }.getOrNull()
			if (!dbChapters.isNullOrEmpty()) {
				resolved = resolved.copy(chapters = dbChapters.toMangaChapters())
			}
		}
		return resolved
	}

	private fun updateDuplicatesState(currentList: List<Pair<Manga, List<Manga>>>) {
		val activeState = duplicatesState.value
		if (activeState.isNullOrEmpty()) return
		val activeIds = activeState.map { it.first.id }.toSet()
		val filtered = currentList.filter { (target, _) ->
			activeIds.contains(target.id) &&
				!skippedMangaIds.contains(target.id) &&
				!migratedMangaIds.contains(target.id) &&
				!approvedMangaIds.contains(target.id)
		}
		duplicatesState.value = filtered.ifEmpty { null }
	}

	private fun fetchDetailsAsync(list: List<Pair<Manga, List<Manga>>>) {
		launchJob(Dispatchers.Default) {
			val currentList = list.map { (target, dupes) ->
				val preparedTarget = prepareManga(target)
				val preparedDupes = dupes.map { prepareManga(it) }
				preparedTarget to preparedDupes
			}.toMutableList()

			updateDuplicatesState(currentList)

			currentList.forEachIndexed { pairIndex, (target, dupes) ->
				var currentTarget = target
				if (currentTarget.chapters.isNullOrEmpty()) {
					runCatchingCancellable {
						val repo = mangaRepositoryFactory.create(currentTarget.source)
						val details = repo.getDetails(currentTarget)
						currentTarget = if (details.chapters.isNullOrEmpty()) {
							(repo as? FreshMangaDetailsRepository)?.getFreshDetails(currentTarget) ?: details
						} else {
							details
						}
					}
				}

				val updatedDupes = dupes.toMutableList()
				updatedDupes.forEachIndexed { dupIndex, dup ->
					if (dup.chapters.isNullOrEmpty()) {
						runCatchingCancellable {
							val repo = mangaRepositoryFactory.create(dup.source)
							val details = repo.getDetails(dup)
							val fullDup = if (details.chapters.isNullOrEmpty()) {
								(repo as? FreshMangaDetailsRepository)?.getFreshDetails(dup) ?: details
							} else {
								details
							}
							updatedDupes[dupIndex] = fullDup
						}
					}
				}

				currentList[pairIndex] = currentTarget to updatedDupes.toList()
				updateDuplicatesState(currentList)
			}
		}
	}

	private suspend fun resolveManga(manga: Manga): Manga {
		if (manga.source is MihonMangaSource) return manga
		val name = manga.source.name
		if (!name.startsWith("MIHON_")) return manga
		mihonExtensionManager.ensureReady()
		val sourceId = name.removePrefix("MIHON_").substringBefore(':').toLongOrNull()
		val resolved = sourceId?.let { mihonExtensionManager.getMihonMangaSourceById(it) }
			?: mihonExtensionManager.getMihonMangaSourceByName(name)
		return if (resolved != null) manga.copy(source = resolved) else manga
	}
}

