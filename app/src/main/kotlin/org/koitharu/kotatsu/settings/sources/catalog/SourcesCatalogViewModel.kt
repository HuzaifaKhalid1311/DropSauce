package org.koitharu.kotatsu.settings.sources.catalog

import androidx.annotation.WorkerThread
import androidx.lifecycle.viewModelScope
import androidx.room.invalidationTrackerFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.TABLE_SOURCES
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.mapSortedByCount
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageDisplayName
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.explore.data.SourcesSortOrder
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.mihon.MihonExtensionLoader
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.util.Comparator
import java.util.EnumSet
import java.util.LinkedHashSet
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SourcesCatalogViewModel @Inject constructor(
	@LocalizedAppContext private val context: android.content.Context,
	private val repository: MangaSourcesRepository,
	private val externalRepoRepository: ExternalExtensionRepoRepository,
	private val mihonExtensionLoader: MihonExtensionLoader,
	db: MangaDatabase,
	private val settings: AppSettings,
) : BaseViewModel() {

	val onActionDone = MutableEventFlow<ReversibleAction>()
	private val builtInLocales: Set<String?> = repository.allMangaSources.mapTo(LinkedHashSet<String?>()) { it.locale }.also {
		it.add(null)
	}
	private val builtInContentTypes = MutableStateFlow<List<ContentType>>(emptyList())
	private val mihonSources = repository.observeMihonSources()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, emptyList())
	private val availableRepoEntries = MutableStateFlow<List<ExternalExtensionRepoEntry>>(emptyList())

	private val searchQuery = MutableStateFlow<String?>(null)
	private val externalRepoUrl = MutableStateFlow(settings.externalExtensionsRepoUrl)
	private val refreshTrigger = MutableStateFlow(0)
	private var lastRefreshTrigger = 0
	val isRefreshing = MutableStateFlow(false)
	val appliedFilter = MutableStateFlow(
		SourcesCatalogFilter(
			mode = SourcesCatalogMode.BUILTIN,
			types = emptySet(),
			locale = Locale.getDefault().language.takeIf { it in builtInLocales },
			isNewOnly = false,
		),
	)
	val onOpenPackageInstaller = MutableEventFlow<String>()
	val onOpenUninstall = MutableEventFlow<String>()
	val onShowMessage = MutableEventFlow<Int>()

	val hasNewSources = combine(
		appliedFilter,
		repository.observeHasNewSources(),
	) { filter, hasNewSources ->
		filter.mode == SourcesCatalogMode.BUILTIN && hasNewSources
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

	val locales: StateFlow<Set<String?>> = combine(
		appliedFilter,
		mihonSources,
		availableRepoEntries,
	) { filter, sources, repoEntries ->
		when (filter.mode) {
			SourcesCatalogMode.BUILTIN -> builtInLocales
			SourcesCatalogMode.MIHON -> {
				// Include locales from both installed sources and available repo entries
				val localeSet = LinkedHashSet<String?>()
				sources.mapTo(localeSet) { it.language }
				repoEntries.mapTo(localeSet) { it.lang }
				localeSet.add(null)
				localeSet
			}
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, builtInLocales)

	val contentTypes: StateFlow<List<ContentType>> = combine(
		appliedFilter,
		builtInContentTypes,
	) { filter, types ->
		if (filter.mode == SourcesCatalogMode.BUILTIN) {
			types
		} else {
			emptyList()
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	val content: StateFlow<List<ListModel>> = combine(
		searchQuery,
		appliedFilter,
		db.invalidationTrackerFlow(TABLE_SOURCES),
		mihonSources,
		externalRepoUrl,
		refreshTrigger,
	) { args ->
		val q = args[0] as String?
		val f = args[1] as SourcesCatalogFilter
		val currentTrigger = args[5] as Int
		val forceRefresh = currentTrigger > lastRefreshTrigger
		if (forceRefresh) {
			lastRefreshTrigger = currentTrigger
		}
		val result = buildMixedCatalogList(f, q, forceRefresh)
		isRefreshing.value = false
		result
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	init {
		repository.clearNewSourcesBadge()
		launchJob(Dispatchers.Default) {
			builtInContentTypes.value = getContentTypes(settings.isNsfwContentDisabled)
		}
	}

	fun performSearch(query: String?) {
		searchQuery.value = query?.trim()
	}

	fun refresh() {
		isRefreshing.value = true
		refreshTrigger.value++
	}

	fun setMode(value: SourcesCatalogMode) {
		val filter = appliedFilter.value
		if (filter.mode == value) {
			return
		}
		val locales = when (value) {
			SourcesCatalogMode.BUILTIN -> builtInLocales
			SourcesCatalogMode.MIHON -> {
				val localeSet = LinkedHashSet<String?>()
				mihonSources.value.mapTo(localeSet) { it.language }
				localeSet.add(null)
				localeSet
			}
		}
		appliedFilter.value = filter.copy(
			mode = value,
			types = emptySet(),
			locale = filter.locale?.takeIf { it in locales },
			isNewOnly = if (value == SourcesCatalogMode.BUILTIN) filter.isNewOnly else false,
		)
	}

	fun setLocale(value: String?) {
		appliedFilter.value = appliedFilter.value.copy(locale = value)
	}

	fun addSource(source: MangaSource) {
		if (source !is MangaParserSource) {
			return
		}
		launchJob(Dispatchers.Default) {
			val rollback = repository.setSourcesEnabled(setOf(source), true)
			onActionDone.call(ReversibleAction(R.string.source_enabled, rollback))
		}
	}

	fun setContentType(value: ContentType, isAdd: Boolean) {
		val filter = appliedFilter.value
		val types = EnumSet.noneOf(ContentType::class.java)
		types.addAll(filter.types)
		if (isAdd) {
			types.add(value)
		} else {
			types.remove(value)
		}
		appliedFilter.value = filter.copy(types = types)
	}

	fun setNewOnly(value: Boolean) {
		appliedFilter.value = appliedFilter.value.copy(isNewOnly = value)
	}

	fun hasExternalRepoConfigured(): Boolean = !externalRepoUrl.value.isNullOrBlank()

	fun getExternalRepoUrl(): String? = externalRepoUrl.value

	fun setExternalRepoUrl(url: String?) {
		settings.externalExtensionsRepoUrl = url
		externalRepoUrl.value = settings.externalExtensionsRepoUrl
	}

	fun onInstallEntryClick(item: SourceCatalogItem.Extension) {
		launchJob(Dispatchers.Default) {
			when (item.action) {
				SourceCatalogItem.Extension.Action.INSTALL,
				SourceCatalogItem.Extension.Action.UPDATE -> {
					val repoUrl = externalRepoUrl.value
					if (repoUrl.isNullOrBlank()) {
						onShowMessage.call(R.string.extensions_repo_required)
						return@launchJob
					}
					val entry = getAvailableEntries(repoUrl).firstOrNull { it.packageName == item.packageName } ?: run {
						onShowMessage.call(R.string.nothing_found)
						return@launchJob
					}
					onOpenPackageInstaller.call(externalRepoRepository.resolveApkUrl(repoUrl, entry.apkName))
				}
				SourceCatalogItem.Extension.Action.UNINSTALL -> onOpenUninstall.call(item.packageName)
			}
		}
	}

	private suspend fun buildMixedCatalogList(filter: SourcesCatalogFilter, query: String?, forceRefresh: Boolean): List<ListModel> {
		val sources = when (filter.mode) {
			SourcesCatalogMode.BUILTIN -> repository.queryParserSources(
				isDisabledOnly = true,
				isNewOnly = filter.isNewOnly,
				excludeBroken = false,
				types = filter.types,
				query = query,
				locale = filter.locale,
				sortOrder = SourcesSortOrder.ALPHABETIC,
			)
			SourcesCatalogMode.MIHON -> emptyList()
		}
		return if (filter.mode == SourcesCatalogMode.MIHON) {
			buildExtensionsList(filter, query, forceRefresh)
		} else if (sources.isEmpty()) {
			listOf(
				if (query == null) {
					SourceCatalogItem.Hint(
						icon = R.drawable.ic_empty_feed,
						title = R.string.no_manga_sources,
						text = R.string.no_manga_sources_catalog_text,
					)
				} else {
					SourceCatalogItem.Hint(
						icon = R.drawable.ic_empty_feed,
						title = R.string.nothing_found,
						text = R.string.no_manga_sources_found,
					)
				},
			)
		} else {
			sources.map {
				SourceCatalogItem.Source(
					source = it,
					isAddAvailable = filter.mode == SourcesCatalogMode.BUILTIN,
				)
			}
		}
	}

	private suspend fun buildExtensionsList(
		filter: SourcesCatalogFilter,
		query: String?,
		forceRefresh: Boolean,
	): List<ListModel> {
		val repoUrl = externalRepoUrl.value
		val hasRepo = !repoUrl.isNullOrBlank()
		val availableResult = if (repoUrl.isNullOrBlank()) {
			availableRepoEntries.value = emptyList()
			Result.success(emptyList<ExternalExtensionRepoEntry>())
		} else {
			runCatching {
				getAvailableEntries(repoUrl, forceRefresh)
			}
		}
		
		val available = availableResult.getOrDefault(emptyList())
		availableRepoEntries.value = available

		val installed = mihonExtensionLoader.getInstalledExtensions(context).associateBy { it.pkgName }
		val installedSourcesByPkg = mihonSources.value.groupBy { it.pkgName }

		val pending = ArrayList<SourceCatalogItem.Extension>()
		val installedItems = linkedMapOf<String, SourceCatalogItem.Extension>()
		val availableItems = ArrayList<SourceCatalogItem.Extension>()
		val packagesWithUpdates = HashSet<String>()
		val locale = filter.locale
		val q = query?.takeIf { it.isNotBlank() }

		for (local in installed.values) {
			if (settings.isNsfwContentDisabled && local.isNsfw) continue
			if (locale != null && local.lang != locale) continue
			if (q != null && !local.appName.contains(q, ignoreCase = true) && !local.pkgName.contains(q, ignoreCase = true)) continue

			val pkgSources = installedSourcesByPkg[local.pkgName]
			val source = pkgSources?.firstOrNull { it.language == local.lang } ?: pkgSources?.firstOrNull()
			val subtitle = buildString {
				append(getExternalExtensionLanguageDisplayName(local.lang))
				append(" • ")
				append(local.versionName)
				if (local.isNsfw) {
					append(" • 18+")
				}
			}
			installedItems[local.pkgName] = SourceCatalogItem.Extension(
				packageName = local.pkgName,
				title = local.appName.removePrefix("Tachiyomi: ").trim(),
				subtitle = subtitle,
				action = SourceCatalogItem.Extension.Action.UNINSTALL,
				iconUrl = null,
				sourceIconName = source?.name,
				sourceName = source?.name,
			)
		}

		for (entry in available) {
			if (settings.isNsfwContentDisabled && entry.isNsfw != 0) continue
			if (locale != null && entry.lang != locale) continue
			if (q != null && !entry.name.contains(q, ignoreCase = true) && !entry.packageName.contains(q, ignoreCase = true)) continue

			val local = installed[entry.packageName]
			val pkgSources = installedSourcesByPkg[entry.packageName]
			val source = pkgSources?.firstOrNull { it.language == entry.lang } ?: pkgSources?.firstOrNull()
			val subtitle = buildString {
				append(getExternalExtensionLanguageDisplayName(entry.lang.orEmpty()))
				append(" • ")
				append(entry.versionName)
				if (entry.isNsfw != 0) {
					append(" • 18+")
				}
			}
			val iconUrl = repoUrl?.let { externalRepoRepository.resolveIconUrl(it, entry.packageName) }
			when {
				local == null -> availableItems += SourceCatalogItem.Extension(
					packageName = entry.packageName,
					title = entry.name.removePrefix("Tachiyomi: ").trim(),
					subtitle = subtitle,
					action = SourceCatalogItem.Extension.Action.INSTALL,
					iconUrl = iconUrl,
					sourceIconName = source?.name,
				)
				entry.versionCode > local.versionCode -> pending += SourceCatalogItem.Extension(
					packageName = entry.packageName,
					title = entry.name.removePrefix("Tachiyomi: ").trim(),
					subtitle = subtitle,
					action = SourceCatalogItem.Extension.Action.UPDATE,
					iconUrl = iconUrl,
					sourceIconName = source?.name,
					sourceName = source?.name,
				).also {
					packagesWithUpdates += entry.packageName
				}
				else -> Unit
			}
		}

		val titleComparator = Comparator<SourceCatalogItem.Extension> { a, b -> a.title.compareTo(b.title, ignoreCase = true) }
		pending.sortWith(titleComparator)
		val installedSorted = installedItems.values
			.filterNot { it.packageName in packagesWithUpdates }
			.sortedWith(titleComparator)
		availableItems.sortWith(titleComparator)

		return buildList {
			if (!hasRepo) {
				add(
					SourceCatalogItem.Hint(
						icon = R.drawable.ic_empty_common,
						title = R.string.no_repo_detected,
						text = R.string.click_edit_icon_add_extension_repo,
					),
				)
			} else if (availableResult.isFailure) {
				add(
					SourceCatalogItem.Hint(
						icon = R.drawable.ic_error_large,
						title = R.string.error,
						text = R.string.extensions_repo_load_error,
					),
				)
			}
			if (pending.isNotEmpty()) {
				add(org.koitharu.kotatsu.list.ui.model.ListHeader(R.string.updates_pending))
				addAll(pending)
			}
			if (installedSorted.isNotEmpty()) {
				add(org.koitharu.kotatsu.list.ui.model.ListHeader(R.string.installed))
				addAll(installedSorted)
			}
			if (availableItems.isNotEmpty()) {
				add(org.koitharu.kotatsu.list.ui.model.ListHeader(R.string.available_to_install))
				addAll(availableItems)
			}
			if (isEmpty()) {
				add(
					SourceCatalogItem.Hint(
						icon = R.drawable.ic_empty_feed,
						title = R.string.nothing_found,
						text = R.string.no_manga_sources_found,
					),
				)
			}
		}
	}

	private suspend fun getAvailableEntries(repoUrl: String, forceRefresh: Boolean): List<ExternalExtensionRepoEntry> {
		return externalRepoRepository.getExtensions(repoUrl, forceRefresh)
	}

	@WorkerThread
	private fun getContentTypes(isNsfwDisabled: Boolean): List<ContentType> {
		val result = repository.allMangaSources.mapSortedByCount { it.contentType }
		return if (isNsfwDisabled) {
			result.filterNot { it == ContentType.HENTAI }
		} else {
			result
		}
	}
}
