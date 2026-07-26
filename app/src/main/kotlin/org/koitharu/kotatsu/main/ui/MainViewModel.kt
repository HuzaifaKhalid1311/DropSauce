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
import kotlinx.coroutines.flow.onEach
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
import org.koitharu.kotatsu.extensions.install.ExtensionUpdateWorker
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.main.domain.ReadingResumeEnabledUseCase
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionInstallMode
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreManager
import org.koitharu.kotatsu.settings.sources.catalog.StoreHealth
import org.koitharu.kotatsu.settings.sources.catalog.isNewerThan
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
	private val extensionStoreManager: ExtensionStoreManager,
	private val extensionUpdateScheduler: ExtensionUpdateWorker.Scheduler,
) : BaseViewModel() {

	val hasExtensionUpdates: StateFlow<Boolean> = combine(
		mihonExtensionManager.installedExtensions,
		extensionStoreManager.states,
		settings.observeAsFlow(AppSettings.KEY_PRIVATE_INSTALLER) { isPrivateInstallEnabled },
	) { installed, stores, privateMode ->
		val mode = if (privateMode) ExtensionInstallMode.SANDBOX else ExtensionInstallMode.SYSTEM
		installed.any { local ->
			val owner = extensionStoreManager.owner(mode, local.pkgName) ?: return@any false
			val state = stores.firstOrNull { it.store.id == owner.id } ?: return@any false
			owner.enabled &&
				state.health == StoreHealth.AVAILABLE &&
				state.catalog.any { it.packageName == local.pkgName && it.isNewerThan(local) }
		}
	}.onEach { hasUpdates ->
		if (hasUpdates) {
			if (settings.isPrivateInstallEnabled) {
				extensionUpdateScheduler.startNow()
			} else if (
				settings.isShizukuInstallerEnabled &&
				settings.isAutoUpdateExtensionsEnabled
			) {
				extensionUpdateScheduler.startNow()
			}
		}
	}.flowOn(Dispatchers.IO)
		.stateIn(
			scope = viewModelScope + Dispatchers.IO,
			started = SharingStarted.WhileSubscribed(5000),
			initialValue = false
		)

	init {
		launchJob(Dispatchers.IO) {
			extensionStoreManager.initialize()
		}
	}

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
