package org.koitharu.kotatsu.settings.about

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import org.koitharu.kotatsu.core.github.AppUpdateRepository
import org.koitharu.kotatsu.core.github.AppVersion
import org.koitharu.kotatsu.core.logs.AppLogger
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import javax.inject.Inject

@HiltViewModel
class AboutSettingsViewModel @Inject constructor(
	private val appUpdateRepository: AppUpdateRepository,
	private val settings: AppSettings,
	private val appLogger: AppLogger,
) : BaseViewModel() {

	val isUpdateSupported = flow {
		emit(appUpdateRepository.isUpdateSupported())
	}.stateIn(viewModelScope, SharingStarted.Eagerly, false)

	val onUpdateAvailable = MutableEventFlow<AppVersion?>()
	val onExportLog = MutableEventFlow<String>()

	private val _isVerboseLogging = MutableStateFlow(settings.isVerboseLoggingEnabled)
	val isVerboseLogging: StateFlow<Boolean> = _isVerboseLogging

	private val hasLogs = MutableStateFlow(appLogger.hasRecordedLogs())
	val canExportLog: StateFlow<Boolean> = combine(_isVerboseLogging, hasLogs) { recording, saved -> recording || saved }
		.stateIn(viewModelScope, SharingStarted.Eagerly, _isVerboseLogging.value || hasLogs.value)

	fun checkForUpdates() {
		launchLoadingJob {
			val update = appUpdateRepository.fetchUpdate()
			onUpdateAvailable.call(update)
		}
	}

	fun setVerboseLogging(enabled: Boolean) {
		settings.isVerboseLoggingEnabled = enabled
		_isVerboseLogging.value = enabled
		launchJob(Dispatchers.Default) {
			if (enabled) {
				appLogger.startNewRecording()
			} else {
				val content = appLogger.stopAndExport()
				if (content.isNotBlank()) {
					onExportLog.call(content)
				}
			}
			hasLogs.value = appLogger.hasRecordedLogs()
		}
	}

	/** Saves what was recorded so far; a running recording keeps going. */
	fun exportLog() {
		launchJob(Dispatchers.Default) {
			val content = appLogger.export()
			if (content.isNotBlank()) {
				onExportLog.call(content)
			}
		}
	}

	/** The exported log reached its file, so a stopped recording can go; an unsaved one is kept until the next. */
	fun onLogSaved() {
		launchJob(Dispatchers.Default) {
			if (!_isVerboseLogging.value) {
				appLogger.clear()
			}
			hasLogs.value = appLogger.hasRecordedLogs()
		}
	}
}
