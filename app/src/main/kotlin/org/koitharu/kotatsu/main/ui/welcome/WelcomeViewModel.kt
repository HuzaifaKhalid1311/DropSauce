package org.koitharu.kotatsu.main.ui.welcome

import android.content.Intent
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
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.sync.data.GoogleDriveAuth
import org.koitharu.kotatsu.sync.domain.GoogleDriveSyncRepository
import org.koitharu.kotatsu.sync.work.SyncWorker
import javax.inject.Inject

data class RestoreBackupResult(
	val report: RestoreReport?,
	val error: Throwable?,
)

@HiltViewModel
class WelcomeViewModel @Inject constructor(
	private val settings: AppSettings,
	private val storageManager: LocalStorageManager,
	private val backupManager: MihonBackupManager,
	private val googleDriveAuth: GoogleDriveAuth,
	private val syncRepository: GoogleDriveSyncRepository,
	private val syncScheduler: SyncWorker.Scheduler,
) : BaseViewModel() {

	val selectedTheme = MutableStateFlow(settings.theme)
	val selectedColorScheme = MutableStateFlow(settings.colorScheme)
	val isAmoledEnabled = MutableStateFlow(settings.isAmoledTheme)
	val storageSummary = MutableStateFlow<String?>(null)
	val onBackupRestored = MutableEventFlow<RestoreBackupResult>()
	val onGoogleSignInLaunch = MutableEventFlow<Intent>()
	val onGoogleSignInCompleted = MutableEventFlow<Boolean>()

	init {
		launchJob(Dispatchers.Default) {
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

	fun launchGoogleSignIn() {
		onGoogleSignInLaunch.call(googleDriveAuth.signInIntent)
	}

	fun handleGoogleSignInResult(data: Intent?) {
		launchLoadingJob(Dispatchers.Default) {
			val account = runCatching { googleDriveAuth.accountFromIntent(data) }.getOrNull()
			if (account != null) {
				syncRepository.onSignedIn(account.email, account.displayName, account.photoUrl?.toString())
				syncScheduler.schedule()
				syncScheduler.syncSoon() // bring the library over now, not at the next periodic run
				onGoogleSignInCompleted.call(true)
			} else {
				onGoogleSignInCompleted.call(false)
			}
		}
	}

	fun completeOnboarding() {
		settings.isOnboardingCompleted = true
	}
}
