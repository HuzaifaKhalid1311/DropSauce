package org.koitharu.kotatsu.main.ui

import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.core.exceptions.EmptyHistoryException
import org.koitharu.kotatsu.core.github.AppUpdateRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.prefs.observeAsStateFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.main.domain.ReadingResumeEnabledUseCase
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.settings.sources.catalog.ExternalExtensionRepoRepository
import org.koitharu.kotatsu.tracker.domain.TrackingRepository
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
	@ApplicationContext private val appContext: Context,
	private val historyRepository: HistoryRepository,
	private val appUpdateRepository: AppUpdateRepository,
	trackingRepository: TrackingRepository,
	private val settings: AppSettings,
	readingResumeEnabledUseCase: ReadingResumeEnabledUseCase,
	private val mihonExtensionManager: MihonExtensionManager,
	private val externalRepoRepository: ExternalExtensionRepoRepository,
) : BaseViewModel() {

	val hasExtensionUpdates: StateFlow<Boolean> = combine(
		mihonExtensionManager.installedExtensions,
		settings.observeAsFlow(AppSettings.KEY_EXTERNAL_EXTENSIONS_REPO_URL) { externalExtensionsRepoUrl }
	) { installed, repoUrl ->
		if (repoUrl.isNullOrBlank()) return@combine false
		try {
			val available = externalRepoRepository.getExtensions(repoUrl)
			val installedByPkg = installed.associateBy { it.pkgName }
			available.any { entry ->
				val local = installedByPkg[entry.packageName] ?: return@any false
				entry.versionCode > local.versionCode
			}
		} catch (_: Exception) {
			false
		}
	}.flowOn(Dispatchers.IO)
		.stateIn(
			scope = viewModelScope + Dispatchers.IO,
			started = SharingStarted.WhileSubscribed(5000),
			initialValue = false
		)

	val onOpenReader = MutableEventFlow<Manga>()

	val isResumeEnabled = readingResumeEnabledUseCase()
		.withErrorHandling()
		.stateIn(
			scope = viewModelScope + Dispatchers.Default,
			started = SharingStarted.WhileSubscribed(5000),
			initialValue = false,
		)

	val appUpdate = appUpdateRepository.observeAvailableUpdate()

	val feedCounter = trackingRepository.observeUnreadUpdatesCount()
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, 0)

	val isBottomNavPinned = settings.observeAsFlow(
		AppSettings.KEY_NAV_PINNED,
	) {
		isNavBarPinned
	}.flowOn(Dispatchers.Default)

	val isIncognitoModeEnabled = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_INCOGNITO_MODE,
		valueProducer = { isIncognitoModeEnabled },
	)

	init {
		launchJob {
			appUpdateRepository.fetchUpdate()
		}
	}

	fun openLastReader() {
		launchLoadingJob(Dispatchers.Default) {
			val manga = historyRepository.getLastOrNull() ?: throw EmptyHistoryException()
			onOpenReader.call(manga)
		}
	}

	fun setIncognitoMode(isEnabled: Boolean) {
		settings.isIncognitoModeEnabled = isEnabled
	}
}
