package org.koitharu.kotatsu.explore.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.os.ConfigurationCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageAutonym
import org.koitharu.kotatsu.lnreader.LnPluginManager
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.lnreader.model.langCode
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.mihon.resolveActiveMihonLanguage
import org.koitharu.kotatsu.parsers.model.MangaSource
import javax.inject.Inject
import javax.inject.Singleton

/** One language offered by the installed sources, as shown in the Explore language filter. */
data class SourceLanguage(
	val code: String,
	val displayName: String,
	val sourceCount: Int,
	val isEnabled: Boolean,
)

/** Result of [MangaSourcesRepository.resolveActiveSource]. */
data class ResolvedSource(
	val source: MangaSource,
	val languageSubtitle: String?,
)

@Singleton
class MangaSourcesRepository @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val settings: AppSettings,
	private val mihonExtensionManager: MihonExtensionManager? = null,
	private val lnPluginManager: LnPluginManager? = null,
) {

	private val usageRefresh = MutableStateFlow(0)
	private val pinnedRefresh = MutableStateFlow(0)

	fun getEnabledSources(): List<MangaSource> {
		return buildSortedSourceInfoList(getAllEnabledSources()).map { it.mangaSource }
	}

	fun getPinnedSources(): Set<MangaSource> {
		val sourcesByKey = getAllEnabledSources().associateBy(::sourceKeyOf)
		return getPinnedSourceKeys()
			.mapNotNull { sourcesByKey[it] }
			.toSet()
	}

	fun getTopSources(limit: Int): List<MangaSource> {
		return getEnabledSources().take(limit)
	}

	fun observeEnabledSources(): Flow<List<MangaSourceInfo>> = combine(
		observeMihonSources(),
		observeLnSources(),
		settings.observeAsFlow(AppSettings.KEY_SOURCES_ORDER) { sourcesSortOrder },
		usageRefresh,
		pinnedRefresh,
	) { mihon, ln, _, _, _ ->
		buildSortedSourceInfoList(mihon + ln)
	}.distinctUntilChanged()

	fun observeAll(): Flow<List<Pair<MangaSource, Boolean>>> =
		combine(observeMihonSources(), observeLnSources()) { mihon, ln ->
			(mihon + ln).map { it to true }
		}

	fun setIsPinned(sources: Collection<MangaSource>, isPinned: Boolean): ReversibleHandle {
		val before = getPinnedSourceKeys()
		val updated = before.toMutableList()
		for (source in sources) {
			val key = sourceKeyOf(source)
			if (isPinned) {
				updated.remove(key)
				updated.add(0, key)
			} else {
				updated.remove(key)
			}
		}
		setPinnedSourceKeys(updated)
		pinnedRefresh.value++
		return ReversibleHandle {
			setPinnedSourceKeys(before)
			pinnedRefresh.value++
		}
	}

	/**
	 * Hides (or unhides) the extension packages backing [sources] from Explore. Returns a
	 * [ReversibleHandle] that restores the previous state.
	 */
	fun setSourcesHidden(sources: Collection<MangaSource>, hidden: Boolean): ReversibleHandle {
		val packages = sources.mapNotNullTo(HashSet()) { it.unwrapMihon()?.pkgName }
		val plugins = sources.mapNotNullTo(HashSet()) { it.unwrapLn()?.pluginId }
		val before = settings.mihonHiddenPackages
		val beforeLn = settings.lnHiddenPlugins
		settings.mihonHiddenPackages = if (hidden) before + packages else before - packages
		settings.lnHiddenPlugins = if (hidden) beforeLn + plugins else beforeLn - plugins
		return ReversibleHandle {
			settings.mihonHiddenPackages = before
			settings.lnHiddenPlugins = beforeLn
		}
	}

	private val usagePrefs: SharedPreferences by lazy {
		context.getSharedPreferences("source_usage", Context.MODE_PRIVATE)
	}

	fun trackUsage(source: MangaSource) {
		val key = sourceKeyOf(source)
		usagePrefs.edit().putLong(key, System.currentTimeMillis()).apply()
		usageRefresh.value++
	}

	private fun getLastUsedTimestamp(source: MangaSource): Long {
		val key = sourceKeyOf(source)
		return usagePrefs.getLong(key, 0L)
	}

	private val sourceStatePrefs: SharedPreferences by lazy {
		context.getSharedPreferences("source_state", Context.MODE_PRIVATE)
	}

	private fun sourceKeyOf(source: MangaSource): String = when (source) {
		is MangaSourceInfo -> sourceKeyOf(source.mangaSource)
		// Key by package + source name (NOT language) so pins and last-used survive a language switch.
		is MihonMangaSource -> "mihon:${source.pkgName}:${source.catalogueSource.name}"
		is LnMangaSource -> "ln:${source.pluginId}"
		else -> {
			val matched = getMihonSources().firstOrNull { it.name == source.name }
			if (matched != null) {
				sourceKeyOf(matched)
			} else {
				source.name
			}
		}
	}

	private fun getPinnedSourceKeys(): List<String> {
		val raw = sourceStatePrefs.getString(KEY_PINNED_ORDER, null).orEmpty()
		if (raw.isEmpty()) return emptyList()
		return raw.split(PIN_SEPARATOR).filter { it.isNotBlank() }
	}

	private fun setPinnedSourceKeys(keys: List<String>) {
		sourceStatePrefs.edit().putString(KEY_PINNED_ORDER, keys.joinToString(PIN_SEPARATOR)).apply()
	}

	private fun buildSortedSourceInfoList(sources: List<MangaSource>): List<MangaSourceInfo> {
		if (sources.isEmpty()) return emptyList()
		val pinnedOrder = getPinnedSourceKeys()
		val pinnedIndex = HashMap<String, Int>(pinnedOrder.size)
		for ((index, key) in pinnedOrder.withIndex()) {
			pinnedIndex[key] = index
		}

		val pinned = ArrayList<MangaSourceInfo>()
		val unpinned = ArrayList<MangaSourceInfo>()
		for (source in sources) {
			val isPinned = sourceKeyOf(source) in pinnedIndex
			val item = MangaSourceInfo(source, isEnabled = true, isPinned = isPinned)
			if (isPinned) pinned += item else unpinned += item
		}

		pinned.sortBy { pinnedIndex[sourceKeyOf(it.mangaSource)] ?: Int.MAX_VALUE }
		when (settings.sourcesSortOrder) {
			SourcesSortOrder.ALPHABETIC -> unpinned.sortWith(compareBy { it.getTitle(context) })
			SourcesSortOrder.LAST_USED -> unpinned.sortWith(compareByDescending { getLastUsedTimestamp(it.mangaSource) })
			SourcesSortOrder.MANUAL -> Unit
		}
		return pinned + unpinned
	}

	/**
	 * Collapses each logical source (a package + source-name pair) into a single Explore entity.
	 * For a multi-language source only the active-language variant is returned — chosen by the
	 * user, or defaulted (app language → English → any) at read time. Single-language sources are
	 * returned as-is. Honours the NSFW filter.
	 */
	private fun getMihonSources(): List<MihonMangaSource> {
		val hiddenLangs = settings.hiddenSourceLanguages
		return getActiveMihonSources().filterNot { it.language in hiddenLangs }
	}

	/**
	 * Every source at its active language, before the language filter is applied. The filter runs
	 * after the collapse on purpose: hiding a language must not change which variant of a
	 * multi-language source is the active one.
	 */
	private fun getActiveMihonSources(): List<MihonMangaSource> {
		val manager = mihonExtensionManager ?: return emptyList()
		manager.initialize()
		val hideNsfw = settings.isNsfwContentDisabled
		val hiddenPackages = settings.mihonHiddenPackages
		val appLang = appLanguage
		return manager.getMihonMangaSources()
			.filterNot { hideNsfw && it.isNsfw }
			.filterNot { it.pkgName in hiddenPackages }
			.groupBy { it.pkgName to it.catalogueSource.name }
			.mapNotNull { (key, group) ->
				if (group.size == 1) {
					group.first()
				} else {
					val (pkgName, sourceName) = key
					val langs = group.map { it.language }
					val stored = settings.getMihonActiveLang(pkgName, sourceName)
					val activeLang = resolveActiveMihonLanguage(langs, stored, appLang)
					group.firstOrNull { it.language == activeLang } ?: group.first()
				}
			}
	}

	/**
	 * Resolves a (possibly stale) Mihon source to the language variant that is currently active
	 * for its logical source. Non-Mihon or single-language sources are returned unchanged. The
	 * returned [ResolvedSource.languageSubtitle] is the active language's native name, or null
	 * when the source has no language variants.
	 */
	fun resolveActiveSource(source: MangaSource): ResolvedSource {
		val mihon = source.unwrapMihon() ?: return ResolvedSource(source, null)
		val manager = mihonExtensionManager ?: return ResolvedSource(source, null)
		manager.initialize()
		val siblings = manager.getMihonMangaSources()
			.filter { it.pkgName == mihon.pkgName && it.catalogueSource.name == mihon.catalogueSource.name }
		if (siblings.size <= 1) return ResolvedSource(source, null)
		val stored = settings.getMihonActiveLang(mihon.pkgName, mihon.catalogueSource.name)
		val activeLang = resolveActiveMihonLanguage(siblings.map { it.language }, stored, appLanguage)
		val active = siblings.firstOrNull { it.language == activeLang } ?: mihon
		return ResolvedSource(active, active.languageDisplayName)
	}

	/** Novel plugins mixed straight into Explore alongside manga sources. */
	fun getLnSources(): List<LnMangaSource> {
		val manager = lnPluginManager ?: return emptyList()
		manager.initialize()
		val hidden = settings.lnHiddenPlugins
		val hiddenLangs = settings.hiddenSourceLanguages
		return manager.getAll()
			.filterNot { it.pluginId in hidden }
			.filterNot { it.plugin.langCode in hiddenLangs }
	}

	fun observeLnSources(): Flow<List<LnMangaSource>> {
		val manager = lnPluginManager ?: return kotlinx.coroutines.flow.flowOf(emptyList())
		manager.initialize()
		return combine(
			manager.sources,
			settings.observeAsFlow(AppSettings.KEY_LN_HIDDEN_PLUGINS) { lnHiddenPlugins },
			settings.observeAsFlow(AppSettings.KEY_HIDDEN_SOURCE_LANGUAGES) { hiddenSourceLanguages },
		) { _: Any?, _: Any?, _: Any? ->
			getLnSources()
		}.distinctUntilChanged()
	}

	private fun getAllEnabledSources(): List<MangaSource> = getMihonSources() + getLnSources()

	private fun MangaSource.unwrapLn(): LnMangaSource? = when (this) {
		is LnMangaSource -> this
		is MangaSourceInfo -> mangaSource as? LnMangaSource
		else -> null
	}

	private fun MangaSource.unwrapMihon(): MihonMangaSource? = when (this) {
		is MihonMangaSource -> this
		is MangaSourceInfo -> mangaSource as? MihonMangaSource
		else -> null
	}

	/** The app's current language code (e.g. "en", "fr"), used to default a source's language. */
	private val appLanguage: String
		get() = ConfigurationCompat.getLocales(context.resources.configuration)[0]
			?.language
			?.takeIf { it.isNotEmpty() }
			?: "en"

	/**
	 * The languages actually spoken by the installed sources, with a source count each. Only the
	 * *active* language of a multi-language source counts, so a source pinned to English shows up
	 * as English alone. Ignores the language filter itself, so a hidden language can be unhidden.
	 */
	fun getSourceLanguages(): List<SourceLanguage> {
		val hiddenPlugins = settings.lnHiddenPlugins
		val counts = HashMap<String, Int>()
		getActiveMihonSources().forEach { counts[it.language] = (counts[it.language] ?: 0) + 1 }
		lnPluginManager?.let { manager ->
			manager.initialize()
			manager.getAll()
				.filterNot { it.pluginId in hiddenPlugins }
				.forEach {
					val code = it.plugin.langCode
					counts[code] = (counts[code] ?: 0) + 1
				}
		}
		val hiddenLangs = settings.hiddenSourceLanguages
		return counts.map { (code, count) ->
			SourceLanguage(
				code = code,
				displayName = getExternalExtensionLanguageAutonym(code),
				sourceCount = count,
				isEnabled = code !in hiddenLangs,
			)
		}.sortedBy { it.displayName.lowercase() }
	}

	fun setHiddenLanguages(codes: Set<String>) {
		settings.hiddenSourceLanguages = codes
	}

	/** True when at least one installed source offers more than one language. */
	private fun hasMultiLanguageSources(): Boolean {
		val manager = mihonExtensionManager ?: return false
		return manager.getMihonMangaSources()
			.groupBy { it.pkgName to it.catalogueSource.name }
			.any { (_, group) -> group.mapTo(HashSet()) { it.language }.size > 1 }
	}

	private fun getAllMihonSources(): List<MihonMangaSource> {
		val manager = mihonExtensionManager ?: return emptyList()
		manager.initialize()
		val sources = manager.getMihonMangaSources()
		val hideNsfw = settings.isNsfwContentDisabled
		return sources.filter { source ->
			!hideNsfw || !source.isNsfw
		}
	}

	fun observeMihonSources(): Flow<List<MihonMangaSource>> {
		val manager = mihonExtensionManager ?: return kotlinx.coroutines.flow.flowOf(emptyList())
		manager.initialize()
		return combine(
			manager.installedExtensions,
			manager.isLoading,
			settings.observeAsFlow(AppSettings.KEY_MIHON_PER_EXT_ACTIVE_LANG) { mihonPerExtActiveLangs },
			settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled },
			settings.observeAsFlow(AppSettings.KEY_MIHON_HIDDEN_PACKAGES) { mihonHiddenPackages },
			settings.observeAsFlow(AppSettings.KEY_HIDDEN_SOURCE_LANGUAGES) { hiddenSourceLanguages },
		) { _: Array<Any?> ->
			getMihonSources()
		}.distinctUntilChanged()
	}

	/** Emits `true` while any installed source offers more than one language. */
	fun observeHasMultiLanguageSources(): Flow<Boolean> {
		val manager = mihonExtensionManager ?: return kotlinx.coroutines.flow.flowOf(false)
		manager.initialize()
		return combine(
			manager.installedExtensions,
			manager.isLoading,
		) { _: Any?, _: Any? ->
			hasMultiLanguageSources()
		}.distinctUntilChanged()
	}

	/** Emits `true` while the Mihon extension manager is loading extensions, `false` otherwise. */
	fun observeMihonLoadingState(): Flow<Boolean> {
		val manager = mihonExtensionManager ?: return kotlinx.coroutines.flow.flowOf(false)
		return manager.isLoading
	}

	fun observeAllMihonSources(): Flow<List<MihonMangaSource>> {
		val manager = mihonExtensionManager ?: return kotlinx.coroutines.flow.flowOf(emptyList())
		manager.initialize()
		return combine(
			manager.installedExtensions,
			manager.isLoading,
			settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled },
		) { _: Any?, _: Any?, _: Any? ->
			getAllMihonSources()
		}.distinctUntilChanged()
	}

	suspend fun reloadMihonSources() {
		mihonExtensionManager?.loadExtensions()
	}

	private companion object {
		private const val KEY_PINNED_ORDER = "pinned_order"
		private const val PIN_SEPARATOR = "\n"
	}
}
