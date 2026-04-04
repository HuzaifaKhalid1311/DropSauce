package org.koitharu.kotatsu.explore.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.dao.MangaSourcesDao
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isNsfw
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

	private val dao: MangaSourcesDao
		get() = db.getSourcesDao()

	fun getEnabledSources(): List<MangaSource> {
		val mihon = getMihonSources()
		val list = ArrayList<MangaSourceInfo>(mihon.size)
		mihon.mapTo(list) { MangaSourceInfo(it, isEnabled = true, isPinned = false) }
		if (settings.sourcesSortOrder == SourcesSortOrder.ALPHABETIC) {
			list.sortWith(compareBy { it.getTitle(context) })
		}
		return list
	}

	fun getPinnedSources(): Set<MangaSource> {
		// Mihon sources don't have pin support via DB, return empty
		return emptySet()
	}

	fun getTopSources(limit: Int): List<MangaSource> {
		return getMihonSources().take(limit)
	}

	fun getDisabledSources(): Set<MangaSource> {
		return emptySet()
	}

	fun observeEnabledSourcesCount(): Flow<Int> {
		return observeMihonSources().map { it.size }.distinctUntilChanged()
	}

	fun observeAvailableSourcesCount(): Flow<Int> {
		// No built-in sources to show as available
		return kotlinx.coroutines.flow.flowOf(0)
	}

	fun observeEnabledSources(): Flow<List<MangaSourceInfo>> =
		observeMihonSources().map { mihon ->
			val list = ArrayList<MangaSourceInfo>(mihon.size)
			mihon.mapTo(list) { MangaSourceInfo(it, isEnabled = true, isPinned = false) }
			if (settings.sourcesSortOrder == SourcesSortOrder.ALPHABETIC) {
				list.sortWith(compareBy { it.getTitle(context) })
			}
			list
		}

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
		return ReversibleHandle { }
	}

	fun trackUsage(source: MangaSource) {
		// trackUsage is a fire-and-forget; setLastUsed is suspend so we skip it in non-coroutine context
		// Usage tracking is best-effort for extension sources
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
		return sources.filter { source ->
			val isPreferredLang = preferredLangs.isEmpty() || source.language in preferredLangs
			val isEnabled = "${source.pkgName}:${source.language}" !in disabledLangs
			isPreferredLang && isEnabled
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
		) { _, _, _, _ ->
			getMihonSources()
		}.distinctUntilChanged()
	}
}
