package org.koitharu.kotatsu.sync.work

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.util.ext.awaitUniqueWorkInfoByName
import org.koitharu.kotatsu.core.util.ext.checkNotificationPermission
import org.koitharu.kotatsu.settings.work.PeriodicWorkScheduler
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.domain.GoogleDriveSyncRepository
import org.koitharu.kotatsu.sync.domain.SyncResult
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Background sync: the periodic fallback and the push when the app goes to the background. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted workerParams: WorkerParameters,
	private val repository: GoogleDriveSyncRepository,
	private val syncSettings: SyncSettings,
) : CoroutineWorker(context, workerParams) {

	override suspend fun doWork(): Result {
		// Waits for a sync the app may still be running, so changes made right before leaving it go out.
		return when (val result = repository.sync(force = true)) {
			is SyncResult.Success -> Result.success()
			is SyncResult.SignInRequired -> {
				// Background sync is dead until the user re-consents — say so instead of failing forever.
				if (syncSettings.isSignedIn) showSignInRequiredNotification()
				Result.failure()
			}

			is SyncResult.Error -> if (result.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
		}
	}

	override suspend fun getForegroundInfo(): ForegroundInfo {
		createNotificationChannel(applicationContext)
		val notification = buildNotification()
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
		} else {
			ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
		}
	}

	private fun showSignInRequiredNotification() {
		if (!applicationContext.checkNotificationPermission(CHANNEL_ID)) {
			return
		}
		createNotificationChannel(applicationContext)
		val intent = PendingIntent.getActivity(
			applicationContext,
			0,
			AppRouter.syncSettingsIntent(applicationContext),
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
		)
		val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
			.setContentTitle(applicationContext.getString(R.string.sync_sign_in_required))
			.setContentText(applicationContext.getString(R.string.sync_sign_in_required_text))
			.setSmallIcon(R.drawable.ic_sync)
			.setContentIntent(intent)
			.setAutoCancel(true)
			.setCategory(NotificationCompat.CATEGORY_ERROR)
			.build()
		NotificationManagerCompat.from(applicationContext).notify(SIGN_IN_NOTIFICATION_ID, notification)
	}

	private fun buildNotification(): Notification {
		return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
			.setContentTitle(applicationContext.getString(R.string.sync_in_progress))
			.setPriority(NotificationCompat.PRIORITY_MIN)
			.setDefaults(0)
			.setSilent(true)
			.setOngoing(true)
			.setProgress(0, 0, true)
			.setSmallIcon(R.drawable.ic_sync)
			.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
			.setCategory(NotificationCompat.CATEGORY_PROGRESS)
			.build()
	}

	class Scheduler @Inject constructor(
		private val workManager: WorkManager,
		private val syncSettings: SyncSettings,
	) : PeriodicWorkScheduler {

		override suspend fun schedule() {
			val intervalMinutes = syncSettings.intervalMinutes
			if (intervalMinutes <= 0 || !syncSettings.isSignedIn) {
				return unschedule()
			}
			val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes.toLong(), TimeUnit.MINUTES)
				.setConstraints(constraints(syncSettings.isWifiOnly))
				.addTag(TAG_PERIODIC)
				.setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
				.build()
			workManager.enqueueUniquePeriodicWork(TAG_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request).await()
		}

		override suspend fun unschedule() {
			workManager.cancelUniqueWork(TAG_PERIODIC).await()
		}

		override suspend fun isScheduled(): Boolean =
			workManager.awaitUniqueWorkInfoByName(TAG_PERIODIC).any { !it.state.isFinished }

		/** Runs a sync as soon as the network allows, surviving the app process being killed. */
		fun syncSoon() {
			val request = OneTimeWorkRequestBuilder<SyncWorker>()
				.setConstraints(constraints(syncSettings.isWifiOnly))
				.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
				.addTag(TAG_SOON)
				.setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
				.build()
			// Append rather than replace: a sync already running is never cut off half-way.
			workManager.enqueueUniqueWork(TAG_SOON, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
		}

		private fun constraints(isWifiOnly: Boolean) = Constraints.Builder()
			.setRequiredNetworkType(if (isWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
			.build()
	}

	companion object {

		const val CHANNEL_ID = "sync_status"
		private const val FOREGROUND_NOTIFICATION_ID = 45
		private const val SIGN_IN_NOTIFICATION_ID = 46
		private const val TAG_PERIODIC = "gdrive_sync_periodic"
		private const val TAG_SOON = "gdrive_sync_soon"
		private const val MAX_ATTEMPTS = 3

		fun createNotificationChannel(context: Context) {
			val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
				.setName(context.getString(R.string.google_drive_sync))
				.setShowBadge(false)
				.setVibrationEnabled(false)
				.setSound(null, null)
				.setLightsEnabled(false)
				.build()
			NotificationManagerCompat.from(context).createNotificationChannel(channel)
		}
	}
}
