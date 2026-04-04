package org.koitharu.kotatsu.settings.sources

import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.MihonMangaRepository
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import javax.inject.Inject

@HiltViewModel
class SourceSettingsViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	mangaRepositoryFactory: MangaRepository.Factory,
	private val settings: AppSettings,
	private val mihonExtensionManager: MihonExtensionManager,
) : BaseViewModel(), SharedPreferences.OnSharedPreferenceChangeListener {

	val source = MangaSource(savedStateHandle.get<String>(AppRouter.KEY_SOURCE))
	val repository = mangaRepositoryFactory.create(source)

	val onActionDone = MutableEventFlow<ReversibleAction>()
	val username = MutableStateFlow<String?>(null)
	val isAuthorized = MutableStateFlow<Boolean?>(null)
	val browserUrl = MutableStateFlow<String?>(null)
	init {
		// Extensions handle their own config; no built-in parser config to subscribe
		browserUrl.value = null
	}

	override fun onCleared() {
		// No-op: extensions handle their own config
		super.onCleared()
	}

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		if (repository is CachingMangaRepository) {
			if (key != SourceSettings.KEY_SLOWDOWN && key != SourceSettings.KEY_SORT_ORDER) {
				repository.invalidateCache()
			}
		}
	}

	fun onResume() {
		// Auth not supported in extension-only mode
	}

	fun clearCookies() {
		// No built-in parser domain to clear cookies for
	}

	/**
	 * Returns all [MihonMangaSource] instances belonging to the same extension package
	 * as the current source. Returns empty list if the source is not a Mihon source.
	 */
	fun getSiblingMihonSources(): List<MihonMangaSource> {
		val repo = repository as? MihonMangaRepository ?: return emptyList()
		val pkgName = repo.source.pkgName
		return mihonExtensionManager.getMihonMangaSources().filter { it.pkgName == pkgName }
	}

	fun isMihonSourceLangEnabled(pkgName: String, lang: String): Boolean =
		settings.isMihonSourceLangEnabled(pkgName, lang)

	fun setMihonSourceLangEnabled(pkgName: String, lang: String, enabled: Boolean) {
		settings.setMihonSourceLangEnabled(pkgName, lang, enabled)
	}
}
