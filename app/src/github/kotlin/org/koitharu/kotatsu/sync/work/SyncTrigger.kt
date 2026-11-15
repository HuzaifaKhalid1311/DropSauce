package org.koitharu.kotatsu.sync.work

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.util.ext.connectivityManager
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.domain.GoogleDriveSyncRepository
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Keeps devices close to real time without a server: sync when the app comes to the foreground and
 * every few minutes while it stays there (cheap when nothing changed), and push reliably through
 * WorkManager when it goes to the background — the moment someone switches to another device.
 */
@Singleton
class SyncTrigger @Inject constructor(
	@ApplicationContext private val context: Context,
	// Built at app start on the main thread, so the sync engine (and the OkHttp client behind it,
	// which must not be created on the main thread) is only resolved inside the background sync.
	private val repositoryProvider: Provider<GoogleDriveSyncRepository>,
	private val syncSettings: SyncSettings,
	private val scheduler: SyncWorker.Scheduler,
) : DefaultLifecycleObserver {

	private var foregroundLoop: Job? = null

	/** Must be called on the main thread. */
	fun init() {
		ProcessLifecycleOwner.get().lifecycle.addObserver(this)
	}

	override fun onStart(owner: LifecycleOwner) {
		foregroundLoop?.cancel()
		foregroundLoop = processLifecycleScope.launch(Dispatchers.Default) {
			while (true) {
				// Not a child of this loop: leaving the app stops the loop, never a sync mid-way.
				processLifecycleScope.launch(Dispatchers.Default) {
					if (syncSettings.isSignedIn && isNetworkAllowed()) repositoryProvider.get().sync()
				}
				delay(FOREGROUND_INTERVAL_MS)
			}
		}
	}

	override fun onStop(owner: LifecycleOwner) {
		foregroundLoop?.cancel()
		foregroundLoop = null
		if (syncSettings.isSignedIn) {
			scheduler.syncSoon()
		}
	}

	private fun isNetworkAllowed(): Boolean {
		val cm = context.connectivityManager
		return cm.activeNetwork != null && (!syncSettings.isWifiOnly || !cm.isActiveNetworkMetered)
	}

	private companion object {

		const val FOREGROUND_INTERVAL_MS = 5L * 60 * 1000
	}
}
