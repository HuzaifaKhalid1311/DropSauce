package org.koitharu.kotatsu.settings.sources.migration

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.Immutable
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
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
	@ApplicationContext private val context: Context,
) : ViewModel() {

	private val _state = MutableStateFlow(BrokenSourcesMigrationState(isLoading = true))
	val state: StateFlow<BrokenSourcesMigrationState> = _state.asStateFlow()

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
		_state.update { it.copy(selectedSources = emptySet()) }
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
