package org.koitharu.kotatsu.reader.ui.tts

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LifecycleService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import javax.inject.Inject

/**
 * Keeps [ReaderTts] alive and controllable while the reader is in the background. It owns nothing
 * but the notification — every button routes straight into the shared controller.
 */
@AndroidEntryPoint
class ReaderTtsService : LifecycleService() {

	@Inject
	lateinit var tts: ReaderTts

	@Inject
	lateinit var settings: AppSettings

	private var title: String = ""

	override fun onCreate() {
		super.onCreate()
		createNotificationChannel(this)
		tts.isPlaying.onEach { isPlaying ->
			if (tts.isAttached) {
				notify(isPlaying)
			} else {
				stopSelf()
			}
		}.launchIn(lifecycleScope)
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)
		intent?.getStringExtra(EXTRA_TITLE)?.let { title = it }
		when (intent?.action) {
			ACTION_TOGGLE -> tts.toggle()
			ACTION_NEXT -> tts.skip(1)
			ACTION_PREVIOUS -> tts.skip(-1)
			ACTION_STOP -> {
				// The only explicit "stop" the user has: retire the quick-start button with it.
				settings.isReaderTtsFabVisible = false
				tts.stop()
				stopSelf()
				return START_NOT_STICKY
			}
		}
		startForeground()
		return START_NOT_STICKY
	}

	override fun onTaskRemoved(rootIntent: Intent?) {
		// Swiping the app away means done reading, not "keep talking from nowhere".
		tts.stop()
		stopSelf()
		super.onTaskRemoved(rootIntent)
	}

	private fun startForeground() {
		ServiceCompat.startForeground(
			this,
			NOTIFICATION_ID,
			buildNotification(tts.isPlaying.value),
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
			} else {
				0
			},
		)
	}

	private fun notify(isPlaying: Boolean) {
		NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(isPlaying))
	}

	private fun buildNotification(isPlaying: Boolean): android.app.Notification {
		val builder = NotificationCompat.Builder(this, CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_voice_over)
			.setContentTitle(title.ifEmpty { getString(R.string.text_to_speech) })
			.setContentText(getString(if (isPlaying) R.string.tts_playing else R.string.tts_paused))
			.setOngoing(isPlaying)
			.setSilent(true)
			.setCategory(NotificationCompat.CATEGORY_TRANSPORT)
			.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
			.addAction(
				android.R.drawable.ic_media_previous,
				getString(R.string.tts_previous_sentence),
				actionIntent(ACTION_PREVIOUS),
			)
			.addAction(
				if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
				getString(if (isPlaying) R.string.pause else R.string.resume),
				actionIntent(ACTION_TOGGLE),
			)
			.addAction(
				android.R.drawable.ic_media_next,
				getString(R.string.tts_next_sentence),
				actionIntent(ACTION_NEXT),
			)
			.addAction(
				android.R.drawable.ic_menu_close_clear_cancel,
				getString(R.string.stop),
				actionIntent(ACTION_STOP),
			)
		packageManager.getLaunchIntentForPackage(packageName)?.let {
			builder.setContentIntent(PendingIntentCompat.getActivity(this, 0, it, 0, false))
		}
		return builder.build()
	}

	private fun actionIntent(action: String) = PendingIntentCompat.getService(
		this,
		action.hashCode(),
		Intent(this, ReaderTtsService::class.java).setAction(action),
		0,
		false,
	)

	companion object {

		private const val CHANNEL_ID = "reader_tts"
		private const val NOTIFICATION_ID = 42
		private const val EXTRA_TITLE = "title"
		private const val ACTION_TOGGLE = "toggle"
		private const val ACTION_NEXT = "next"
		private const val ACTION_PREVIOUS = "previous"
		private const val ACTION_STOP = "stop"

		fun start(context: Context, title: String) {
			val intent = Intent(context, ReaderTtsService::class.java).putExtra(EXTRA_TITLE, title)
			ContextCompat.startForegroundService(context, intent)
		}

		fun stop(context: Context) {
			context.stopService(Intent(context, ReaderTtsService::class.java))
		}

		private fun createNotificationChannel(context: Context) {
			val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
				.setName(context.getString(R.string.text_to_speech))
				.setShowBadge(false)
				.setVibrationEnabled(false)
				.setSound(null, null)
				.setLightsEnabled(false)
				.build()
			NotificationManagerCompat.from(context).createNotificationChannel(channel)
		}
	}
}
