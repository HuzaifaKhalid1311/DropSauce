package org.koitharu.kotatsu.settings.sources

import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.explore.data.getMihonLanguageCandidates
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.MihonMangaRepository
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import java.util.Locale
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

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		if (repository is CachingMangaRepository) {
			if (key != SourceSettings.KEY_SLOWDOWN && key != SourceSettings.KEY_SORT_ORDER) {
				repository.invalidateCache()
			}
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

	fun getSelectedMihonSourceLang(pkgName: String, langs: Collection<String>): String? {
		val selected = settings.getMihonSelectedLanguage(pkgName)
		if (selected != null && selected in langs) {
			return selected
		}
		return langs.pickByPreferredLanguage(getMihonLanguageCandidates())
	}

	fun setSelectedMihonSourceLang(pkgName: String, lang: String) {
		settings.setMihonSelectedLanguage(pkgName, lang)
	}
}

private fun Collection<String>.pickByPreferredLanguage(languageCandidates: Collection<String>): String? {
	if (isEmpty()) return null
	val byLanguage = associateBy { it.lowercase(Locale.ROOT) }
	for (candidate in languageCandidates) {
		byLanguage[candidate.lowercase(Locale.ROOT)]?.let { return it }
	}
	return byLanguage["en"] ?: first()
}
