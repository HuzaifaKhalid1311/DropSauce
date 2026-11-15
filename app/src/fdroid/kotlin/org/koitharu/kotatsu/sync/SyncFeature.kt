package org.koitharu.kotatsu.sync

import android.app.PendingIntent
import android.content.Intent
import androidx.fragment.app.Fragment
import androidx.work.WorkManager
import org.koitharu.kotatsu.settings.work.PeriodicWorkScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F-Droid version: no Google Drive sync (its sign-in needs Google Play Services, which F-Droid
 * does not allow). Same shape as the GitHub version's SyncFeature; the sync UI is hidden
 * ([IS_AVAILABLE]), so the sign-in calls are never made.
 */
@Singleton
class SyncFeature @Inject constructor(
	private val workManager: WorkManager,
) {

	val periodicScheduler: PeriodicWorkScheduler? get() = null

	val isPeriodicEnabled: Boolean get() = false

	/**
	 * After switching over from the GitHub version, its sync jobs would stay queued forever with no
	 * worker to run them (names as in the GitHub SyncWorker). Cancelling missing work does nothing.
	 */
	fun startTriggers() {
		workManager.cancelUniqueWork("gdrive_sync_periodic")
		workManager.cancelUniqueWork("gdrive_sync_soon")
	}

	suspend fun beginSignIn(): PendingIntent? = throw UnsupportedOperationException()

	suspend fun finishSignIn(data: Intent?): Unit = throw UnsupportedOperationException()

	suspend fun onSignedIn() = Unit

	companion object {

		const val IS_AVAILABLE = false

		val settingsFragmentClass: Class<out Fragment>? = null

		fun createSettingsFragment(): Fragment? = null
	}
}
