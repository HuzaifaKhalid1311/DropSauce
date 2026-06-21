package org.koitharu.kotatsu.sync.work

import android.app.Notification
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
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.awaitUniqueWorkInfoByName
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.trySetForeground
import org.koitharu.kotatsu.settings.work.PeriodicWorkScheduler
import org.koitharu.kotatsu.sync.data.SyncSettings
import org.koitharu.kotatsu.sync.domain.GoogleDriveSyncRepository
import org.koitharu.kotatsu.sync.domain.SyncResult
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltWorker
class SyncWorker @AssistedInject constructor(
	@Assisted context: Context,
	@Assisted workerParams: WorkerParameters,
	private val repository: GoogleDriveSyncRepository,
) : CoroutineWorker(context, workerParams) {

	override suspend fun doWork(): Result {
		trySetForeground()
		return try {
			when (val result = repository.sync()) {
				is SyncResult.Success -> Result.success()
				is SyncResult.AlreadyRunning -> Result.success()
				is SyncResult.SignInRequired -> {
					postReauthNotification()
					Result.failure()
				}
				is SyncResult.Error ->
					if (result.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
			}
		} catch (e: Exception) {
			e.printStackTraceDebug()
			if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
		}
	}

	private fun postReauthNotification() {
		val context = applicationContext
		createNotificationChannel(context)
		val notification = NotificationCompat.Builder(context, CHANNEL_ID)
			.setContentTitle(context.getString(R.string.google_drive_sync))
			.setContentText("Re-authentication required")
			.setSmallIcon(R.drawable.ic_sync)
			.setAutoCancel(true)
			.setPriority(NotificationCompat.PRIORITY_HIGH)
			.build()
		try {
			NotificationManagerCompat.from(context).notify(REAUTH_NOTIFICATION_ID, notification)
		} catch (e: SecurityException) {
			// Ignore if notification permission is missing
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
			val constraints = Constraints.Builder()
				.setRequiredNetworkType(if (syncSettings.isWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
				.build()
			val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes.toLong(), TimeUnit.MINUTES)
				.setConstraints(constraints)
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
	}

	companion object {

		const val CHANNEL_ID = "sync_status"
		private const val FOREGROUND_NOTIFICATION_ID = 45
		private const val REAUTH_NOTIFICATION_ID = 46
		private const val TAG_PERIODIC = "gdrive_sync_periodic"
		private const val TAG_MANUAL = "gdrive_sync_manual"
		private const val MAX_ATTEMPTS = 3

		fun enqueueManual(workManager: WorkManager) {
			val constraints = Constraints.Builder()
				.setRequiredNetworkType(NetworkType.CONNECTED)
				.build()
			val request = OneTimeWorkRequestBuilder<SyncWorker>()
				.setConstraints(constraints)
				.addTag(TAG_MANUAL)
				.setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
				.build()
			workManager.enqueueUniqueWork(TAG_MANUAL, ExistingWorkPolicy.KEEP, request)
		}

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
