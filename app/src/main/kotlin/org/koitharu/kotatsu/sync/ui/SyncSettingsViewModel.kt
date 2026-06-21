package org.koitharu.kotatsu.sync.ui

import android.content.Intent
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.sync.data.GoogleDriveAuth
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.data.model.SyncContent
import org.koitharu.kotatsu.sync.domain.GoogleDriveSyncRepository
import org.koitharu.kotatsu.sync.domain.SyncResult
import org.koitharu.kotatsu.sync.work.SyncWorker
import javax.inject.Inject

data class SyncUiState(
	val isSignedIn: Boolean = false,
	val accountEmail: String? = null,
	val accountName: String? = null,
	val accountPhotoUrl: String? = null,
	val isEmailHidden: Boolean = false,
	val intervalMinutes: Int = 0,
	val isWifiOnly: Boolean = false,
	val isSyncOnStart: Boolean = false,
	val enabledContent: Set<String> = SyncContent.DEFAULT,
	val lastSyncTimestamp: Long = 0L,
	val lastError: String? = null,
)

sealed interface SyncEvent {
	data class LaunchSignIn(val intent: Intent) : SyncEvent
	data class Message(val resId: Int) : SyncEvent
	data class Error(val message: String?) : SyncEvent
}

@HiltViewModel
class SyncSettingsViewModel @Inject constructor(
	private val syncSettings: SyncSettings,
	private val repository: GoogleDriveSyncRepository,
	private val auth: GoogleDriveAuth,
	private val scheduler: SyncWorker.Scheduler,
) : BaseViewModel() {

	val uiState = MutableStateFlow(readState())
	val isSyncing = repository.isSyncing
	val events = MutableEventFlow<SyncEvent>()

	init {
		// Refresh the displayed "last synced" / error info whenever a background sync finishes.
		viewModelScope.launch {
			isSyncing.drop(1).collect { running ->
				if (!running) refresh()
			}
		}
	}

	fun signIn() {
		events.call(SyncEvent.LaunchSignIn(auth.signInIntent))
	}

	fun onSignInResult(data: Intent?) {
		launchLoadingJob(Dispatchers.Default) {
			val account = try {
				auth.accountFromIntent(data)
			} catch (e: Exception) {
				events.call(SyncEvent.Error(e.message ?: e.javaClass.simpleName))
				return@launchLoadingJob
			}
			repository.onSignedIn(account.email, account.displayName, account.photoUrl?.toString())
			refresh()
			scheduler.schedule()
			runSync() // pull existing data right away, surfacing any error
		}
	}

	/** Runs a sync in the foreground and reports the outcome to the user. */
	private suspend fun runSync() {
		when (val result = repository.sync()) {
			is SyncResult.Success -> events.call(SyncEvent.Message(R.string.sync_completed))
			is SyncResult.AlreadyRunning -> events.call(SyncEvent.Message(R.string.sync_in_progress))
			is SyncResult.SignInRequired -> events.call(SyncEvent.Error(null))
			is SyncResult.Error -> events.call(SyncEvent.Error(result.message))
		}
		refresh()
	}

	fun signOut() {
		launchJob(Dispatchers.Default) {
			repository.signOut()
			scheduler.unschedule()
			refresh()
		}
	}

	fun syncNow() {
		if (!syncSettings.isSignedIn) return
		launchLoadingJob(Dispatchers.Default) { runSync() }
	}

	fun setInterval(minutes: Int) {
		syncSettings.intervalMinutes = minutes
		launchJob(Dispatchers.Default) { scheduler.schedule() }
		refresh()
	}

	fun setWifiOnly(value: Boolean) {
		syncSettings.isWifiOnly = value
		launchJob(Dispatchers.Default) { scheduler.schedule() }
		refresh()
	}

	fun setSyncOnStart(value: Boolean) {
		syncSettings.isSyncOnStart = value
		refresh()
	}

	fun setEmailHidden(value: Boolean) {
		syncSettings.isEmailHidden = value
		refresh()
	}

	fun setEnabledContent(content: Set<String>) {
		syncSettings.enabledContent = content
		refresh()
	}

	fun deleteAllData() {
		launchLoadingJob(Dispatchers.Default) {
			when (val result = repository.deleteRemoteData()) {
				is SyncResult.Success -> events.call(SyncEvent.Message(R.string.sync_data_deleted))
				is SyncResult.AlreadyRunning -> events.call(SyncEvent.Message(R.string.sync_in_progress))
				is SyncResult.SignInRequired -> events.call(SyncEvent.Error(null))
				is SyncResult.Error -> events.call(SyncEvent.Error(result.message))
			}
			refresh()
		}
	}

	private fun refresh() {
		uiState.value = readState()
	}

	private fun readState() = SyncUiState(
		isSignedIn = syncSettings.isSignedIn,
		accountEmail = syncSettings.accountEmail,
		accountName = syncSettings.accountName,
		accountPhotoUrl = syncSettings.accountPhotoUrl,
		isEmailHidden = syncSettings.isEmailHidden,
		intervalMinutes = syncSettings.intervalMinutes,
		isWifiOnly = syncSettings.isWifiOnly,
		isSyncOnStart = syncSettings.isSyncOnStart,
		enabledContent = syncSettings.enabledContent,
		lastSyncTimestamp = syncSettings.lastSyncTimestamp,
		lastError = syncSettings.lastSyncError,
	)
}
