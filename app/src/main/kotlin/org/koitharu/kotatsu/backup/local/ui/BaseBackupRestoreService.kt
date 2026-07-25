package org.koitharu.kotatsu.backup.local.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ShareCompat
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backup.local.domain.BackupUtils
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.CoroutineIntentService
import org.koitharu.kotatsu.core.util.CompositeResult
import org.koitharu.kotatsu.core.util.ext.checkNotificationPermission
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import androidx.appcompat.R as appcompatR

abstract class BaseBackupRestoreService : CoroutineIntentService() {

	protected abstract val notificationTag: String
	protected abstract val isRestoreService: Boolean

	protected lateinit var notificationManager: NotificationManagerCompat
		private set

	override fun onCreate() {
		super.onCreate()
		notificationManager = NotificationManagerCompat.from(applicationContext)
		createNotificationChannel(this)
	}

	override fun IntentJobContext.onError(error: Throwable) {
		showResultNotification(null, CompositeResult.failure(error))
	}

	protected fun IntentJobContext.showResultNotification(
		fileUri: Uri?,
		result: CompositeResult,
	) {
		if (!applicationContext.checkNotificationPermission(CHANNEL_ID)) {
			return
		}
		val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
			.setPriority(NotificationCompat.PRIORITY_HIGH)
			.setDefaults(0)
			.setSilent(true)
			.setAutoCancel(true)
			.setSubText(fileUri?.let { getFileDisplayName(it) })
		when {
			result.isAllSuccess -> {
				if (isRestoreService) {
					notification
						.setContentTitle(getString(R.string.restoring_backup))
						.setContentText(getString(R.string.data_restored_success))
				} else {
					notification
						.setContentTitle(getString(R.string.backup_saved))
						.setContentText(fileUri?.let { getFileDisplayName(it) })
						.setSubText(null)
				}
				notification.setSmallIcon(R.drawable.general_notification)
			}

			result.isAllFailed || !isRestoreService -> {
				val title = getString(if (isRestoreService) R.string.data_not_restored else R.string.error_occurred)
				val message = result.failures.joinToString("\n") {
					it.getDisplayMessage(applicationContext.resources)
				}
				notification
					.setContentTitle(title)
					.setContentText(if (isRestoreService) getString(R.string.data_not_restored_text) else message)
					.setStyle(
						NotificationCompat.BigTextStyle()
							.bigText(message)
							.setSummaryText(message)
							.setBigContentTitle(title),
					)
					.setSmallIcon(android.R.drawable.stat_notify_error)
			}

			else -> {
				notification
					.setContentTitle(getString(R.string.restoring_backup))
					.setContentText(getString(R.string.data_restored_with_errors))
					.setSmallIcon(R.drawable.general_notification)
			}
		}
		notification.setContentIntent(
			PendingIntentCompat.getActivity(
				applicationContext,
				0,
				AppRouter.homeIntent(this@BaseBackupRestoreService),
				0,
				false,
			),
		)
		if (!isRestoreService && fileUri != null && result.isAllSuccess) {
			val shareIntent = ShareCompat.IntentBuilder(this@BaseBackupRestoreService)
				.setStream(fileUri)
				.setType(BackupUtils.MIME_TYPE)
				.setChooserTitle(R.string.share)
				.createChooserIntent()
			notification.addAction(
				appcompatR.drawable.abc_ic_menu_share_mtrl_alpha,
				getString(R.string.share),
				PendingIntentCompat.getActivity(this@BaseBackupRestoreService, 0, shareIntent, 0, false),
			)
		}
		notificationManager.notify(notificationTag, startId, notification.build())
	}

	protected fun getFileDisplayName(uri: Uri): String? = try {
		if (uri.scheme == "file") {
			uri.lastPathSegment
		} else {
			contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
				if (cursor.moveToFirst()) cursor.getString(0) else null
			} ?: uri.lastPathSegment
		}
	} catch (e: Throwable) {
		e.printStackTraceDebug()
		null
	}

	companion object {

		const val CHANNEL_ID = "backup_restore"

		fun createNotificationChannel(context: Context) {
			val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
				.setName(context.getString(R.string.backup_restore))
				.setShowBadge(false)
				.setVibrationEnabled(false)
				.setSound(null, null)
				.setLightsEnabled(false)
				.build()
			NotificationManagerCompat.from(context).createNotificationChannel(channel)
		}
	}
}
