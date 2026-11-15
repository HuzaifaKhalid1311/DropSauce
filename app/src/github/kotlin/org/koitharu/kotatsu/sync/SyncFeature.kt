package org.koitharu.kotatsu.sync

import android.app.PendingIntent
import android.content.Intent
import androidx.fragment.app.Fragment
import org.koitharu.kotatsu.settings.work.PeriodicWorkScheduler
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.domain.GoogleDriveSyncRepository
import org.koitharu.kotatsu.sync.ui.SyncSettingsFragment
import org.koitharu.kotatsu.sync.work.SyncTrigger
import org.koitharu.kotatsu.sync.work.SyncWorker
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Google Drive sync as the shared code sees it. GitHub version only: the F-Droid version has its
 * own SyncFeature without sync, because Drive sign-in needs Google Play Services.
 */
@Singleton
class SyncFeature @Inject constructor(
	private val syncSettings: SyncSettings,
	private val scheduler: SyncWorker.Scheduler,
	private val trigger: SyncTrigger,
	private val repository: Provider<GoogleDriveSyncRepository>,
) {

	val periodicScheduler: PeriodicWorkScheduler? get() = scheduler

	val isPeriodicEnabled: Boolean get() = syncSettings.isSignedIn && syncSettings.intervalMinutes > 0

	/** Syncs on foreground and every few minutes while the app is open (see [SyncTrigger]). */
	fun startTriggers() = trigger.init()

	/** The consent screen to launch, or null when access was already granted. */
	suspend fun beginSignIn(): PendingIntent? = repository.get().beginSignIn()

	suspend fun finishSignIn(data: Intent?) = repository.get().finishSignIn(data)

	suspend fun onSignedIn() {
		scheduler.schedule()
		scheduler.syncSoon() // bring the library over now, not at the next periodic run
	}

	companion object {

		const val IS_AVAILABLE = true

		val settingsFragmentClass: Class<out Fragment>? = SyncSettingsFragment::class.java

		fun createSettingsFragment(): Fragment? = SyncSettingsFragment()
	}
}
