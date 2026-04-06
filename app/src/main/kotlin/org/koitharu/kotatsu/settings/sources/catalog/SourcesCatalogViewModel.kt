package org.koitharu.kotatsu.settings.sources.catalog

import androidx.annotation.WorkerThread
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageDisplayName
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.mihon.MihonExtensionLoader
import org.koitharu.kotatsu.parsers.model.ContentType
import java.util.Comparator
import java.util.EnumSet
import java.util.LinkedHashSet
import javax.inject.Inject

@HiltViewModel
class SourcesCatalogViewModel @Inject constructor(
	@LocalizedAppContext context: android.content.Context,
	private val repository: MangaSourcesRepository,
	private val externalRepoRepository: ExternalExtensionRepoRepository,
	private val mihonExtensionLoader: MihonExtensionLoader,
	private val settings: AppSettings,
) : BaseViewModel() {

	private val appContext = context
	private val defaultLocales: Set<String?> = setOf(null)
	private val mihonSources = repository.observeMihonSources()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, emptyList<org.koitharu.kotatsu.mihon.model.MihonMangaSource>())
	private val availableRepoEntries = MutableStateFlow<List<ExternalExtensionRepoEntry>>(emptyList())

	private val searchQuery = MutableStateFlow<String?>(null)
	private val externalRepoUrl = MutableStateFlow(settings.externalExtensionsRepoUrl)
	private val refreshTrigger = MutableStateFlow(0)
	private var lastRefreshTrigger = 0
	val isRefreshing = MutableStateFlow(false)
	val isNsfwDisabled = settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, settings.isNsfwContentDisabled)
	val appliedFilter = MutableStateFlow(
		SourcesCatalogFilter(
			types = emptySet(),
			locale = null,
			isNewOnly = false,
		),
	)
	val onOpenPackageInstaller = MutableEventFlow<String>()
	val onOpenUninstall = MutableEventFlow<String>()
	val onShowMessage = MutableEventFlow<Int>()

	val hasNewSources = combine(
		appliedFilter,
		repository.observeHasNewSources(),
	) { _, hasNewSources ->
		hasNewSources
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

	val locales: StateFlow<Set<String?>> = combine(
		appliedFilter,
		mihonSources,
		availableRepoEntries,
	) { _, sources, repoEntries ->
		val localeSet = LinkedHashSet<String?>()
		sources.mapTo(localeSet) { it.language }
		repoEntries.mapTo(localeSet) { it.lang }
		localeSet.add(null)
		localeSet
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, defaultLocales)

	val contentTypes: StateFlow<List<ContentType>> = MutableStateFlow(emptyList())

	val content: StateFlow<List<ListModel>> = combine(
		searchQuery,
		appliedFilter,
		mihonSources,
		externalRepoUrl,
		refreshTrigger,
	) { args ->
		val q = args[0] as String?
		val f = args[1] as SourcesCatalogFilter
		val currentTrigger = args[4] as Int
		val forceRefresh = currentTrigger > lastRefreshTrigger
		if (forceRefresh) {
			lastRefreshTrigger = currentTrigger
		}
		val result = buildMixedCatalogList(f, q, forceRefresh)
		isRefreshing.value = false
		result
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	val hasUpdates = content.map { items ->
		items.any { it is SourceCatalogItem.Extension && it.action == SourceCatalogItem.Extension.Action.UPDATE }
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	init {
		repository.clearNewSourcesBadge()
	}

	fun performSearch(query: String?) {
		searchQuery.value = query?.trim()
	}

	fun refresh() {
		launchJob(Dispatchers.Default) {
			isRefreshing.value = true
			availableRepoEntries.value = emptyList()
			runCatching { mihonExtensionLoader.loadExtensions() }
			refreshTrigger.value++
		}
	}

	fun setLocale(value: String?) {
		appliedFilter.value = appliedFilter.value.copy(locale = value)
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

	fun setNsfwDisabled(value: Boolean) {
		settings.isNsfwContentDisabled = value
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
					val entry = getAvailableEntries(repoUrl, forceRefresh = false).firstOrNull { it.packageName == item.packageName } ?: run {
						onShowMessage.call(R.string.nothing_found)
						return@launchJob
					}
					onOpenPackageInstaller.call(externalRepoRepository.resolveApkUrl(repoUrl, entry.apkName))
				}
				SourceCatalogItem.Extension.Action.UNINSTALL -> onOpenUninstall.call(item.packageName)
			}
		}
	}

	fun updateAllExtensions() {
		launchJob(Dispatchers.Default) {
			val repoUrl = externalRepoUrl.value
			if (repoUrl.isNullOrBlank()) {
				onShowMessage.call(R.string.extensions_repo_required)
				return@launchJob
			}
			val updates = content.value.filterIsInstance<SourceCatalogItem.Extension>()
				.filter { it.action == SourceCatalogItem.Extension.Action.UPDATE }
			if (updates.isEmpty()) {
				onShowMessage.call(R.string.nothing_found)
				return@launchJob
			}
			val entries = getAvailableEntries(repoUrl, forceRefresh = false).associateBy { it.packageName }
			for (item in updates) {
				val entry = entries[item.packageName] ?: continue
				onOpenPackageInstaller.call(externalRepoRepository.resolveApkUrl(repoUrl, entry.apkName))
			}
		}
	}

	private suspend fun buildMixedCatalogList(filter: SourcesCatalogFilter, query: String?, forceRefresh: Boolean): List<ListModel> {
		return buildExtensionsList(filter, query, forceRefresh)
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

		val installed = mihonExtensionLoader.getInstalledExtensions(appContext).associateBy { it.pkgName }
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
				add(
					org.koitharu.kotatsu.list.ui.model.ListHeader(
						textRes = R.string.updates_pending,
						buttonTextRes = R.string.update_all,
						payload = HEADER_PAYLOAD_UPDATE_ALL,
					),
				)
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

	companion object {
		const val HEADER_PAYLOAD_UPDATE_ALL = "updates_pending_update_all"
	}

	@WorkerThread
	private fun getContentTypes(isNsfwDisabled: Boolean): List<ContentType> {
		// Content types come from extensions
		return if (isNsfwDisabled) {
			ContentType.entries.filterNot { it == ContentType.HENTAI }
		} else {
			ContentType.entries.toList()
		}
	}
}
