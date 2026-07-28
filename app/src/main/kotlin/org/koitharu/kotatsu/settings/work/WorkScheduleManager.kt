package org.koitharu.kotatsu.settings.work

import android.content.Context
import android.content.SharedPreferences
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.backup.local.ui.periodical.PeriodicalBackupWorker
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.extensions.install.ExtensionUpdateWorker
import org.koitharu.kotatsu.lnreader.LnPluginManager
import org.koitharu.kotatsu.suggestions.ui.SuggestionsWorker
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.work.SyncWorker
import org.koitharu.kotatsu.tracker.work.TrackWorker
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkScheduleManager @Inject constructor(
	private val settings: AppSettings,
	private val syncSettings: SyncSettings,
	private val workManager: WorkManager,
	private val suggestionScheduler: SuggestionsWorker.Scheduler,
	private val trackerScheduler: TrackWorker.Scheduler,
	private val backupScheduler: PeriodicalBackupWorker.Scheduler,
	private val syncScheduler: SyncWorker.Scheduler,
	private val extensionUpdateScheduler: ExtensionUpdateWorker.Scheduler,
	@ApplicationContext private val context: Context,
) : SharedPreferences.OnSharedPreferenceChangeListener {

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		when (key) {
			AppSettings.KEY_TRACKER_ENABLED,
			AppSettings.KEY_TRACKER_FREQUENCY,
			AppSettings.KEY_TRACKER_WIFI_ONLY -> updateWorker(
				scheduler = trackerScheduler,
				isEnabled = settings.isTrackerEnabled,
				force = key != AppSettings.KEY_TRACKER_ENABLED,
			)

			AppSettings.KEY_SUGGESTIONS,
			AppSettings.KEY_SUGGESTIONS_WIFI_ONLY -> updateWorker(
				scheduler = suggestionScheduler,
				isEnabled = settings.isSuggestionsEnabled,
				force = key != AppSettings.KEY_SUGGESTIONS,
			)

			AppSettings.KEY_BACKUP_PERIODICAL_ENABLED,
			AppSettings.KEY_BACKUP_PERIODICAL_FREQ,
			AppSettings.KEY_BACKUP_PERIODICAL_OUTPUT -> updateWorker(
				scheduler = backupScheduler,
				isEnabled = settings.isPeriodicalBackupEnabled && settings.periodicalBackupDirectory != null,
				force = key != AppSettings.KEY_BACKUP_PERIODICAL_ENABLED,
			)

			AppSettings.KEY_AUTO_UPDATE_EXTENSIONS,
			AppSettings.KEY_SHIZUKU_INSTALLER,
			AppSettings.KEY_EXTENSION_UPDATE_NOTIFICATIONS,
			AppSettings.KEY_PRIVATE_INSTALLER -> {
				val enabled = isExtensionUpdateWorkerNeeded()
				updateWorker(extensionUpdateScheduler, enabled, force = false)
				if (enabled) {
					processLifecycleScope.launch(Dispatchers.Default) {
						extensionUpdateScheduler.startNow()
					}
				}
			}
		}
	}

	fun init() {
		settings.subscribe(this)
		processLifecycleScope.launch(Dispatchers.Default) {
			updateWorkerImpl(trackerScheduler, settings.isTrackerEnabled, true)
			updateWorkerImpl(suggestionScheduler, settings.isSuggestionsEnabled, false)
			updateWorkerImpl(
				scheduler = backupScheduler,
				isEnabled = settings.isPeriodicalBackupEnabled && settings.periodicalBackupDirectory != null,
				force = false,
			)
			// Sync interval lives in its own prefs file (not observed here); just re-assert the
			// schedule on app start, and optionally kick off a one-shot sync if the user opted in.
			updateWorkerImpl(
				scheduler = syncScheduler,
				isEnabled = syncSettings.isSignedIn && syncSettings.intervalMinutes > 0,
				force = false,
			)
			val extensionUpdatesEnabled = isExtensionUpdateWorkerNeeded()
			updateWorkerImpl(extensionUpdateScheduler, extensionUpdatesEnabled, force = false)
			if (extensionUpdatesEnabled) {
				extensionUpdateScheduler.startNow()
			}
			if (syncSettings.isSignedIn && syncSettings.isSyncOnStart) {
				SyncWorker.enqueueManual(workManager)
			}
		}
	}

	// The worker checks for updates when either auto-install (Shizuku) or the update notification
	// is turned on — either one needs the periodic repo check to run. Installed novel plugins need it
	// unconditionally: updating them is just writing a file, so there is nothing to opt into.
	private fun isExtensionUpdateWorkerNeeded(): Boolean =
		settings.isPrivateInstallEnabled ||
			(settings.isAutoUpdateExtensionsEnabled && settings.isShizukuInstallerEnabled) ||
			settings.isExtensionUpdateNotificationsEnabled ||
			LnPluginManager.hasInstalledPlugins(context)

	private fun updateWorker(scheduler: PeriodicWorkScheduler, isEnabled: Boolean, force: Boolean) {
		processLifecycleScope.launch(Dispatchers.Default) {
			updateWorkerImpl(scheduler, isEnabled, force)
		}
	}

	private suspend fun updateWorkerImpl(scheduler: PeriodicWorkScheduler, isEnabled: Boolean, force: Boolean) {
		if (force || scheduler.isScheduled() != isEnabled) {
			if (isEnabled) {
				scheduler.schedule()
			} else {
				scheduler.unschedule()
			}
		}
	}
}
