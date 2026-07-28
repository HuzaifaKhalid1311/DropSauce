package org.koitharu.kotatsu.settings.sources

import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.lnreader.LnPluginManager
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.MihonMangaRepository
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.mihon.resolveActiveMihonLanguage
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SourceSettingsViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	mangaRepositoryFactory: MangaRepository.Factory,
	private val settings: AppSettings,
	private val mihonExtensionManager: MihonExtensionManager,
	private val cookieJar: MutableCookieJar,
	private val lnPluginManager: LnPluginManager,
) : BaseViewModel(), SharedPreferences.OnSharedPreferenceChangeListener {

	val source = MangaSource(savedStateHandle.get<String>(AppRouter.KEY_SOURCE))
	val repository = mangaRepositoryFactory.create(source)

	val onActionDone = MutableEventFlow<ReversibleAction>()
	val onPluginDeleted = MutableEventFlow<Unit>()

	/** The opened source as a Mihon source, or null if it isn't one. */
	private val mihonSource: MihonMangaSource? = (repository as? MihonMangaRepository)?.source

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		if (repository is CachingMangaRepository) {
			if (key != SourceSettings.KEY_SLOWDOWN && key != SourceSettings.KEY_SORT_ORDER) {
				repository.invalidateCache()
			}
		}
	}

	/**
	 * Returns all [MihonMangaSource] language variants of the current logical source — same
	 * extension package AND same source name. Returns an empty list if the source is not a Mihon
	 * source. (Distinct sources bundled in the same package are intentionally excluded.)
	 */
	fun getSiblingMihonSources(): List<MihonMangaSource> {
		val src = mihonSource ?: return emptyList()
		return mihonExtensionManager.getMihonMangaSources()
			.filter { it.pkgName == src.pkgName && it.catalogueSource.name == src.catalogueSource.name }
	}

	/** The currently active language for the logical source (stored choice, or resolved default). */
	fun getActiveLanguage(siblings: List<MihonMangaSource>): String? {
		val src = mihonSource ?: return null
		val stored = settings.getMihonActiveLang(src.pkgName, src.catalogueSource.name)
		return resolveActiveMihonLanguage(siblings.map { it.language }, stored, Locale.getDefault().language)
	}

	/** Clears cookies stored for this source's site. */
	fun clearCookies(baseUrl: String) {
		val url = baseUrl.toHttpUrlOrNull() ?: return
		launchJob(Dispatchers.Default) {
			cookieJar.removeCookies(url, null)
			onActionDone.call(ReversibleAction(R.string.cookies_cleared, null))
		}
	}

	/** Deletes an installed novel plugin. Unlike an APK there is no system uninstaller to hand off to. */
	fun deleteNovelPlugin(pluginId: String) {
		launchJob(Dispatchers.Default) {
			lnPluginManager.uninstall(pluginId)
			onPluginDeleted.call(Unit)
		}
	}

	/** Persists [language] as the active language for the logical source. */
	fun setActiveLanguage(language: String) {
		val src = mihonSource ?: return
		settings.setMihonActiveLang(src.pkgName, src.catalogueSource.name, language)
	}
}
