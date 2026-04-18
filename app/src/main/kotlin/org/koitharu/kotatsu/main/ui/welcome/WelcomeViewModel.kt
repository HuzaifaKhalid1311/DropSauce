package org.koitharu.kotatsu.main.ui.welcome

import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.backup.MihonBackupManager
import org.koitharu.kotatsu.backup.MihonBackupManager.RestoreReport
import org.koitharu.kotatsu.core.prefs.ColorScheme
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.local.data.LocalStorageManager
import javax.inject.Inject

data class RestoreBackupResult(
	val report: RestoreReport?,
	val error: Throwable?,
)

@HiltViewModel
class WelcomeViewModel @Inject constructor(
	repository: MangaSourcesRepository,
	private val settings: AppSettings,
	private val storageManager: LocalStorageManager,
	private val backupManager: MihonBackupManager,
) : BaseViewModel() {

	val selectedTheme = MutableStateFlow(settings.theme)
	val selectedColorScheme = MutableStateFlow(settings.colorScheme)
	val isAmoledEnabled = MutableStateFlow(settings.isAmoledTheme)
	val storageSummary = MutableStateFlow<String?>(null)
	val onBackupRestored = MutableEventFlow<RestoreBackupResult>()

	init {
		launchJob(Dispatchers.Default) {
			repository.clearNewSourcesBadge()
			refreshStorageSummary()
		}
	}

	fun setTheme(mode: Int) {
		if (selectedTheme.value == mode) {
			return
		}
		selectedTheme.value = mode
		settings.setTheme(mode)
	}

	fun setColorScheme(colorScheme: ColorScheme) {
		if (selectedColorScheme.value == colorScheme) {
			return
		}
		selectedColorScheme.value = colorScheme
		settings.setColorScheme(colorScheme)
	}

	fun setAmoledTheme(isEnabled: Boolean) {
		if (isAmoledEnabled.value == isEnabled) {
			return
		}
		isAmoledEnabled.value = isEnabled
		settings.setAmoledTheme(isEnabled)
	}

	fun refreshStorageSummary() {
		launchJob(Dispatchers.Default) {
			val summary = storageManager.getDefaultWriteableDir()?.let {
				storageManager.getDirectoryDisplayName(it, isFullPath = true)
			}
			storageSummary.value = summary
		}
	}

	fun restoreBackup(uri: Uri) {
		launchLoadingJob(Dispatchers.Default) {
			val result = runCatching {
				backupManager.restoreBackup(uri, MihonBackupManager.Options())
			}
			onBackupRestored.call(
				RestoreBackupResult(
					report = result.getOrNull(),
					error = result.exceptionOrNull(),
				),
			)
		}
	}

	fun completeOnboarding() {
		settings.isOnboardingCompleted = true
	}
}
