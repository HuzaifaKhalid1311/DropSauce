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
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionRepoDisplayName
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
	private val externalRepoUrl = MutableStateFlow(settings.externalExtensionsRepoUrl)
	private val installingPackages = MutableStateFlow<Set<String>>(emptySet())
	private val refreshTrigger = MutableStateFlow(0)
	private var lastRefreshTrigger = 0
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
		allMihonSources,
		externalRepoUrl,
		installingPackages,
		refreshTrigger,
		settings.observeAsFlow(AppSettings.KEY_MIHON_HIDDEN_PACKAGES) { mihonHiddenPackages },
		isPrivateMode,
	) { args ->
		val q = args[0] as String?
		val f = args[1] as SourcesCatalogFilter
		val currentTrigger = args[6] as Int
		val privateMode = args[8] as Boolean
		val forceRefresh = currentTrigger > lastRefreshTrigger
		if (forceRefresh) {
			lastRefreshTrigger = currentTrigger
		}
		val result = if (privateMode) {
			buildPrivateExtensionsList(f, q, forceRefresh)
		} else {
			buildMixedCatalogList(f, q, forceRefresh)
		}
		isRefreshing.value = false
		result
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	val hasUpdates = content.map { items ->
		items.any { it is SourceCatalogItem.Extension && it.action == SourceCatalogItem.Extension.Action.UPDATE }
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	fun performSearch(query: String?) {
		searchQuery.value = query?.trim()
	}

	fun refresh() {
		isRefreshing.value = true
		launchJob(Dispatchers.Default) {
			try {
				repository.reloadMihonSources()
				externalRepoUrl.value?.takeIf { it.isNotBlank() }?.let { repoUrl ->
					runCatching {
						getAvailableEntries(repoUrl, forceRefresh = true)
					}
				}
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

	fun hasExternalRepoConfigured(): Boolean = !externalRepoUrl.value.isNullOrBlank()

	fun getExternalRepoUrl(): String? = externalRepoUrl.value

	fun setExternalRepoUrl(url: String?) {
		settings.externalExtensionsRepoUrl = url
		externalRepoUrl.value = settings.externalExtensionsRepoUrl
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
					val repoUrl = externalRepoUrl.value
					if (repoUrl.isNullOrBlank()) {
						onShowMessage.call(R.string.extensions_repo_required)
						return@launchJob
					}
					val entry = getAvailableEntries(repoUrl, forceRefresh = false).firstOrNull { it.packageName == item.packageName } ?: run {
						onShowMessage.call(R.string.nothing_found)
						return@launchJob
					}
					settings.setExtensionRepoUrl(item.packageName, repoUrl)
					emitInstallRequests(
						listOf(
							InstallRequest(
								packageName = item.packageName,
								url = externalRepoRepository.resolveApkUrl(repoUrl, entry.apkName),
							),
						),
					)
				}
				SourceCatalogItem.Extension.Action.DISABLE -> onOpenUninstall.call(item.packageName)
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
					settings.setExtensionRepoUrl(item.packageName, repoUrl)
					emitInstallRequests(
						listOf(
							InstallRequest(
								packageName = item.packageName,
								url = externalRepoRepository.resolveApkUrl(repoUrl, entry.apkName),
							),
						),
					)
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
			val updateEntries = getUpdatableEntries(repoUrl)
			if (updateEntries.isEmpty()) {
				onShowMessage.call(R.string.nothing_found)
				return@launchJob
			}
			emitInstallRequests(
				updateEntries.map { entry ->
					settings.setExtensionRepoUrl(entry.packageName, repoUrl)
					InstallRequest(
						packageName = entry.packageName,
						url = externalRepoRepository.resolveApkUrl(repoUrl, entry.apkName),
					)
				},
			)
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

	private suspend fun getUpdatableEntries(repoUrl: String): List<ExternalExtensionRepoEntry> {
		val installedByPkg = mihonExtensionLoader.getInstalledExtensions(appContext).associateBy { it.pkgName }
		val activeRepoFingerprint = settings.findRepoInfoByUrl(repoUrl)?.fingerprint
		return getAvailableEntries(repoUrl, forceRefresh = false)
			.filter { entry ->
				val local = installedByPkg[entry.packageName] ?: return@filter false
				// Only update extensions this repo actually signed — skip same-package extensions
				// installed from a different repo (different signer, uninstallable update).
				if (activeRepoFingerprint != null && activeRepoFingerprint !in local.signatures) return@filter false
				entry.isNewerThan(local)
			}
			.sortedBy { it.name.lowercase() }
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

		// Pull the active repo's authoritative name + signing fingerprint once (or on refresh) so
		// installed extensions can be attributed to it by signature. Best-effort — repos without a
		// repo.json just fall back to URL-derived naming below.
		if (!repoUrl.isNullOrBlank() && (forceRefresh || settings.externalRepoInfos.none { it.url == repoUrl })) {
			runCatching { externalRepoRepository.fetchRepoInfo(repoUrl) }.getOrNull()?.let(settings::putExternalRepoInfo)
		}

		// The active repo's signing fingerprint. An entry from this repo can only update/replace an
		// installed extension that this repo actually signed — a same-package extension from a different
		// repo has a different signer, so Android would reject the install. Null = fingerprint unknown
		// (repo has no repo.json), in which case we fall back to package-name matching as before.
		val activeRepoFingerprint = repoUrl?.let { settings.findRepoInfoByUrl(it)?.fingerprint }

		val installed = mihonExtensionLoader.getInstalledExtensions(appContext).associateBy { it.pkgName }
		// Backfill provenance for extensions installed before it was tracked: if the active repo lists
		// the package, attribute it here — a valid update source (OS enforces same-signer) and a name to show.
		val availablePkgs = available.mapTo(HashSet(available.size)) { it.packageName }
		val installedSourcesByPkg = mihonSources.value.groupBy { it.pkgName }
		val allInstalledSourcesByPkg = allMihonSources.value.groupBy { it.pkgName }

		val pending = ArrayList<SourceCatalogItem.Extension>()
		val installedItems = linkedMapOf<String, SourceCatalogItem.Extension>()
		val availableItems = ArrayList<SourceCatalogItem.Extension>()
		val packagesWithUpdates = HashSet<String>()
		val locale = filter.locale
		val q = query?.takeIf { it.isNotBlank() }
		val inProgressPackages = installingPackages.value

		for (local in installed.values) {
			if (settings.isNsfwContentDisabled && local.isNsfw) continue
			val pkgAllSources = allInstalledSourcesByPkg[local.pkgName].orEmpty()
			if (locale != null && local.lang != locale && pkgAllSources.none { it.language == locale }) continue
			if (q != null && !local.appName.contains(q, ignoreCase = true) && !local.pkgName.contains(q, ignoreCase = true)) continue

			val pkgSources = allInstalledSourcesByPkg[local.pkgName] ?: installedSourcesByPkg[local.pkgName]
			val source = pkgSources?.firstOrNull { it.language == local.lang } ?: pkgSources?.firstOrNull()
			// Prefer the repo's real name matched by signing fingerprint (works even for extensions
			// installed before tracking); fall back to install-time provenance / active-repo backfill
			// with a URL-derived name for repos that don't publish a repo.json.
			val repoLabel = settings.findRepoInfoBySignatures(local.signatures)?.displayName
				?: (
					settings.getExtensionRepoUrl(local.pkgName)
						?: repoUrl?.takeIf { local.pkgName in availablePkgs }
							?.also { settings.setExtensionRepoUrl(local.pkgName, it) }
					)?.let { getExternalExtensionRepoDisplayName(it) }
			val subtitle = buildString {
				append(getExternalExtensionLanguageDisplayName(local.lang))
				append(" • ")
				append(local.versionName)
				if (local.isNsfw) {
					append(" • 18+")
				}
				repoLabel?.let {
					append(" • ")
					append(it)
				}
			}
			installedItems[local.pkgName] = SourceCatalogItem.Extension(
				packageName = local.pkgName,
				title = local.appName.removePrefix("Tachiyomi: ").trim(),
				subtitle = subtitle,
				action = SourceCatalogItem.Extension.Action.UNINSTALL,
				isInProgress = local.pkgName in inProgressPackages,
				iconUrl = null,
				sourceIconName = source?.name,
				sourceName = source?.name,
				isHidden = settings.isMihonPackageHidden(local.pkgName),
			)
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
		)
		val recommendedPackages = recommended.mapTo(HashSet(recommended.size)) { it.packageName }

		for (entry in available) {
			if (entry.packageName in recommendedPackages) continue // surfaced in the Recommended section
			if (settings.isNsfwContentDisabled && entry.isNsfw != 0) continue
			if (locale != null && entry.lang != locale) continue
			if (q != null && !entry.name.contains(q, ignoreCase = true) && !entry.packageName.contains(q, ignoreCase = true)) continue

			val local = installed[entry.packageName]
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
			val iconUrl = entry.iconUrl ?: repoUrl?.let { externalRepoRepository.resolveIconUrl(it, entry.packageName) }
			when {
				local == null -> availableItems += SourceCatalogItem.Extension(
					packageName = entry.packageName,
					title = entry.name.removePrefix("Tachiyomi: ").trim(),
					subtitle = subtitle,
					action = SourceCatalogItem.Extension.Action.INSTALL,
					isInProgress = entry.packageName in inProgressPackages,
					iconUrl = iconUrl,
					sourceIconName = source?.name,
				)
				// Installed, but by a different repo (different signer): this repo can't update or
				// replace it, so don't offer a phantom update that Android would reject forever.
				activeRepoFingerprint != null && activeRepoFingerprint !in local.signatures -> Unit
				entry.isNewerThan(local) -> pending += SourceCatalogItem.Extension(
					packageName = entry.packageName,
					title = entry.name.removePrefix("Tachiyomi: ").trim(),
					subtitle = subtitle,
					action = SourceCatalogItem.Extension.Action.UPDATE,
					isInProgress = entry.packageName in inProgressPackages,
					iconUrl = iconUrl,
					sourceIconName = source?.name,
					sourceName = source?.name,
					isHidden = settings.isMihonPackageHidden(entry.packageName),
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
			// The "no repository" / error hint always stays pinned at the very top.
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
						payload = HEADER_ACTION_UPDATE_ALL,
					),
				)
				addAll(pending)
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

	private suspend fun getAvailableEntries(repoUrl: String, forceRefresh: Boolean): List<ExternalExtensionRepoEntry> {
		return externalRepoRepository.getExtensions(repoUrl, forceRefresh)
	}

	/**
	 * Extensions the user's library needs but that aren't installed: derived from `MIHON_<id>`
	 * sources referenced by favourites/history whose id is in the migration map (so we know the
	 * package + name) and isn't currently installed. Shown even when no repository is configured.
	 */
	private suspend fun computeRecommendedExtensions(
		installedPkgs: Set<String>,
		installedIds: Set<Long>,
		inProgress: Set<String>,
		query: String?,
		repoUrl: String?,
		repoSourceIndex: Map<Long, Pair<String, String>>,
	): List<SourceCatalogItem.Extension> {
		val sources = runCatching {
			mangaDatabase.getMangaDao().findExternalSourcesInLibrary()
		}.getOrDefault(emptyList())
		if (sources.isEmpty()) return emptyList()
		val seen = HashSet<String>()
		val out = ArrayList<SourceCatalogItem.Extension>()
		for (name in sources) {
			val id = name.removePrefix("MIHON_").substringBefore(':').toLongOrNull() ?: continue
			if (id in installedIds) continue
			// Prefer the live repo index (complete for the configured repo); fall back to the baked
			// migration map so popular sources are still recommended with no repo configured.
			val ref = repoSourceIndex[id]
				?: kotatsuSourceMap.resolveById(id)?.let { it.packageName to it.sourceName }
				?: continue
			val (pkg, displayName) = ref
			if (pkg.isBlank() || pkg in installedPkgs || !seen.add(pkg)) continue
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
				action = SourceCatalogItem.Extension.Action.INSTALL,
				isInProgress = pkg in inProgress,
				// Real extension icon when a repo is configured; otherwise the row falls back to a
				// generated favicon (handled in the adapter).
				iconUrl = repoUrl?.takeIf { it.isNotBlank() }?.let { externalRepoRepository.resolveIconUrl(it, pkg) },
			)
		}
		out.sortBy { it.title.lowercase() }
		return out
	}

	private suspend fun buildPrivateExtensionsList(
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
			runCatching { getAvailableEntries(repoUrl, forceRefresh) }
		}
		val available = availableResult.getOrDefault(emptyList())
		availableRepoEntries.value = available

		if (!repoUrl.isNullOrBlank() && (forceRefresh || settings.externalRepoInfos.none { it.url == repoUrl })) {
			runCatching { externalRepoRepository.fetchRepoInfo(repoUrl) }.getOrNull()?.let(settings::putExternalRepoInfo)
		}

		val installed = mihonExtensionLoader.getInstalledExtensions(appContext, privateMode = true)
			.associateBy { it.pkgName }
		val availableByPkg = available.associateBy { it.packageName }
		val installedSourcesByPkg = mihonSources.value.groupBy { it.pkgName }
		val allInstalledSourcesByPkg = allMihonSources.value.groupBy { it.pkgName }
		val inProgressPackages = installingPackages.value
		val locale = filter.locale
		val q = query?.takeIf { it.isNotBlank() }

		val enabledItems = ArrayList<SourceCatalogItem.Extension>()
		for (local in installed.values) {
			if (settings.isNsfwContentDisabled && local.isNsfw) continue
			val pkgAllSources = allInstalledSourcesByPkg[local.pkgName].orEmpty()
			if (locale != null && local.lang != locale && pkgAllSources.none { it.language == locale }) continue
			if (q != null && !local.appName.contains(q, ignoreCase = true) && !local.pkgName.contains(q, ignoreCase = true)) continue
			val pkgSources = allInstalledSourcesByPkg[local.pkgName] ?: installedSourcesByPkg[local.pkgName]
			val source = pkgSources?.firstOrNull { it.language == local.lang } ?: pkgSources?.firstOrNull()
			val repoLabel = settings.findRepoInfoBySignatures(local.signatures)?.displayName
				?: settings.getExtensionRepoUrl(local.pkgName)?.let { getExternalExtensionRepoDisplayName(it) }
			val subtitle = buildString {
				append(getExternalExtensionLanguageDisplayName(local.lang))
				append(" • ")
				append(local.versionName)
				if (local.isNsfw) append(" • 18+")
				repoLabel?.let { append(" • "); append(it) }
			}
			enabledItems += SourceCatalogItem.Extension(
				packageName = local.pkgName,
				title = local.appName.removePrefix("Tachiyomi: ").trim(),
				subtitle = subtitle,
				action = SourceCatalogItem.Extension.Action.DISABLE,
				isInProgress = local.pkgName in inProgressPackages,
				iconUrl = availableByPkg[local.pkgName]?.iconUrl
					?: repoUrl?.let { externalRepoRepository.resolveIconUrl(it, local.pkgName) },
				sourceIconName = source?.name,
				sourceName = source?.name,
				isHidden = settings.isMihonPackageHidden(local.pkgName),
				isPrivateMode = true,
			)
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
		val recommended = computeRecommendedExtensionsPrivate(
			installedPkgs = installed.keys,
			installedIds = installedIds,
			inProgress = inProgressPackages,
			query = q,
			repoUrl = repoUrl,
			repoSourceIndex = repoSourceIndex,
		)
		val recommendedPackages = recommended.mapTo(HashSet(recommended.size)) { it.packageName }

		val disabledItems = ArrayList<SourceCatalogItem.Extension>()
		for (entry in available) {
			if (entry.packageName in recommendedPackages) continue
			if (entry.packageName in installed) continue
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
			val iconUrl = entry.iconUrl ?: repoUrl?.let { externalRepoRepository.resolveIconUrl(it, entry.packageName) }
			disabledItems += SourceCatalogItem.Extension(
				packageName = entry.packageName,
				title = entry.name.removePrefix("Tachiyomi: ").trim(),
				subtitle = subtitle,
				action = SourceCatalogItem.Extension.Action.ENABLE,
				isInProgress = entry.packageName in inProgressPackages,
				iconUrl = iconUrl,
				sourceIconName = source?.name,
				isPrivateMode = true,
			)
		}

		val titleComparator = Comparator<SourceCatalogItem.Extension> { a, b -> a.title.compareTo(b.title, ignoreCase = true) }
		enabledItems.sortWith(titleComparator)
		disabledItems.sortWith(titleComparator)

		return buildList {
			if (!hasRepo) {
				add(SourceCatalogItem.Hint(R.drawable.ic_empty_common, R.string.no_repo_detected, R.string.click_edit_icon_add_extension_repo))
			} else if (availableResult.isFailure) {
				add(SourceCatalogItem.Hint(R.drawable.ic_error_large, R.string.error, R.string.extensions_repo_load_error))
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

	private suspend fun computeRecommendedExtensionsPrivate(
		installedPkgs: Set<String>,
		installedIds: Set<Long>,
		inProgress: Set<String>,
		query: String?,
		repoUrl: String?,
		repoSourceIndex: Map<Long, Pair<String, String>>,
	): List<SourceCatalogItem.Extension> {
		val sources = runCatching {
			mangaDatabase.getMangaDao().findExternalSourcesInLibrary()
		}.getOrDefault(emptyList())
		if (sources.isEmpty()) return emptyList()
		val seen = HashSet<String>()
		val out = ArrayList<SourceCatalogItem.Extension>()
		for (name in sources) {
			val id = name.removePrefix("MIHON_").substringBefore(':').toLongOrNull() ?: continue
			if (id in installedIds) continue
			val ref = repoSourceIndex[id]
				?: kotatsuSourceMap.resolveById(id)?.let { it.packageName to it.sourceName }
				?: continue
			val (pkg, displayName) = ref
			if (pkg.isBlank() || pkg in installedPkgs || !seen.add(pkg)) continue
			if (query != null && !displayName.contains(query, ignoreCase = true) && !pkg.contains(query, ignoreCase = true)) continue
			out += SourceCatalogItem.Extension(
				packageName = pkg,
				title = displayName,
				subtitle = appContext.getString(R.string.recommended_extension_subtitle),
				action = SourceCatalogItem.Extension.Action.ENABLE,
				isInProgress = pkg in inProgress,
				iconUrl = repoUrl?.takeIf { it.isNotBlank() }?.let { externalRepoRepository.resolveIconUrl(it, pkg) },
				isPrivateMode = true,
			)
		}
		out.sortBy { it.title.lowercase() }
		return out
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
		val repoUrl = externalRepoUrl.value ?: return
		val entries = getAvailableEntries(repoUrl, forceRefresh = false)
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
		val requests = entries
			.filter { it.packageName in migrationPkgs }
			.map { entry ->
				settings.setExtensionRepoUrl(entry.packageName, repoUrl)
				InstallRequest(
					packageName = entry.packageName,
					url = externalRepoRepository.resolveApkUrl(repoUrl, entry.apkName),
				)
			}
		if (requests.isNotEmpty()) {
			emitInstallRequests(requests)
		}
	}

	companion object {
		const val HEADER_ACTION_UPDATE_ALL = "update_all"
	}

	data class InstallRequest(
		val packageName: String,
		val url: String,
	)
}
