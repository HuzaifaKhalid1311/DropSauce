package org.koitharu.kotatsu.settings.sources.catalog

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
import org.koitharu.kotatsu.list.ui.model.ButtonFooter
import org.koitharu.kotatsu.lnreader.LnPluginManager
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageLabel
import org.koitharu.kotatsu.lnreader.model.langCode
import org.koitharu.kotatsu.lnreader.model.languageLabel
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
	private val lnPluginManager: LnPluginManager,
) : BaseViewModel() {

	private val appContext = context
	private val defaultLocales: Set<String?> = setOf(null)
	private val mihonSources = repository.observeMihonSources()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, emptyList<org.koitharu.kotatsu.mihon.model.MihonMangaSource>())
	private val allMihonSources = repository.observeAllMihonSources()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, emptyList<org.koitharu.kotatsu.mihon.model.MihonMangaSource>())
	private val availableRepoEntries = MutableStateFlow<List<ExternalExtensionRepoEntry>>(emptyList())

	private val searchQuery = MutableStateFlow<String?>(null)
	private val activePageId = MutableStateFlow(ExtensionCatalogPage.Available.id)
	private val installingPackages = MutableStateFlow<Set<String>>(emptySet())
	private val refreshTrigger = MutableStateFlow(0)
	val isRefreshing = MutableStateFlow(false)
	val isNsfwDisabled = settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, settings.isNsfwContentDisabled)
	val isPrivateMode = settings.observeAsFlow(AppSettings.KEY_PRIVATE_INSTALLER) { isPrivateInstallEnabled }
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, settings.isPrivateInstallEnabled)
	val appliedFilter = MutableStateFlow(
		SourcesCatalogFilter(
			types = emptySet(),
			locale = null,
		),
	)
	val onOpenPackageInstaller = MutableEventFlow<List<InstallRequest>>()
	val onOpenUninstall = MutableEventFlow<String>()
	val onShowMessage = MutableEventFlow<Int>()

	/** Source name of a freshly installed extension, so the caller can offer to open it. */
	val onExtensionInstalled = MutableEventFlow<String>()
	val pages: StateFlow<List<ExtensionCatalogPage>> = storeManager.states.map { states ->
		buildExtensionCatalogPages(states.map { it.store })
	}.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		listOf(ExtensionCatalogPage.Available),
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
	) { args ->
		val q = args[0] as String?
		val f = args[1] as SourcesCatalogFilter
		val privateMode = args[7] as Boolean
		val pageId = args[8] as String
		@Suppress("UNCHECKED_CAST")
		val storeStates = args[9] as List<ExtensionStoreState>
		val mode = if (privateMode) ExtensionInstallMode.SANDBOX else ExtensionInstallMode.SYSTEM
		val result = buildPage(pageId, storeStates, mode, f, q)
		isRefreshing.value = false
		CatalogPageContent(pageId, result)
	}.stateIn(
		viewModelScope + Dispatchers.Default,
		SharingStarted.Eagerly,
		CatalogPageContent(ExtensionCatalogPage.Available.id, listOf(LoadingState)),
	)

	val hasUpdates = content.map { page ->
		page.items.any { it is SourceCatalogItem.Extension && it.action == SourceCatalogItem.Extension.Action.UPDATE }
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	init {
		launchJob(Dispatchers.Default) {
			storeManager.initialize()
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

	fun hasExternalRepoConfigured(): Boolean = storeManager.stores().isNotEmpty()

	fun setNsfwDisabled(value: Boolean) {
		settings.isNsfwContentDisabled = value
	}

	/** Hides or shows an installed extension in Explore. It stays listed in the manager. */
	fun setExtensionHidden(packageName: String, hidden: Boolean) {
		if (lnPluginManager.isInstalled(packageName)) {
			val before = settings.lnHiddenPlugins
			settings.lnHiddenPlugins = if (hidden) before + packageName else before - packageName
			refreshTrigger.value++
			return
		}
		settings.setMihonPackageHidden(packageName, hidden)
	}

	fun onInstallEntryClick(item: SourceCatalogItem.Extension) {
		if (item.isInProgress) {
			return
		}
		launchJob(Dispatchers.Default) {
			// A novel plugin is a file in our own filesDir, so uninstall needs no PackageManager trip.
			if (item.action == SourceCatalogItem.Extension.Action.UNINSTALL &&
				lnPluginManager.isInstalled(item.packageName)
			) {
				lnPluginManager.uninstall(item.packageName)
				refreshTrigger.value++
				return@launchJob
			}
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
				if (state.health != StoreHealth.AVAILABLE) return@mapNotNull null
				val entry = state.catalog.firstOrNull {
					it.packageName == local.pkgName && it.isNewerThan(local)
				} ?: return@mapNotNull null
				InstallRequest(
					packageName = entry.packageName,
					url = externalRepoRepository.resolveApkUrl(owner.indexUrl, entry.apkName),
					storeId = owner.id,
					mode = mode,
				)
			} + collectNovelUpdateRequests(statesById, mode)
			if (requests.isEmpty()) {
				onShowMessage.call(R.string.nothing_found)
				return@launchJob
			}
			emitInstallRequests(requests)
		}
	}

	/**
	 * Novel plugins are not package installs, so they are invisible to the PackageManager sweep above —
	 * without this, "Update all" reported "nothing found" whenever the only pending updates were novels.
	 * A plugin can be updated from any store that carries a newer version, not just the one it came
	 * from, since there is no signature to keep consistent.
	 */
	private fun collectNovelUpdateRequests(
		statesById: Map<String, ExtensionStoreState>,
		mode: ExtensionInstallMode,
	): List<InstallRequest> = lnPluginManager.getAll().mapNotNull { source ->
		val plugin = source.plugin
		val preferred = plugin.storeId?.let { statesById[it] }
		val candidates = listOfNotNull(preferred) + statesById.values.filter { it !== preferred }
		for (state in candidates) {
			if (state.health != StoreHealth.AVAILABLE) continue
			val entry = state.catalog.firstOrNull {
				it.isLnPlugin && it.packageName == plugin.id && isNewerPluginVersion(it.versionName, plugin.version)
			} ?: continue
			return@mapNotNull InstallRequest(
				packageName = entry.packageName,
				url = entry.apkName,
				storeId = state.store.id,
				mode = mode,
				iconUrl = entry.iconUrl,
				lang = entry.lang,
			)
		}
		null
	}

	private fun createInstallRequest(item: SourceCatalogItem.Extension): InstallRequest? {
		val store = item.storeId?.let { id -> storeManager.stores().firstOrNull { it.id == id } }
		if (store == null) {
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
		if (entry.isLnPlugin) {
			// No PackageManager round trip and no provider-replacement prompt: the plugin is a file we
			// own, so the store's icon/lang ride along for the manifest we write at install time.
			return InstallRequest(
				packageName = item.packageName,
				url = entry.apkName,
				storeId = store.id,
				mode = mode,
				iconUrl = entry.iconUrl,
				lang = entry.lang,
			)
		}
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
						currentOwner?.displayName ?: appContext.getString(R.string.no_source),
						store.displayName,
					)
				},
		)
	}

	/**
	 * Resolves the source a just-installed extension provides and announces it. An extension with no
	 * usable source (failed load, or a package whose sources are all filtered out) announces nothing.
	 */
	fun notifyExtensionInstalled(packageName: String) {
		launchJob(Dispatchers.Default) {
			val plugin = lnPluginManager.getById(packageName)
			val sourceName = if (plugin != null) {
				plugin.name
			} else {
				// The install finished a moment ago; the extension list may not have been re-read yet.
				repository.reloadMihonSources()
				repository.observeAllMihonSources().first()
					.firstOrNull { it.pkgName == packageName }
					?.name
			}
			onExtensionInstalled.call(sourceName ?: return@launchJob)
		}
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

	private fun buildAvailablePage(
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
		val updates = ArrayList<SourceCatalogItem.Extension>()
		val installedItems = ArrayList<SourceCatalogItem.Extension>()
		for (local in installed) {
			val owner = storeManager.owner(mode, local)
			val state = owner?.let { statesById[it.id] }
			val entry = state
				?.takeIf { canUseStoreCatalogForUpdates(it.health) }
				?.catalog
				?.firstOrNull { it.packageName == local.pkgName && it.isNewerThan(local) }
			val source = installedSourcesByPackage[local.pkgName]
				?.firstOrNull { it.language == local.lang }
				?: installedSourcesByPackage[local.pkgName]?.firstOrNull()
			if (settings.isNsfwContentDisabled && local.isNsfw) continue
			if (filter.locale != null && local.lang != filter.locale) continue
			if (!matchesExtensionQuery(q, local.appName, local.pkgName)) continue
			val ownerSuffix = owner?.displayName?.let { " • $it" }.orEmpty()
			if (entry != null) {
				updates += SourceCatalogItem.Extension(
					packageName = entry.packageName,
					title = extensionDisplayName(entry.name),
					subtitle = buildString {
						append(getExternalExtensionLanguageDisplayName(entry.lang.orEmpty()))
						append(" • ")
						append(entry.versionName)
						append(ownerSuffix)
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
			}
			installedItems += SourceCatalogItem.Extension(
				packageName = local.pkgName,
				title = extensionDisplayName(local.appName),
				subtitle = buildString {
					append(getExternalExtensionLanguageDisplayName(local.lang))
					append(" • ")
					append(local.versionName)
					if (local.isNsfw) append(" • 18+")
					append(ownerSuffix)
				},
				action = if (mode == ExtensionInstallMode.SANDBOX) {
					SourceCatalogItem.Extension.Action.DISABLE
				} else {
					SourceCatalogItem.Extension.Action.UNINSTALL
				},
				isInProgress = local.pkgName in inProgress,
				iconUrl = entry?.iconUrl ?: owner?.let {
					externalRepoRepository.resolveIconUrl(it.indexUrl, local.pkgName)
				},
				sourceIconName = source?.name,
				sourceName = source?.name,
				storeId = owner?.id,
				isHidden = settings.isMihonPackageHidden(local.pkgName),
				isPrivateMode = mode == ExtensionInstallMode.SANDBOX,
			)
		}
		// Novel plugins live in our own filesDir, so they are listed straight from the plugin manager
		// rather than through the PackageManager-shaped path above.
		val lnCatalog = storeStates.flatMap { it.catalog }.filter { it.isLnPlugin }
		for (source in lnPluginManager.getAll()) {
			val plugin = source.plugin
			if (filter.locale != null && plugin.langCode != filter.locale) continue
			if (!matchesExtensionQuery(q, plugin.name, plugin.id)) continue
			val newer = lnCatalog.firstOrNull {
				it.packageName == plugin.id && isNewerPluginVersion(it.versionName, plugin.version)
			}
			val owner = plugin.storeId?.let { id -> statesById[id]?.store }
			// Plugins installed before the manifest carried an icon still get one from the index.
			val iconUrl = plugin.iconUrl.takeIf { it.isNotEmpty() }
				?: lnCatalog.firstOrNull { it.packageName == plugin.id }?.iconUrl
			if (newer != null) {
				updates += newer.toLnCatalogItem(
					storeId = storeStates.firstOrNull { state -> state.catalog.any { it === newer } }?.store?.id,
					action = SourceCatalogItem.Extension.Action.UPDATE,
					isInProgress = plugin.id in inProgress,
					isHidden = plugin.id in settings.lnHiddenPlugins,
					sourceName = source.name,
				)
			}
			installedItems += SourceCatalogItem.Extension(
				packageName = plugin.id,
				title = plugin.name,
				subtitle = buildString {
					plugin.languageLabel.takeIf { it.isNotEmpty() }?.let { append(it).append(" • ") }
					append(plugin.version)
					owner?.displayName?.let { append(" • ").append(it) }
				},
				action = SourceCatalogItem.Extension.Action.UNINSTALL,
				isInProgress = plugin.id in inProgress,
				iconUrl = iconUrl,
				// Makes the row open the novel's browse list and its settings, like a Mihon source.
				sourceIconName = source.name,
				sourceName = source.name,
				storeId = plugin.storeId,
				isHidden = plugin.id in settings.lnHiddenPlugins,
			)
		}
		val byTitle = compareBy<SourceCatalogItem.Extension> { it.title.lowercase() }
		return buildAvailablePageItems(
			updates.sortedWith(byTitle),
			installedItems.sortedWith(byTitle),
			isPrivateMode = mode == ExtensionInstallMode.SANDBOX,
			hasStores = storeStates.isNotEmpty(),
		)
	}

	private suspend fun buildPage(
		pageId: String,
		storeStates: List<ExtensionStoreState>,
		mode: ExtensionInstallMode,
		filter: SourcesCatalogFilter,
		query: String?,
	): List<ListModel> = when (pageId) {
		ExtensionCatalogPage.Available.id -> buildAvailablePage(storeStates, mode, filter, query)
		ExtensionCatalogPage.Empty.id -> listOf(
			SourceCatalogItem.Hint(
				R.drawable.ic_empty_feed,
				R.string.no_extension_store_found,
				R.string.no_extension_store_found_summary,
			),
		)
		else -> {
			val storeState = storeStates.firstOrNull { it.store.id == pageId }
				?: return listOf(LoadingState)
			if (mode == ExtensionInstallMode.SANDBOX) {
				buildPrivateExtensionsList(filter, query, storeState)
			} else {
				buildExtensionsList(filter, query, storeState)
			}
		}
	}

	private suspend fun buildExtensionsList(
		filter: SourcesCatalogFilter,
		query: String?,
		storeState: ExtensionStoreState,
	): List<ListModel> {
		val repoUrl = storeState.store.indexUrl
		val available = storeState.catalog
		availableRepoEntries.value = available
		val installed = mihonExtensionLoader.getInstalledExtensions(appContext).associateBy { it.pkgName }
		val installedSourcesByPkg = mihonSources.value.groupBy { it.pkgName }
		val allInstalledSourcesByPkg = allMihonSources.value.groupBy { it.pkgName }

		val availableItems = ArrayList<SourceCatalogItem.Extension>()
		val locale = filter.locale
		val q = query?.takeIf { it.isNotBlank() }
		val inProgressPackages = installingPackages.value

		val installedIds = allMihonSources.value.mapTo(HashSet()) { it.sourceId }
		// id -> (package, display name) for every source the configured repo offers, so a
		// MIHON_<id> library entry from ANY origin (DropSauce/GDrive/Kotatsu/Mihon backup) can be
		// recommended even if it isn't in the baked migration map.
		val repoSourceIndex = HashMap<Long, Pair<String, String>>()
		for (entry in available) {
			val fallbackName = extensionDisplayName(entry.name)
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
			lnCatalog = available.filter { it.isLnPlugin },
		)
		val recommendedPackages = recommended.mapTo(HashSet(recommended.size)) { it.packageName }
		val installedItems = buildStoreInstalledItems(
			mode = ExtensionInstallMode.SYSTEM,
			storeState = storeState,
			filter = filter,
			query = q,
		)

		for (entry in available) {
			if (entry.packageName in recommendedPackages) continue // surfaced in the Recommended section
			if (settings.isNsfwContentDisabled && entry.isNsfw != 0) continue
			if (locale != null && entry.lang != locale) continue
			if (!matchesExtensionQuery(q, entry.name, entry.packageName)) continue

			if (entry.isLnPlugin) {
				val installedVersion = lnPluginManager.getById(entry.packageName)?.plugin?.version
				if (installedVersion != null && !isNewerPluginVersion(entry.versionName, installedVersion)) continue
				availableItems += entry.toLnCatalogItem(
					storeId = storeState.store.id,
					action = if (installedVersion == null) {
						SourceCatalogItem.Extension.Action.INSTALL
					} else {
						SourceCatalogItem.Extension.Action.UPDATE
					},
					isInProgress = entry.packageName in inProgressPackages,
				)
				continue
			}

			val local = installed[entry.packageName]
			val localOwner = local?.let { storeManager.owner(ExtensionInstallMode.SYSTEM, it) }
			if (!isStoreInstallCandidate(local != null, localOwner?.id, storeState.store.id)) continue
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
			availableItems += SourceCatalogItem.Extension(
				packageName = entry.packageName,
				title = extensionDisplayName(entry.name),
				subtitle = subtitle,
				action = SourceCatalogItem.Extension.Action.INSTALL,
				isInProgress = entry.packageName in inProgressPackages,
				iconUrl = iconUrl,
				sourceIconName = source?.name,
				storeId = storeState.store.id,
			)
		}

		val titleComparator = Comparator<SourceCatalogItem.Extension> { a, b -> a.title.compareTo(b.title, ignoreCase = true) }
		availableItems.sortWith(titleComparator)

		return buildList {
			// The "no repository" / error hint always stays pinned at the very top.
			if (storeState.health == StoreHealth.UNAVAILABLE) {
				add(
					SourceCatalogItem.Hint(
						icon = R.drawable.ic_error_large,
						title = R.string.error,
						text = R.string.extensions_repo_load_error,
					),
				)
			}
			if (recommended.isNotEmpty()) {
				add(org.koitharu.kotatsu.list.ui.model.ListHeader(R.string.recommended_to_install))
				addAll(recommended)
			}
			if (installedItems.isNotEmpty()) {
				add(ListHeader(R.string.installed))
				addAll(installedItems)
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
	 * Extensions the user's library needs but that aren't installed: derived from `MIHON_<id>` and
	 * `LN_<pluginId>` sources referenced by favourites/history and offered by the current store.
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
		lnCatalog: List<ExternalExtensionRepoEntry>,
	): List<SourceCatalogItem.Extension> {
		val sources = runCatching {
			mangaDatabase.getMangaDao().findExternalSourcesInLibrary()
		}.getOrDefault(emptyList())
		if (sources.isEmpty()) return emptyList()
		val out = ArrayList<SourceCatalogItem.Extension>()
		// A novel plugin is never a package install, so it can't go through the id-based Mihon mapping
		// below: the library stores the plugin id itself, which is exactly what the index is keyed by.
		val wantedPlugins = sources.filter { it.startsWith(LN_SOURCE_PREFIX) }
			.mapTo(HashSet()) { it.removePrefix(LN_SOURCE_PREFIX) }
		for (entry in lnCatalog) {
			if (entry.packageName !in wantedPlugins) continue
			if (lnPluginManager.isInstalled(entry.packageName)) continue
			if (!matchesExtensionQuery(query, entry.name, entry.packageName)) continue
			out += SourceCatalogItem.Extension(
				packageName = entry.packageName,
				title = entry.name,
				subtitle = appContext.getString(R.string.recommended_extension_subtitle),
				action = SourceCatalogItem.Extension.Action.INSTALL,
				isInProgress = entry.packageName in inProgress,
				iconUrl = entry.iconUrl,
				storeId = storeId,
				isPrivateMode = isPrivateMode,
			)
		}
		for ((pkg, displayName) in collectRecommendedExtensionRefs(sources, installedIds, installedPkgs, repoSourceIndex)) {
			if (!matchesExtensionQuery(query, displayName, pkg)) continue
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
	): List<ListModel> {
		val repoUrl = storeState.store.indexUrl
		val available = storeState.catalog
		availableRepoEntries.value = available

		val installed = mihonExtensionLoader.getInstalledExtensions(appContext, privateMode = true)
			.associateBy { it.pkgName }
		val installedSourcesByPkg = mihonSources.value.groupBy { it.pkgName }
		val allInstalledSourcesByPkg = allMihonSources.value.groupBy { it.pkgName }
		val inProgressPackages = installingPackages.value
		val locale = filter.locale
		val q = query?.takeIf { it.isNotBlank() }

		val installedIds = allMihonSources.value.mapTo(HashSet()) { it.sourceId }
		val repoSourceIndex = HashMap<Long, Pair<String, String>>()
		for (entry in available) {
			val fallbackName = extensionDisplayName(entry.name)
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
			lnCatalog = available.filter { it.isLnPlugin },
		)
		val recommendedPackages = recommended.mapTo(HashSet(recommended.size)) { it.packageName }
		val installedItems = buildStoreInstalledItems(
			mode = ExtensionInstallMode.SANDBOX,
			storeState = storeState,
			filter = filter,
			query = q,
		)

		val disabledItems = ArrayList<SourceCatalogItem.Extension>()
		for (entry in available) {
			if (entry.packageName in recommendedPackages) continue
			// Novel plugins are never package installs, so private mode does not change how they load.
			if (entry.isLnPlugin) {
				val installedVersion = lnPluginManager.getById(entry.packageName)?.plugin?.version
				if (installedVersion != null && !isNewerPluginVersion(entry.versionName, installedVersion)) continue
				if (locale != null && entry.lang != locale) continue
				if (!matchesExtensionQuery(q, entry.name, entry.packageName)) continue
				disabledItems += entry.toLnCatalogItem(
					storeId = storeState.store.id,
					action = if (installedVersion == null) {
						SourceCatalogItem.Extension.Action.INSTALL
					} else {
						SourceCatalogItem.Extension.Action.UPDATE
					},
					isInProgress = entry.packageName in inProgressPackages,
				)
				continue
			}
			val installedOwner = installed[entry.packageName]?.let {
				storeManager.owner(ExtensionInstallMode.SANDBOX, it)
			}
			if (!isStoreInstallCandidate(
					isInstalled = entry.packageName in installed,
					ownerStoreId = installedOwner?.id,
					currentStoreId = storeState.store.id,
				)
			) {
				continue
			}
			if (settings.isNsfwContentDisabled && entry.isNsfw != 0) continue
			if (locale != null && entry.lang != locale) continue
			if (!matchesExtensionQuery(q, entry.name, entry.packageName)) continue
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
				title = extensionDisplayName(entry.name),
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
		disabledItems.sortWith(titleComparator)

		return buildList {
			if (storeState.health == StoreHealth.UNAVAILABLE) {
				add(SourceCatalogItem.Hint(R.drawable.ic_error_large, R.string.error, R.string.extensions_repo_load_error))
			}
			if (recommended.isNotEmpty()) {
				add(org.koitharu.kotatsu.list.ui.model.ListHeader(R.string.recommended_to_install))
				addAll(recommended)
			}
			if (installedItems.isNotEmpty()) {
				add(ListHeader(R.string.installed))
				addAll(installedItems)
			}
			if (disabledItems.isNotEmpty()) {
				add(org.koitharu.kotatsu.list.ui.model.ListHeader(R.string.available_to_install))
				addAll(disabledItems)
			}
			if (isEmpty()) {
				add(SourceCatalogItem.Hint(R.drawable.ic_empty_feed, R.string.nothing_found, R.string.no_manga_sources_found))
			}
		}
	}

	private fun buildStoreInstalledItems(
		mode: ExtensionInstallMode,
		storeState: ExtensionStoreState,
		filter: SourcesCatalogFilter,
		query: String?,
	): List<SourceCatalogItem.Extension> {
		val privateMode = mode == ExtensionInstallMode.SANDBOX
		val sourcesByPackage = allMihonSources.value.groupBy { it.pkgName }
		val items = mihonExtensionLoader.getInstalledExtensions(appContext, privateMode)
			.mapNotNull { local ->
				val owner = storeManager.owner(mode, local) ?: return@mapNotNull null
				if (owner.id != storeState.store.id) return@mapNotNull null
				if (settings.isNsfwContentDisabled && local.isNsfw) return@mapNotNull null
				if (filter.locale != null && local.lang != filter.locale) return@mapNotNull null
				if (!matchesExtensionQuery(query, local.appName, local.pkgName)) return@mapNotNull null
				val entry = storeState.catalog.firstOrNull { it.packageName == local.pkgName }
				val source = sourcesByPackage[local.pkgName]
					?.firstOrNull { it.language == local.lang }
					?: sourcesByPackage[local.pkgName]?.firstOrNull()
				SourceCatalogItem.Extension(
					packageName = local.pkgName,
					title = extensionDisplayName(local.appName),
					subtitle = buildString {
						append(getExternalExtensionLanguageDisplayName(local.lang))
						append(" • ").append(local.versionName)
						if (local.isNsfw) append(" • 18+")
						append(" • ").append(owner.displayName)
					},
					action = if (privateMode) SourceCatalogItem.Extension.Action.DISABLE
					else SourceCatalogItem.Extension.Action.UNINSTALL,
					isInProgress = local.pkgName in installingPackages.value,
					iconUrl = entry?.iconUrl ?: externalRepoRepository.resolveIconUrl(owner.indexUrl, local.pkgName),
					sourceIconName = source?.name,
					sourceName = source?.name,
					storeId = owner.id,
					isHidden = settings.isMihonPackageHidden(local.pkgName),
					isPrivateMode = privateMode,
				)
			}.toMutableList()
		for (source in lnPluginManager.getAll()) {
			val plugin = source.plugin
			if (plugin.storeId != storeState.store.id) continue
			if (filter.locale != null && plugin.langCode != filter.locale) continue
			if (!matchesExtensionQuery(query, plugin.name, plugin.id)) continue
			val entry = storeState.catalog.firstOrNull { it.packageName == plugin.id }
			items += SourceCatalogItem.Extension(
				packageName = plugin.id,
				title = plugin.name,
				subtitle = buildString {
					plugin.languageLabel.takeIf { it.isNotEmpty() }?.let { append(it).append(" • ") }
					append(plugin.version).append(" • ").append(storeState.store.displayName)
				},
				action = SourceCatalogItem.Extension.Action.UNINSTALL,
				isInProgress = plugin.id in installingPackages.value,
				iconUrl = plugin.iconUrl.takeIf { it.isNotEmpty() } ?: entry?.iconUrl,
				sourceIconName = source.name,
				sourceName = source.name,
				storeId = plugin.storeId,
				isHidden = plugin.id in settings.lnHiddenPlugins,
				isPrivateMode = privateMode,
			)
		}
		return items.sortedBy { it.title.lowercase() }
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
			if (state.health != StoreHealth.AVAILABLE) return@mapNotNull null
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
		/** Store-supplied metadata a novel plugin's own code does not carry. Null for APK installs. */
		val iconUrl: String? = null,
		val lang: String? = null,
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

/** Library rows for novel plugins are stored as `LN_<pluginId>`. */
private const val LN_SOURCE_PREFIX = "LN_"

internal fun buildAvailablePageItems(
	updates: List<SourceCatalogItem.Extension>,
	installed: List<SourceCatalogItem.Extension>,
	isPrivateMode: Boolean,
	hasStores: Boolean = true,
): List<ListModel> = buildList {
	if (!hasStores) {
		add(
			SourceCatalogItem.Hint(
				R.drawable.ic_empty_feed,
				R.string.no_extension_store_found,
				R.string.no_extension_store_found_summary,
			),
		)
	}
	if (updates.isNotEmpty()) {
		add(ListHeader(R.string.updates_available))
		addAll(updates)
		add(ButtonFooter(R.string.update_all))
	}
	if (installed.isNotEmpty()) {
		add(ListHeader(if (isPrivateMode) R.string.enabled else R.string.installed))
		addAll(installed)
	}
	if (isEmpty()) {
		add(SourceCatalogItem.Hint(R.drawable.ic_empty_feed, R.string.nothing_found, R.string.no_manga_sources_found))
	}
}

private fun ExternalExtensionRepoEntry.toLnCatalogItem(
	storeId: String?,
	action: SourceCatalogItem.Extension.Action,
	isInProgress: Boolean,
	isHidden: Boolean = false,
	sourceName: String? = null,
) = SourceCatalogItem.Extension(
	packageName = packageName,
	title = extensionDisplayName(name),
	subtitle = buildString {
		lang?.takeIf { it.isNotEmpty() }?.let { append(getExternalExtensionLanguageLabel(it)).append(" • ") }
		append(versionName)
	},
	action = action,
	isInProgress = isInProgress,
	iconUrl = iconUrl,
	sourceIconName = sourceName,
	sourceName = sourceName,
	storeId = storeId,
	isHidden = isHidden,
)

/**
 * Dotted-number comparison. Plugins have no version code, and a plain string `!=` would advertise a
 * downgrade as an update whenever a store rolls a plugin back.
 */
internal fun isNewerPluginVersion(available: String, local: String): Boolean {
	val a = available.split('.', '-').mapNotNull { it.toIntOrNull() }
	val b = local.split('.', '-').mapNotNull { it.toIntOrNull() }
	for (i in 0 until maxOf(a.size, b.size)) {
		val left = a.getOrElse(i) { 0 }
		val right = b.getOrElse(i) { 0 }
		if (left != right) return left > right
	}
	return false
}

internal fun isStoreInstallCandidate(
	isInstalled: Boolean,
	ownerStoreId: String?,
	currentStoreId: String,
): Boolean = !isInstalled || ownerStoreId != currentStoreId
