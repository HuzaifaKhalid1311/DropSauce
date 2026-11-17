package org.koitharu.kotatsu.settings.sources.migration

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.Immutable
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.alternatives.domain.AutoFixUseCase
import org.koitharu.kotatsu.alternatives.domain.MigrateUseCase
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.getStoredTitleOrNull
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.kotatsumigration.data.KotatsuSourceMap
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.settings.sources.catalog.ExternalExtensionRepoRepository
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreManager
import javax.inject.Inject

@HiltViewModel
class BrokenSourcesMigrationViewModel @Inject constructor(
	private val database: MangaDatabase,
	private val extensionManager: MihonExtensionManager,
	private val extensionRepoRepository: ExternalExtensionRepoRepository,
	private val extensionStoreManager: ExtensionStoreManager,
	private val kotatsuSourceMap: KotatsuSourceMap,
	private val sourcesRepository: MangaSourcesRepository,
	private val autoFixUseCase: AutoFixUseCase,
	private val migrateUseCase: MigrateUseCase,
	@ApplicationContext private val context: Context,
) : ViewModel() {

	private val _state = MutableStateFlow(BrokenSourcesMigrationState(isLoading = true))
	val state: StateFlow<BrokenSourcesMigrationState> = _state.asStateFlow()

	/** Fires with the number of entries migrated once a reviewed migration is done. */
	val onReviewMigrated = MutableEventFlow<Int>()

	private var reviewJob: Job? = null

	init {
		observeSources()
	}

	private fun observeSources() {
		viewModelScope.launch {
			_state.update { it.copy(isLoading = true) }
			val metadata = withContext(Dispatchers.IO) {
				extensionManager.ensureReady()
				extensionStoreManager.initialize()
				val installedIds = extensionManager.getMihonMangaSources()
					.mapTo(hashSetOf()) { it.sourceId }
				val repositoryIcons = buildMap {
					for (storeState in extensionStoreManager.states.value) {
						if (!storeState.store.enabled) continue
						for (extension in storeState.catalog) {
							val iconUrl = extension.iconUrl ?: extensionRepoRepository.resolveIconUrl(
								storeState.store.indexUrl,
								extension.packageName,
							)
							for (source in extension.sources) {
								val id = source.id.toLongOrNull() ?: continue
								putIfAbsent(id, iconUrl)
							}
						}
					}
				}
				SourceMetadata(installedIds, repositoryIcons)
			}
			database.getMangaDao().observeLibrarySourceUsage().collect { usages ->
				val sources = withContext(Dispatchers.IO) { buildOptions(usages, metadata) }
				_state.update { current ->
					current.copy(
						isLoading = false,
						sources = sources,
						selectedSources = current.selectedSources.intersect(sources.mapTo(hashSetOf()) { it.key }),
					)
				}
			}
		}
	}

	private suspend fun buildOptions(
		usages: List<org.koitharu.kotatsu.core.db.dao.LibrarySourceUsage>,
		metadata: SourceMetadata,
	): List<LibrarySourceOption> {
		val unmerged = usages.map { usage ->
			val source = MangaSource(usage.source, usage.sourceTitle)
			val mihonId = usage.source
				.takeIf { it.startsWith(MIHON_PREFIX) }
				?.removePrefix(MIHON_PREFIX)
				?.substringBefore(':')
				?.toLongOrNull()
			val mappedLegacySource = if (mihonId == null) {
				kotatsuSourceMap.resolve(usage.source)
			} else {
				null
			}
			val represented = when (mihonId) {
				null -> mappedLegacySource != null
				else -> mihonId in metadata.installedIds ||
					mihonId in metadata.repositoryIcons ||
					kotatsuSourceMap.resolveById(mihonId) != null
			}
			val representedSourceId = mihonId ?: mappedLegacySource?.sourceId
			val title = usage.sourceTitle
				?.takeIf(String::isNotBlank)
				?: mappedLegacySource?.sourceName?.takeIf(String::isNotBlank)
				?: source.getStoredTitleOrNull()
				?: source.getTitle(context).toReadableSourceName()
			LibrarySourceOption(
				key = usage.source,
				sourceKeys = setOf(usage.source),
				title = title,
				mangaCount = usage.mangaCount,
				isUnavailable = !represented,
				iconSourceKey = when {
					mihonId != null -> usage.source
					mappedLegacySource != null ->
						"MIHON_${mappedLegacySource.sourceId}:${mappedLegacySource.sourceName}"
					else -> usage.source
				},
				iconUrl = representedSourceId
					?.takeUnless { it in metadata.installedIds }
					?.let(metadata.repositoryIcons::get),
			)
		}
		return mergeLibrarySourceOptions(unmerged)
	}

	fun toggle(source: String) {
		_state.update { current ->
			current.copy(
				selectedSources = current.selectedSources.toMutableSet().apply {
					if (!add(source)) remove(source)
				},
			)
		}
	}

	fun clearSelection() {
		_state.update { it.copy(selectedSources = emptySet(), step = MigrationStep.SOURCES) }
	}

	fun toggleAll(sources: Collection<String>) {
		_state.update { current ->
			val sourceSet = sources.toSet()
			current.copy(
				selectedSources = if (sourceSet.all(current.selectedSources::contains)) {
					current.selectedSources - sourceSet
				} else {
					current.selectedSources + sourceSet
				},
			)
		}
	}

	/** Raw source keys behind the "from" selection. */
	fun selectedSourceKeys(): Set<String> = _state.value.let { current ->
		current.sources.filter { it.key in current.selectedSources }.flatMapTo(linkedSetOf()) { it.sourceKeys }
	}

	/** Checked targets in priority order, as source names. */
	fun checkedTargets(): List<String> = _state.value.let { current ->
		current.targets.map { it.name }.filter { it in current.checkedTargets }
	}

	/** Step 2: every enabled source of the kinds being migrated, all ticked, in Explore's order. */
	fun openTargets() {
		val from = selectedSourceKeys()
		val kinds = from.mapTo(hashSetOf()) { MangaSource(it).isNovelSource }
		val targets = sourcesRepository.getEnabledSources()
			.filter { it.name !in from && it.isNovelSource in kinds }
			.map { TargetOption(it.name, it.getTitle(context), it) }
		_state.update {
			it.copy(
				step = MigrationStep.TARGETS,
				targets = targets,
				checkedTargets = targets.mapTo(hashSetOf()) { t -> t.name },
			)
		}
	}

	fun toggleTarget(name: String) {
		_state.update { current ->
			current.copy(
				checkedTargets = current.checkedTargets.toMutableSet().apply {
					if (!add(name)) remove(name)
				},
			)
		}
	}

	fun toggleAllTargets() {
		_state.update { current ->
			current.copy(
				checkedTargets = if (current.checkedTargets.size == current.targets.size) {
					emptySet()
				} else {
					current.targets.mapTo(hashSetOf()) { it.name }
				},
			)
		}
	}

	fun moveTarget(from: Int, to: Int) {
		_state.update { current ->
			current.copy(targets = current.targets.toMutableList().apply { add(to, removeAt(from)) })
		}
	}

	/** Review searches every ticked source per title, which is slow, so turning it on starts from none ticked. */
	fun setManualReview(isManual: Boolean) {
		_state.update {
			it.copy(
				isManualReview = isManual,
				checkedTargets = if (isManual) emptySet() else it.targets.mapTo(hashSetOf()) { t -> t.name },
			)
		}
	}

	/** Step 3 (manual): searches each entry in turn and pre-picks the first working match. */
	fun startReview() {
		val sourceKeys = selectedSourceKeys().toList()
		val targets = checkedTargets()
		_state.update { it.copy(step = MigrationStep.REVIEW, review = emptyList(), reviewTotal = 0) }
		reviewJob?.cancel()
		reviewJob = viewModelScope.launch(Dispatchers.Default) {
			val ids = database.getMangaDao().findLibraryMangaIdsBySources(sourceKeys)
			_state.update { it.copy(reviewTotal = ids.size) }
			for (id in ids) {
				val seed = runCatchingCancellable { autoFixUseCase.loadSeed(id) }.getOrNull()
				if (seed == null) {
					_state.update { it.copy(reviewTotal = it.reviewTotal - 1) }
					continue
				}
				_state.update { it.copy(review = it.review + ReviewItem(seed, null, null)) }
				val candidates = runCatchingCancellable { autoFixUseCase.findCandidates(seed, targets) }
					.getOrDefault(emptyList())
				val choice = runCatchingCancellable { autoFixUseCase.pickWorking(candidates) }.getOrNull()
				updateReviewItem(seed.id) { it.copy(candidates = candidates, choice = choice) }
			}
		}
	}

	/** [manga] null means the entry is skipped. */
	fun chooseReplacement(seedId: Long, manga: Manga?) {
		updateReviewItem(seedId) { it.copy(choice = manga) }
	}

	fun migrateReviewed() {
		val items = _state.value.review.filter { it.choice != null }
		_state.update { it.copy(isMigrating = true) }
		viewModelScope.launch(Dispatchers.Default) {
			var done = 0
			for (item in items) {
				runCatchingCancellable { migrateUseCase(item.seed, checkNotNull(item.choice)) }
					.onSuccess { done++ }
					.onFailure { it.printStackTraceDebug() }
			}
			_state.update {
				it.copy(step = MigrationStep.SOURCES, selectedSources = emptySet(), review = emptyList(), isMigrating = false)
			}
			onReviewMigrated.call(done)
		}
	}

	/** Goes one step back; false when already on the first step. */
	fun back(): Boolean {
		val step = _state.value.step
		if (step == MigrationStep.SOURCES || _state.value.isMigrating) return step != MigrationStep.SOURCES
		if (step == MigrationStep.REVIEW) {
			reviewJob?.cancel()
		}
		_state.update { it.copy(step = MigrationStep.entries[step.ordinal - 1], review = emptyList()) }
		return true
	}

	private fun updateReviewItem(seedId: Long, block: (ReviewItem) -> ReviewItem) {
		_state.update { current ->
			current.copy(review = current.review.map { if (it.seed.id == seedId) block(it) else it })
		}
	}

	fun toggleInfo() {
		_state.update { it.copy(isInfoVisible = !it.isInfoVisible) }
	}

	private companion object {
		const val MIHON_PREFIX = "MIHON_"
	}
}

