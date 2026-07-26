package org.koitharu.kotatsu.settings.sources.catalog

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
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.kotatsumigration.data.KotatsuSourceMap
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageDisplayName
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.ListHeader
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
	private val storeManager: ExtensionStoreManager,
	private val mihonExtensionLoader: MihonExtensionLoader,
	private val settings: AppSettings,
	private val kotatsuSourceMap: KotatsuSourceMap,
	private val mangaDatabase: MangaDatabase,
) : BaseViewModel() {

	private val appContext = context
	private val defaultLocales: Set<String?> = setOf(null)
	private val mihonSources = repository.observeMihonSources()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, emptyList<org.koitharu.kotatsu.mihon.model.MihonMangaSource>())
	private val allMihonSources = repository.observeAllMihonSources()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, emptyList<org.koitharu.kotatsu.mihon.model.MihonMangaSource>())
	private val availableRepoEntries = MutableStateFlow<List<ExternalExtensionRepoEntry>>(emptyList())

	private val searchQuery = MutableStateFlow<String?>(null)
	private val activePageId = MutableStateFlow(ExtensionCatalogPage.Updates.id)
	private val hasUnknownStore = MutableStateFlow(false)
	private val installingPackages = MutableStateFlow<Set<String>>(emptySet())
	private val refreshTrigger = MutableStateFlow(0)
	val isRefreshing = MutableStateFlow(false)
	val isNsfwDisabled = settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, settings.isNsfwContentDisabled)
	val isPrivateMode = settings.observeAsFlow(AppSettings.KEY_PRIVATE_INSTALLER) { isPrivateInstallEnabled }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, settings.isPrivateInstallEnabled)
	val showUpdatesTab = settings.observeAsFlow(AppSettings.KEY_EXTENSION_UPDATES_TAB) { isExtensionUpdatesTabEnabled }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, settings.isExtensionUpdatesTabEnabled)
	val appliedFilter = MutableStateFlow(
		SourcesCatalogFilter(
			types = emptySet(),
			locale = null,
		),
	)
	val onOpenPackageInstaller = MutableEventFlow<List<InstallRequest>>()
	val onOpenUninstall = MutableEventFlow<String>()
	val onShowMessage = MutableEventFlow<Int>()
	val pages: StateFlow<List<ExtensionCatalogPage>> = combine(
		storeManager.states,
		hasUnknownStore,
		showUpdatesTab,
	) { states, includeUnknown, includeUpdates ->
		buildExtensionCatalogPages(states.map { it.store }, includeUnknown, includeUpdates)
	}.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		listOf(ExtensionCatalogPage.Updates),
	)

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

	val content: StateFlow<CatalogPageContent> = combine(
		searchQuery,
		appliedFilter,
		mihonSources,
		allMihonSources,
		installingPackages,
		refreshTrigger,
		settings.observeAsFlow(AppSettings.KEY_MIHON_HIDDEN_PACKAGES) { mihonHiddenPackages },
		isPrivateMode,
		activePageId,
		storeManager.states,
		showUpdatesTab,
	) { args ->
		val q = args[0] as String?
		val f = args[1] as SourcesCatalogFilter
		val privateMode = args[7] as Boolean
		val pageId = args[8] as String
		@Suppress("UNCHECKED_CAST")
		val storeStates = args[9] as List<ExtensionStoreState>
		val includeUpdates = args[10] as Boolean
		val mode = if (privateMode) ExtensionInstallMode.SANDBOX else ExtensionInstallMode.SYSTEM
		refreshUnknownStoreState()
		val result = buildPage(pageId, storeStates, mode, f, q, includeUpdates)
		isRefreshing.value = false
		CatalogPageContent(pageId, result)
	}.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		CatalogPageContent(ExtensionCatalogPage.Updates.id, listOf(LoadingState)),
	)

	val hasUpdates = content.map { page ->
		page.items.any { it is SourceCatalogItem.Extension && it.action == SourceCatalogItem.Extension.Action.UPDATE }
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	init {
		launchJob(Dispatchers.Default) {
			storeManager.initialize()
			refreshUnknownStoreState()
		}
	}

	fun selectPage(pageId: String) {
		activePageId.value = pageId
	}

	fun performSearch(query: String?) {
		searchQuery.value = query?.trim()
	}

	fun refresh() {
		isRefreshing.value = true
		launchJob(Dispatchers.Default) {
			try {
				repository.reloadMihonSources()
				storeManager.refresh(forceRefresh = true)
				refreshUnknownStoreState()
			} finally {
				refreshTrigger.value++
			}
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

	fun hasExternalRepoConfigured(): Boolean = storeManager.stores().any { it.enabled }

	fun addExternalStore(url: String) {
		launchJob(Dispatchers.Default) {
			val result = storeManager.validateAndAdd(url)
			if (result.isFailure) {
				onShowMessage.call(R.string.extensions_repo_load_error)
			} else {
				refreshTrigger.value++
			}
		}
	}

	fun setNsfwDisabled(value: Boolean) {
		settings.isNsfwContentDisabled = value
	}

	/** Hides or shows an installed extension in Explore. It stays listed in the manager. */
	fun setExtensionHidden(packageName: String, hidden: Boolean) {
		settings.setMihonPackageHidden(packageName, hidden)
	}

	fun onInstallEntryClick(item: SourceCatalogItem.Extension) {
		if (item.isInProgress) {
			return
		}
		launchJob(Dispatchers.Default) {
			when (item.action) {
				SourceCatalogItem.Extension.Action.ENABLE -> {
					createInstallRequest(item)?.let { emitInstallRequests(listOf(it)) }
				}
				SourceCatalogItem.Extension.Action.DISABLE -> onOpenUninstall.call(item.packageName)
				SourceCatalogItem.Extension.Action.INSTALL,
				SourceCatalogItem.Extension.Action.UPDATE -> {
					createInstallRequest(item)?.let { emitInstallRequests(listOf(it)) }
				}
				SourceCatalogItem.Extension.Action.UNINSTALL -> onOpenUninstall.call(item.packageName)
			}
		}
	}

	fun updateAllExtensions() {
		launchJob(Dispatchers.Default) {
			val mode = if (settings.isPrivateInstallEnabled) {
				ExtensionInstallMode.SANDBOX
			} else {
				ExtensionInstallMode.SYSTEM
			}
			val statesById = storeManager.states.value.associateBy { it.store.id }
			val requests = mihonExtensionLoader.getInstalledExtensions(
				appContext,
				privateMode = mode == ExtensionInstallMode.SANDBOX,
			).mapNotNull { local ->
				val owner = storeManager.owner(mode, local) ?: return@mapNotNull null
				val state = statesById[owner.id] ?: return@mapNotNull null
				if (!owner.enabled || state.health != StoreHealth.AVAILABLE) return@mapNotNull null
				val entry = state.catalog.firstOrNull {
					it.packageName == local.pkgName && it.isNewerThan(local)
				} ?: return@mapNotNull null
				InstallRequest(
					packageName = entry.packageName,
					url = externalRepoRepository.resolveApkUrl(owner.indexUrl, entry.apkName),
					storeId = owner.id,
					mode = mode,
				)
			}
			if (requests.isEmpty()) {
				onShowMessage.call(R.string.nothing_found)
				return@launchJob
			}
			emitInstallRequests(requests)
		}
	}

	private fun createInstallRequest(item: SourceCatalogItem.Extension): InstallRequest? {
		val store = item.storeId?.let { id -> storeManager.stores().firstOrNull { it.id == id } }
		if (store == null || !store.enabled) {
			onShowMessage.call(R.string.extensions_repo_required)
			return null
		}
		val entry = storeManager.state(store.id)?.catalog
			?.firstOrNull { it.packageName == item.packageName }
		if (entry == null) {
			onShowMessage.call(R.string.nothing_found)
			return null
		}
		val mode = if (item.isPrivateMode) ExtensionInstallMode.SANDBOX else ExtensionInstallMode.SYSTEM
		val local = mihonExtensionLoader.getInstalledExtensions(
			appContext,
			privateMode = mode == ExtensionInstallMode.SANDBOX,
		).firstOrNull { it.pkgName == item.packageName }
		val currentOwner = local?.let { storeManager.owner(mode, it) }
		return InstallRequest(
			packageName = item.packageName,
			url = externalRepoRepository.resolveApkUrl(store.indexUrl, entry.apkName),
			storeId = store.id,
			mode = mode,
			replacement = local
				?.takeIf { currentOwner?.id != store.id }
				?.let {
					ProviderReplacement(
						currentOwner?.displayName ?: appContext.getString(R.string.unknown_store),
						store.displayName,
					)
				},
		)
	}

	fun setExtensionInProgress(packageName: String, isInProgress: Boolean) {
		val current = installingPackages.value
		installingPackages.value = if (isInProgress) {
			current + packageName
		} else {
			current - packageName
		}
	}

	fun clearExtensionInProgress(packageName: String?) {
		if (packageName != null) {
			setExtensionInProgress(packageName, false)
		}
	}

	private fun emitInstallRequests(requests: List<InstallRequest>) {
		if (requests.isEmpty()) {
			return
		}
		installingPackages.value = installingPackages.value + requests.mapTo(HashSet(requests.size)) { it.packageName }
		onOpenPackageInstaller.call(requests)
	}

	private fun refreshUnknownStoreState() {
		val mode = if (settings.isPrivateInstallEnabled) {
			ExtensionInstallMode.SANDBOX
		} else {
			ExtensionInstallMode.SYSTEM
		}
		val installed = mihonExtensionLoader.getInstalledExtensions(
			appContext,
			privateMode = mode == ExtensionInstallMode.SANDBOX,
		)
		hasUnknownStore.value = installed.any { storeManager.owner(mode, it) == null }
	}

	private fun buildUpdatesPage(
		storeStates: List<ExtensionStoreState>,
		mode: ExtensionInstallMode,
		filter: SourcesCatalogFilter,
		query: String?,
	): List<ListModel> {
		val statesById = storeStates.associateBy { it.store.id }
		val q = query?.takeIf(String::isNotBlank)
		val inProgress = installingPackages.value
		val installed = mihonExtensionLoader.getInstalledExtensions(
			appContext,
			privateMode = mode == ExtensionInstallMode.SANDBOX,
		)
		val installedSourcesByPackage = allMihonSources.value.groupBy { it.pkgName }
		val updates = installed.mapNotNull { local ->
			val owner = storeManager.owner(mode, local) ?: return@mapNotNull null
			val state = statesById[owner.id] ?: return@mapNotNull null
			if (!owner.enabled || state.health != StoreHealth.AVAILABLE) return@mapNotNull null
			val entry = state.catalog.firstOrNull { it.packageName == local.pkgName && it.isNewerThan(local) }
				?: return@mapNotNull null
			val source = installedSourcesByPackage[local.pkgName]
				?.firstOrNull { it.language == local.lang }
				?: installedSourcesByPackage[local.pkgName]?.firstOrNull()
			if (settings.isNsfwContentDisabled && entry.isNsfw != 0) return@mapNotNull null
			if (filter.locale != null && entry.lang != filter.locale) return@mapNotNull null
			if (q != null &&
				!entry.name.contains(q, ignoreCase = true) &&
				!entry.packageName.contains(q, ignoreCase = true)
			) {
				return@mapNotNull null
			}
			SourceCatalogItem.Extension(
				packageName = entry.packageName,
				title = entry.name.removePrefix("Tachiyomi: ").trim(),
				subtitle = buildString {
					append(getExternalExtensionLanguageDisplayName(entry.lang.orEmpty()))
					append(" • ")
					append(entry.versionName)
					append(" • ")
					append(owner.displayName)
				},
				action = SourceCatalogItem.Extension.Action.UPDATE,
				isInProgress = entry.packageName in inProgress,
				iconUrl = entry.iconUrl ?: externalRepoRepository.resolveIconUrl(owner.indexUrl, entry.packageName),
				sourceIconName = source?.name,
				sourceName = source?.name,
				storeId = owner.id,
				isHidden = settings.isMihonPackageHidden(local.pkgName),
				isPrivateMode = mode == ExtensionInstallMode.SANDBOX,
			)
		}.sortedBy { it.title.lowercase() }
		return if (updates.isEmpty()) {
			listOf(
				SourceCatalogItem.Hint(
					R.drawable.ic_empty_feed,
					R.string.nothing_found,
					R.string.no_extension_updates,
				),
			)
		} else updates
	}

	private fun buildUnknownPage(
		mode: ExtensionInstallMode,
		filter: SourcesCatalogFilter,
		query: String?,
	): List<ListModel> {
		val q = query?.takeIf(String::isNotBlank)
		val installedSourcesByPkg = allMihonSources.value.groupBy { it.pkgName }
		val items = mihonExtensionLoader.getInstalledExtensions(
			appContext,
			privateMode = mode == ExtensionInstallMode.SANDBOX,
		).mapNotNull { local ->
			if (storeManager.owner(mode, local) != null) return@mapNotNull null
			if (settings.isNsfwContentDisabled && local.isNsfw) return@mapNotNull null
			if (filter.locale != null && local.lang != filter.locale) return@mapNotNull null
			if (q != null &&
				!local.appName.contains(q, ignoreCase = true) &&
				!local.pkgName.contains(q, ignoreCase = true)
			) {
				return@mapNotNull null
			}
			val source = installedSourcesByPkg[local.pkgName]
				?.firstOrNull { it.language == local.lang }
				?: installedSourcesByPkg[local.pkgName]?.firstOrNull()
			SourceCatalogItem.Extension(
				packageName = local.pkgName,
				title = local.appName.removePrefix("Tachiyomi: ").trim(),
				subtitle = buildString {
					append(getExternalExtensionLanguageDisplayName(local.lang))
					append(" • ")
					append(local.versionName)
					if (local.isNsfw) append(" • 18+")
				},
				action = if (mode == ExtensionInstallMode.SANDBOX) {
					SourceCatalogItem.Extension.Action.DISABLE
				} else {
					SourceCatalogItem.Extension.Action.UNINSTALL
				},
				isInProgress = local.pkgName in installingPackages.value,
				sourceIconName = source?.name,
				sourceName = source?.name,
				isHidden = settings.isMihonPackageHidden(local.pkgName),
				isPrivateMode = mode == ExtensionInstallMode.SANDBOX,
			)
		}.sortedBy { it.title.lowercase() }
		return buildList {
			add(SourceCatalogItem.Hint(R.drawable.ic_error_large, R.string.unknown_store, R.string.unknown_store_summary))
			if (items.isNotEmpty()) {
				add(ListHeader(if (mode == ExtensionInstallMode.SANDBOX) R.string.enabled else R.string.installed))
				addAll(items)
			}
		}
	}

	private suspend fun buildPage(
		pageId: String,
		storeStates: List<ExtensionStoreState>,
		mode: ExtensionInstallMode,
		filter: SourcesCatalogFilter,
		query: String?,
		showUpdatesTab: Boolean,
	): List<ListModel> = when (pageId) {
		ExtensionCatalogPage.Updates.id -> buildUpdatesPage(storeStates, mode, filter, query)
		ExtensionCatalogPage.Unknown.id -> buildUnknownPage(mode, filter, query)
		else -> {
			val storeState = storeStates.firstOrNull { it.store.id == pageId }
				?: return listOf(LoadingState)
			if (mode == ExtensionInstallMode.SANDBOX) {
				buildPrivateExtensionsList(filter, query, storeState, showUpdatesTab)
			} else {
				buildExtensionsList(filter, query, storeState, showUpdatesTab)
			}
		}
	}

	private suspend fun buildExtensionsList(
		filter: SourcesCatalogFilter,
		query: String?,
		storeState: ExtensionStoreState,
		showUpdatesTab: Boolean,
	): List<ListModel> {
		val repoUrl = storeState.store.indexUrl
		val available = storeState.catalog
		availableRepoEntries.value = available
		val installed = mihonExtensionLoader.getInstalledExtensions(appContext).associateBy { it.pkgName }
		val installedSourcesByPkg = mihonSources.value.groupBy { it.pkgName }
		val allInstalledSourcesByPkg = allMihonSources.value.groupBy { it.pkgName }

		val installedItems = linkedMapOf<String, SourceCatalogItem.Extension>()
		val updateItems = ArrayList<SourceCatalogItem.Extension>()
		val availableItems = ArrayList<SourceCatalogItem.Extension>()
		val locale = filter.locale
		val q = query?.takeIf { it.isNotBlank() }
		val inProgressPackages = installingPackages.value

		for (local in installed.values) {
			val owner = storeManager.owner(ExtensionInstallMode.SYSTEM, local) ?: continue
			if (owner.id != storeState.store.id) continue
			if (settings.isNsfwContentDisabled && local.isNsfw) continue
			val pkgAllSources = allInstalledSourcesByPkg[local.pkgName].orEmpty()
			if (locale != null && local.lang != locale && pkgAllSources.none { it.language == locale }) continue
			if (q != null && !local.appName.contains(q, ignoreCase = true) && !local.pkgName.contains(q, ignoreCase = true)) continue

			val pkgSources = allInstalledSourcesByPkg[local.pkgName] ?: installedSourcesByPkg[local.pkgName]
			val source = pkgSources?.firstOrNull { it.language == local.lang } ?: pkgSources?.firstOrNull()
			val update = findStoreTabUpdate(local, available, showUpdatesTab)
			val repoLabel = owner.displayName
			val subtitle = buildString {
				append(getExternalExtensionLanguageDisplayName(local.lang))
				append(" • ")
				append(update?.versionName ?: local.versionName)
				if (local.isNsfw) {
					append(" • 18+")
				}
				append(" • ")
				append(repoLabel)
			}
			val item = SourceCatalogItem.Extension(
				packageName = local.pkgName,
				title = (update?.name ?: local.appName).removePrefix("Tachiyomi: ").trim(),
				subtitle = subtitle,
				action = if (update == null) SourceCatalogItem.Extension.Action.UNINSTALL else SourceCatalogItem.Extension.Action.UPDATE,
				isInProgress = local.pkgName in inProgressPackages,
				iconUrl = update?.let { it.iconUrl ?: externalRepoRepository.resolveIconUrl(repoUrl, it.packageName) },
				sourceIconName = source?.name,
				sourceName = source?.name,
				storeId = owner.id,
				isHidden = settings.isMihonPackageHidden(local.pkgName),
			)
			if (update == null) installedItems[local.pkgName] = item else updateItems += item
		}

		val installedIds = allMihonSources.value.mapTo(HashSet()) { it.sourceId }
		// id -> (package, display name) for every source the configured repo offers, so a
		// MIHON_<id> library entry from ANY origin (DropSauce/GDrive/Kotatsu/Mihon backup) can be
		// recommended even if it isn't in the baked migration map.
		val repoSourceIndex = HashMap<Long, Pair<String, String>>()
		for (entry in available) {
			val fallbackName = entry.name.removePrefix("Tachiyomi: ").trim()
			for (src in entry.sources) {
				val sid = src.id.toLongOrNull() ?: continue
				repoSourceIndex.putIfAbsent(sid, entry.packageName to src.name.ifBlank { fallbackName })
			}
		}
		val recommended = computeRecommendedExtensions(
			installedPkgs = installed.keys,
			installedIds = installedIds,
			inProgress = inProgressPackages,
			query = q,
			repoUrl = repoUrl,
			repoSourceIndex = repoSourceIndex,
			storeId = storeState.store.id,
			action = SourceCatalogItem.Extension.Action.INSTALL,
			isPrivateMode = false,
		)
		val recommendedPackages = recommended.mapTo(HashSet(recommended.size)) { it.packageName }

		for (entry in available) {
			if (entry.packageName in recommendedPackages) continue // surfaced in the Recommended section
			if (settings.isNsfwContentDisabled && entry.isNsfw != 0) continue
			if (locale != null && entry.lang != locale) continue
			if (q != null && !entry.name.contains(q, ignoreCase = true) && !entry.packageName.contains(q, ignoreCase = true)) continue

			val local = installed[entry.packageName]
			val localOwner = local?.let { storeManager.owner(ExtensionInstallMode.SYSTEM, it) }
			val pkgSources = allInstalledSourcesByPkg[entry.packageName] ?: installedSourcesByPkg[entry.packageName]
			val source = pkgSources?.firstOrNull { it.language == entry.lang } ?: pkgSources?.firstOrNull()
			val subtitle = buildString {
				append(getExternalExtensionLanguageDisplayName(entry.lang.orEmpty()))
				append(" • ")
				append(entry.versionName)
				if (entry.isNsfw != 0) {
					append(" • 18+")
				}
			}
			val iconUrl = entry.iconUrl ?: externalRepoRepository.resolveIconUrl(repoUrl, entry.packageName)
			when {
				local == null -> availableItems += SourceCatalogItem.Extension(
					packageName = entry.packageName,
					title = entry.name.removePrefix("Tachiyomi: ").trim(),
					subtitle = subtitle,
					action = SourceCatalogItem.Extension.Action.INSTALL,
					isInProgress = entry.packageName in inProgressPackages,
					iconUrl = iconUrl,
					sourceIconName = source?.name,
					storeId = storeState.store.id,
				)
				localOwner?.id != storeState.store.id -> availableItems += SourceCatalogItem.Extension(
					packageName = entry.packageName,
					title = entry.name.removePrefix("Tachiyomi: ").trim(),
					subtitle = subtitle,
					action = SourceCatalogItem.Extension.Action.INSTALL,
					isInProgress = entry.packageName in inProgressPackages,
					iconUrl = iconUrl,
					sourceIconName = source?.name,
					storeId = storeState.store.id,
				)
				else -> Unit
			}
		}

		val titleComparator = Comparator<SourceCatalogItem.Extension> { a, b -> a.title.compareTo(b.title, ignoreCase = true) }
		val installedSorted = installedItems.values
			.sortedWith(titleComparator)
		availableItems.sortWith(titleComparator)

		return buildList {
			// The "no repository" / error hint always stays pinned at the very top.
			if (storeState.health == StoreHealth.REMOVED) {
				add(
					SourceCatalogItem.Hint(
						icon = R.drawable.ic_error_large,
						title = R.string.store_removed,
						text = R.string.store_removed_catalog_warning,
					),
				)
			} else if (storeState.health == StoreHealth.UNAVAILABLE) {
				add(
					SourceCatalogItem.Hint(
						icon = R.drawable.ic_error_large,
						title = R.string.error,
						text = R.string.extensions_repo_load_error,
					),
				)
			}
			if (updateItems.isNotEmpty()) {
				add(ListHeader(R.string.updates_pending))
				addAll(updateItems.sortedWith(titleComparator))
			}
			if (installedSorted.isNotEmpty()) {
				add(org.koitharu.kotatsu.list.ui.model.ListHeader(R.string.installed))
				addAll(installedSorted)
			}
			if (recommended.isNotEmpty()) {
				add(org.koitharu.kotatsu.list.ui.model.ListHeader(R.string.recommended_to_install))
				addAll(recommended)
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

	/**
	 * Extensions the user's library needs but that aren't installed: derived from `MIHON_<id>`
	 * sources referenced by favourites/history and offered by the current store.
	 */
	private suspend fun computeRecommendedExtensions(
		installedPkgs: Set<String>,
		installedIds: Set<Long>,
		inProgress: Set<String>,
		query: String?,
		repoUrl: String?,
		repoSourceIndex: Map<Long, Pair<String, String>>,
		storeId: String,
		action: SourceCatalogItem.Extension.Action,
		isPrivateMode: Boolean,
	): List<SourceCatalogItem.Extension> {
		val sources = runCatching {
			mangaDatabase.getMangaDao().findExternalSourcesInLibrary()
		}.getOrDefault(emptyList())
		if (sources.isEmpty()) return emptyList()
		val out = ArrayList<SourceCatalogItem.Extension>()
		for ((pkg, displayName) in collectRecommendedExtensionRefs(sources, installedIds, installedPkgs, repoSourceIndex)) {
			if (query != null &&
				!displayName.contains(query, ignoreCase = true) &&
				!pkg.contains(query, ignoreCase = true)
			) {
				continue
			}
			out += SourceCatalogItem.Extension(
				packageName = pkg,
				title = displayName,
				subtitle = appContext.getString(R.string.recommended_extension_subtitle),
				action = action,
				isInProgress = pkg in inProgress,
				// Real extension icon when a repo is configured; otherwise the row falls back to a
				// generated favicon (handled in the adapter).
				iconUrl = repoUrl?.takeIf { it.isNotBlank() }?.let { externalRepoRepository.resolveIconUrl(it, pkg) },
				storeId = storeId,
				isPrivateMode = isPrivateMode,
			)
		}
		out.sortBy { it.title.lowercase() }
		return out
	}

	private suspend fun buildPrivateExtensionsList(
		filter: SourcesCatalogFilter,
		query: String?,
		storeState: ExtensionStoreState,
		showUpdatesTab: Boolean,
	): List<ListModel> {
		val repoUrl = storeState.store.indexUrl
		val available = storeState.catalog
		availableRepoEntries.value = available

		val installed = mihonExtensionLoader.getInstalledExtensions(appContext, privateMode = true)
			.associateBy { it.pkgName }
		val availableByPkg = available.associateBy { it.packageName }
		val installedSourcesByPkg = mihonSources.value.groupBy { it.pkgName }
		val allInstalledSourcesByPkg = allMihonSources.value.groupBy { it.pkgName }
		val inProgressPackages = installingPackages.value
		val locale = filter.locale
		val q = query?.takeIf { it.isNotBlank() }

		val enabledItems = ArrayList<SourceCatalogItem.Extension>()
		val updateItems = ArrayList<SourceCatalogItem.Extension>()
		for (local in installed.values) {
			val owner = storeManager.owner(ExtensionInstallMode.SANDBOX, local) ?: continue
			if (owner.id != storeState.store.id) continue
			if (settings.isNsfwContentDisabled && local.isNsfw) continue
			val pkgAllSources = allInstalledSourcesByPkg[local.pkgName].orEmpty()
			if (locale != null && local.lang != locale && pkgAllSources.none { it.language == locale }) continue
			if (q != null && !local.appName.contains(q, ignoreCase = true) && !local.pkgName.contains(q, ignoreCase = true)) continue
			val pkgSources = allInstalledSourcesByPkg[local.pkgName] ?: installedSourcesByPkg[local.pkgName]
			val source = pkgSources?.firstOrNull { it.language == local.lang } ?: pkgSources?.firstOrNull()
			val update = findStoreTabUpdate(local, available, showUpdatesTab)
			val repoLabel = owner.displayName
			val subtitle = buildString {
				append(getExternalExtensionLanguageDisplayName(local.lang))
				append(" • ")
				append(update?.versionName ?: local.versionName)
				if (local.isNsfw) append(" • 18+")
				append(" • ")
				append(repoLabel)
			}
			val item = SourceCatalogItem.Extension(
				packageName = local.pkgName,
				title = (update?.name ?: local.appName).removePrefix("Tachiyomi: ").trim(),
				subtitle = subtitle,
				action = if (update == null) SourceCatalogItem.Extension.Action.DISABLE else SourceCatalogItem.Extension.Action.UPDATE,
				isInProgress = local.pkgName in inProgressPackages,
				iconUrl = update?.iconUrl ?: availableByPkg[local.pkgName]?.iconUrl
					?: externalRepoRepository.resolveIconUrl(repoUrl, local.pkgName),
				sourceIconName = source?.name,
				sourceName = source?.name,
				storeId = owner.id,
				isHidden = settings.isMihonPackageHidden(local.pkgName),
				isPrivateMode = true,
			)
			if (update == null) enabledItems += item else updateItems += item
		}

		val installedIds = allMihonSources.value.mapTo(HashSet()) { it.sourceId }
		val repoSourceIndex = HashMap<Long, Pair<String, String>>()
		for (entry in available) {
			val fallbackName = entry.name.removePrefix("Tachiyomi: ").trim()
			for (src in entry.sources) {
				val sid = src.id.toLongOrNull() ?: continue
				repoSourceIndex.putIfAbsent(sid, entry.packageName to src.name.ifBlank { fallbackName })
			}
		}
		val recommended = computeRecommendedExtensions(
			installedPkgs = installed.keys,
			installedIds = installedIds,
			inProgress = inProgressPackages,
			query = q,
			repoUrl = repoUrl,
			repoSourceIndex = repoSourceIndex,
			storeId = storeState.store.id,
			action = SourceCatalogItem.Extension.Action.ENABLE,
			isPrivateMode = true,
		)
		val recommendedPackages = recommended.mapTo(HashSet(recommended.size)) { it.packageName }

		val disabledItems = ArrayList<SourceCatalogItem.Extension>()
		for (entry in available) {
			if (entry.packageName in recommendedPackages) continue
			val installedOwner = installed[entry.packageName]?.let {
				storeManager.owner(ExtensionInstallMode.SANDBOX, it)
			}
			if (installedOwner?.id == storeState.store.id) continue
			if (settings.isNsfwContentDisabled && entry.isNsfw != 0) continue
			if (locale != null && entry.lang != locale) continue
			if (q != null && !entry.name.contains(q, ignoreCase = true) && !entry.packageName.contains(q, ignoreCase = true)) continue
			val pkgSources = allInstalledSourcesByPkg[entry.packageName] ?: installedSourcesByPkg[entry.packageName]
			val source = pkgSources?.firstOrNull { it.language == entry.lang } ?: pkgSources?.firstOrNull()
			val subtitle = buildString {
				append(getExternalExtensionLanguageDisplayName(entry.lang.orEmpty()))
				append(" • ")
				append(entry.versionName)
				if (entry.isNsfw != 0) append(" • 18+")
			}
			val iconUrl = entry.iconUrl ?: externalRepoRepository.resolveIconUrl(repoUrl, entry.packageName)
			disabledItems += SourceCatalogItem.Extension(
				packageName = entry.packageName,
				title = entry.name.removePrefix("Tachiyomi: ").trim(),
				subtitle = subtitle,
				action = SourceCatalogItem.Extension.Action.ENABLE,
				isInProgress = entry.packageName in inProgressPackages,
				iconUrl = iconUrl,
				sourceIconName = source?.name,
				storeId = storeState.store.id,
				isPrivateMode = true,
			)
		}

		val titleComparator = Comparator<SourceCatalogItem.Extension> { a, b -> a.title.compareTo(b.title, ignoreCase = true) }
		enabledItems.sortWith(titleComparator)
		disabledItems.sortWith(titleComparator)

		return buildList {
			if (storeState.health == StoreHealth.REMOVED) {
				add(SourceCatalogItem.Hint(R.drawable.ic_error_large, R.string.store_removed, R.string.store_removed_catalog_warning))
			} else if (storeState.health == StoreHealth.UNAVAILABLE) {
				add(SourceCatalogItem.Hint(R.drawable.ic_error_large, R.string.error, R.string.extensions_repo_load_error))
			}
			if (updateItems.isNotEmpty()) {
				add(ListHeader(R.string.updates_pending))
				addAll(updateItems.sortedWith(titleComparator))
			}
			if (enabledItems.isNotEmpty()) {
				add(org.koitharu.kotatsu.list.ui.model.ListHeader(R.string.enabled))
				addAll(enabledItems)
			}
			if (recommended.isNotEmpty()) {
				add(org.koitharu.kotatsu.list.ui.model.ListHeader(R.string.recommended_to_install))
				addAll(recommended)
			}
			if (disabledItems.isNotEmpty()) {
				add(org.koitharu.kotatsu.list.ui.model.ListHeader(R.string.disabled_extensions))
				addAll(disabledItems)
			}
			if (isEmpty()) {
				add(SourceCatalogItem.Hint(R.drawable.ic_empty_feed, R.string.nothing_found, R.string.no_manga_sources_found))
			}
		}
	}

	suspend fun getMigrationExtensionCount(): Int {
		val systemInstalled = mihonExtensionLoader.getInstalledExtensions(appContext, privateMode = false)
			.map { it.pkgName }
			.toMutableSet()
		runCatching {
			val sources = mangaDatabase.getMangaDao().findExternalSourcesInLibrary()
			val installedIds = allMihonSources.value.mapTo(HashSet()) { it.sourceId }
			for (name in sources) {
				val id = name.removePrefix("MIHON_").substringBefore(':').toLongOrNull() ?: continue
				if (id in installedIds) continue
				val ref = kotatsuSourceMap.resolveById(id) ?: continue
				systemInstalled.add(ref.packageName)
			}
		}
		return systemInstalled.size
	}

	fun onPrivateExtensionChanged() {
		launchJob(Dispatchers.Default) {
			try {
				repository.reloadMihonSources()
			} finally {
				refreshTrigger.value++
			}
		}
	}

	suspend fun reloadAndCheckMigration(): Int {
		repository.reloadMihonSources()
		val hasPrivate = MihonExtensionLoader.hasPrivateExtensions(appContext)
		if (hasPrivate) return 0
		return getMigrationExtensionCount()
	}

	fun performMigration() {
		launchJob(Dispatchers.Default) {
			emitMigrationInstallRequests()
		}
	}

	fun onPrivateModeDisabled() {
		isRefreshing.value = true
		launchJob(Dispatchers.Default) {
			try {
				repository.reloadMihonSources()
			} finally {
				refreshTrigger.value++
			}
		}
	}

	private suspend fun emitMigrationInstallRequests() {
		val systemInstalled = mihonExtensionLoader.getInstalledExtensions(appContext, privateMode = false)
			.associateBy { it.pkgName }
		val installedIds = allMihonSources.value.mapTo(HashSet()) { it.sourceId }
		val migrationPkgs = mutableSetOf<String>()
		migrationPkgs.addAll(systemInstalled.keys)
		runCatching {
			val sources = mangaDatabase.getMangaDao().findExternalSourcesInLibrary()
			for (name in sources) {
				val id = name.removePrefix("MIHON_").substringBefore(':').toLongOrNull() ?: continue
				if (id in installedIds) continue
				val ref = kotatsuSourceMap.resolveById(id) ?: continue
				migrationPkgs.add(ref.packageName)
			}
		}
		val statesById = storeManager.states.value.associateBy { it.store.id }
		val requests = migrationPkgs.mapNotNull { packageName ->
			val owner = systemInstalled[packageName]
				?.let { storeManager.owner(ExtensionInstallMode.SYSTEM, it) }
				?: storeManager.owner(ExtensionInstallMode.SYSTEM, packageName)
				?: return@mapNotNull null
			val state = statesById[owner.id] ?: return@mapNotNull null
			if (!owner.enabled || state.health != StoreHealth.AVAILABLE) return@mapNotNull null
			val entry = state.catalog.firstOrNull { it.packageName == packageName } ?: return@mapNotNull null
			InstallRequest(
				packageName = packageName,
				url = externalRepoRepository.resolveApkUrl(owner.indexUrl, entry.apkName),
				storeId = owner.id,
				mode = ExtensionInstallMode.SANDBOX,
			)
		}
		if (requests.isNotEmpty()) {
			emitInstallRequests(requests)
		}
	}

	data class InstallRequest(
		val packageName: String,
		val url: String,
		val storeId: String,
		val mode: ExtensionInstallMode,
		val replacement: ProviderReplacement? = null,
	)

	data class ProviderReplacement(
		val currentStoreName: String,
		val newStoreName: String,
	)

	data class CatalogPageContent(
		val pageId: String,
		val items: List<ListModel>,
	)
}
