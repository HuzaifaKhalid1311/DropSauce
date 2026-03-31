package org.haziffe.dropsauce.settings.sources

import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.core.model.MangaSource
import org.haziffe.dropsauce.core.nav.AppRouter
import org.haziffe.dropsauce.core.network.cookies.MutableCookieJar
import org.haziffe.dropsauce.core.parser.CachingMangaRepository
import org.haziffe.dropsauce.core.parser.MangaRepository
import org.haziffe.dropsauce.core.parser.ParserMangaRepository
import org.haziffe.dropsauce.core.prefs.AppSettings
import org.haziffe.dropsauce.core.prefs.SourceSettings
import org.haziffe.dropsauce.core.ui.BaseViewModel
import org.haziffe.dropsauce.core.ui.util.ReversibleAction
import org.haziffe.dropsauce.core.util.ext.MutableEventFlow
import org.haziffe.dropsauce.core.util.ext.call
import org.haziffe.dropsauce.mihon.MihonExtensionManager
import org.haziffe.dropsauce.mihon.MihonMangaRepository
import org.haziffe.dropsauce.mihon.model.MihonMangaSource
import org.haziffe.dropsauce.parsers.MangaParserAuthProvider
import org.haziffe.dropsauce.parsers.exception.AuthRequiredException
import javax.inject.Inject

@HiltViewModel
class SourceSettingsViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	mangaRepositoryFactory: MangaRepository.Factory,
	private val cookieJar: MutableCookieJar,
	private val settings: AppSettings,
	private val mihonExtensionManager: MihonExtensionManager,
) : BaseViewModel(), SharedPreferences.OnSharedPreferenceChangeListener {

	val source = MangaSource(savedStateHandle.get<String>(AppRouter.KEY_SOURCE))
	val repository = mangaRepositoryFactory.create(source)

	val onActionDone = MutableEventFlow<ReversibleAction>()
	val username = MutableStateFlow<String?>(null)
	val isAuthorized = MutableStateFlow<Boolean?>(null)
	val browserUrl = MutableStateFlow<String?>(null)
	private var usernameLoadJob: Job? = null

	init {
		when (repository) {
			is ParserMangaRepository -> {
				browserUrl.value = "https://${repository.domain}"
				repository.getConfig().subscribe(this)
				loadUsername(repository.getAuthProvider())
			}
		}
	}

	override fun onCleared() {
		when (repository) {
			is ParserMangaRepository -> {
				repository.getConfig().unsubscribe(this)
			}
		}
		super.onCleared()
	}

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		if (repository is CachingMangaRepository) {
			if (key != SourceSettings.KEY_SLOWDOWN && key != SourceSettings.KEY_SORT_ORDER) {
				repository.invalidateCache()
			}
		}
		if (repository is ParserMangaRepository) {
			if (key == SourceSettings.KEY_DOMAIN) {
				browserUrl.value = "https://${repository.domain}"
			}
		}
	}

	fun onResume() {
		if (usernameLoadJob?.isActive != true && repository is ParserMangaRepository) {
			loadUsername(repository.getAuthProvider())
		}
	}

	fun clearCookies() {
		if (repository !is ParserMangaRepository) return
		launchLoadingJob(Dispatchers.Default) {
			val url = HttpUrl.Builder()
				.scheme("https")
				.host(repository.domain)
				.build()
			cookieJar.removeCookies(url, null)
			onActionDone.call(ReversibleAction(R.string.cookies_cleared, null))
			loadUsername(repository.getAuthProvider())
		}
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

	private fun loadUsername(authProvider: MangaParserAuthProvider?) {
		launchLoadingJob(Dispatchers.Default) {
			try {
				username.value = null
				isAuthorized.value = null
				isAuthorized.value = authProvider?.isAuthorized()
				username.value = authProvider?.getUsername()
			} catch (_: AuthRequiredException) {
			}
		}
	}
}