internal fun mergeLibrarySourceOptions(
	options: List<LibrarySourceOption>,
): List<LibrarySourceOption> {
	return options
			.groupBy { it.title.trim().lowercase() }
			.map { (canonicalKey, group) ->
				val iconSource = group.firstOrNull { !it.isUnavailable && it.iconUrl != null }
					?: group.firstOrNull { !it.isUnavailable }
					?: group.first()
				LibrarySourceOption(
					key = canonicalKey,
					sourceKeys = group.flatMapTo(linkedSetOf()) { it.sourceKeys },
					title = group.first().title,
					mangaCount = group.sumOf { it.mangaCount },
					isUnavailable = group.all { it.isUnavailable },
					iconSourceKey = iconSource.iconSourceKey,
					iconUrl = iconSource.iconUrl,
				)
			}
			.sortedBy { it.title.lowercase() }
}

private fun String.toReadableSourceName(): String {
	if ('_' !in this || startsWith("MIHON_")) return this
	return lowercase()
		.split('_')
		.joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }
}

@Immutable
data class BrokenSourcesMigrationState(
	val isLoading: Boolean = false,
	val isInfoVisible: Boolean = false,
	val sources: List<LibrarySourceOption> = emptyList(),
	val selectedSources: Set<String> = emptySet(),
	val step: MigrationStep = MigrationStep.SOURCES,
	val targets: List<TargetOption> = emptyList(),
	val checkedTargets: Set<String> = emptySet(),
	val isManualReview: Boolean = false,
	val review: List<ReviewItem> = emptyList(),
	val reviewTotal: Int = 0,
	val isMigrating: Boolean = false,
) {
	val isReviewSearching: Boolean
		get() = review.size < reviewTotal || review.any { it.candidates == null }
}

enum class MigrationStep { SOURCES, TARGETS, REVIEW }

@Immutable
data class TargetOption(
	val name: String,
	val title: String,
	val source: MangaSource,
)

/** [candidates] is null while still searching; [choice] null means skipped or nothing found. */
@Immutable
data class ReviewItem(
	val seed: Manga,
	val candidates: List<Manga>?,
	val choice: Manga?,
)

@Immutable
data class LibrarySourceOption(
	val key: String,
	val sourceKeys: Set<String>,
	val title: String,
	val mangaCount: Int,
	val isUnavailable: Boolean,
	val iconSourceKey: String,
	val iconUrl: String?,
)

private data class SourceMetadata(
	val installedIds: Set<Long>,
	val repositoryIcons: Map<Long, String>,
)
