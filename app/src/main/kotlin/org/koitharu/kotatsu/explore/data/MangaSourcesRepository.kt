package org.koitharu.kotatsu.explore.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.dao.MangaSourcesDao
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaSourcesRepository @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val db: MangaDatabase,
	private val settings: AppSettings,
	private val mihonExtensionManager: MihonExtensionManager? = null,
) {

	@Suppress("unused")
	private val dao: MangaSourcesDao
		get() = db.getSourcesDao()

	private val usageRefresh = MutableStateFlow(0)
	private val pinnedRefresh = MutableStateFlow(0)

	fun getEnabledSources(): List<MangaSource> {
		return buildSortedSourceInfoList(getMihonSources()).map { it.mangaSource }
	}

	fun getPinnedSources(): Set<MangaSource> {
		val sourcesByKey = getMihonSources().associateBy(::sourceKeyOf)
		return getPinnedSourceKeys()
			.mapNotNull { sourcesByKey[it] }
			.toSet()
	}

	fun getTopSources(limit: Int): List<MangaSource> {
		return getEnabledSources().take(limit)
	}

	fun observeEnabledSourcesCount(): Flow<Int> {
		return observeMihonSources().map { it.size }.distinctUntilChanged()
	}

	fun observeAvailableSourcesCount(): Flow<Int> {
		// Available sources are provided by extensions
		return kotlinx.coroutines.flow.flowOf(0)
	}

	fun observeEnabledSources(): Flow<List<MangaSourceInfo>> = combine(
		observeMihonSources(),
		settings.observeAsFlow(AppSettings.KEY_SOURCES_ORDER) { sourcesSortOrder },
		usageRefresh,
		pinnedRefresh,
	) { sources, _, _, _ ->
		buildSortedSourceInfoList(sources)
	}.distinctUntilChanged()

	fun observeAll(): Flow<List<Pair<MangaSource, Boolean>>> = observeMihonSources().map { mihon ->
		mihon.map { it to true }
	}

	fun setSourcesEnabled(sources: Collection<MangaSource>, isEnabled: Boolean): ReversibleHandle {
		return ReversibleHandle { }
	}

	fun setSourcesEnabledExclusive(sources: Set<MangaSource>) {
		// No-op for Mihon-only mode
	}

	fun disableAllSources() {
		// No-op for Mihon-only mode
	}

	fun setPositions(sources: List<MangaSource>) {
		// No-op for Mihon-only mode
	}

	fun observeHasNewSources(): Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false)

	fun clearNewSourcesBadge() {
		settings.sourcesVersion = org.koitharu.kotatsu.BuildConfig.VERSION_CODE
	}

	fun isSetupRequired(): Boolean {
		// Setup is not required for Mihon-only mode, extensions are installed separately
		return false
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
		is MihonMangaSource -> "mihon:${source.pkgName}:${source.language}"
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

	private fun buildSortedSourceInfoList(sources: List<MihonMangaSource>): List<MangaSourceInfo> {
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

	fun queryMihonSources(
		query: String?,
		locale: String?,
	): List<MihonMangaSource> {
		val sources = getMihonSources().toMutableList()
		if (settings.isNsfwContentDisabled) {
			sources.removeAll { it.isNsfw }
		}
		if (locale != null) {
			sources.retainAll { it.language == locale }
		}
		if (!query.isNullOrEmpty()) {
			sources.retainAll {
				it.displayName.contains(query, ignoreCase = true) ||
					it.pkgName.contains(query, ignoreCase = true)
			}
		}
		sources.sortBy { it.displayName.lowercase() }
		return sources
	}

	private fun getMihonSources(): List<MihonMangaSource> {
		val manager = mihonExtensionManager ?: return emptyList()
		manager.initialize()
		val sources = manager.getMihonMangaSources()
		val preferredLangs = settings.mihonPreferredLanguages
		val disabledLangs = settings.mihonPerExtDisabledLangs
		val hideNsfw = settings.isNsfwContentDisabled
		return sources.filter { source ->
			val isPreferredLang = preferredLangs.isEmpty() || source.language in preferredLangs
			val isEnabled = "${source.pkgName}:${source.language}" !in disabledLangs
			val isNsfwOk = !hideNsfw || !source.isNsfw
			isPreferredLang && isEnabled && isNsfwOk
		}
	}

	fun observeMihonSources(): Flow<List<MihonMangaSource>> {
		val manager = mihonExtensionManager ?: return kotlinx.coroutines.flow.flowOf(emptyList())
		manager.initialize()
		return combine(
			manager.installedExtensions,
			manager.isLoading,
			settings.observeAsFlow(AppSettings.KEY_MIHON_PREFERRED_LANGUAGES) { mihonPreferredLanguages },
			settings.observeAsFlow(AppSettings.KEY_MIHON_PER_EXT_DISABLED_LANGS) { mihonPerExtDisabledLangs },
			settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) { isNsfwContentDisabled },
		) { _: Any?, _: Any?, _: Any?, _: Any?, _: Any? ->
			getMihonSources()
		}.distinctUntilChanged()
	}

	private companion object {
		private const val KEY_PINNED_ORDER = "pinned_order"
		private const val PIN_SEPARATOR = "\n"
	}
}
