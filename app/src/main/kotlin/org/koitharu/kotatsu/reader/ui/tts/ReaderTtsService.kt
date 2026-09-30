package org.koitharu.kotatsu.reader.ui.tts

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Scale
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.getDrawableOrThrow
import org.koitharu.kotatsu.core.util.ext.getParcelableExtraCompat
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

/**
 * Keeps [ReaderTts] alive and controllable while the reader is in the background, as a regular
 * media player: a [MediaSession] feeds the lock screen / quick settings player (cover as the
 * background) and headset buttons. It owns nothing but that — every control routes straight into
 * the shared controller.
 */
@AndroidEntryPoint
class ReaderTtsService : LifecycleService() {

	@Inject
	lateinit var tts: ReaderTts

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var coil: ImageLoader

	private lateinit var session: MediaSession
	private var manga: Manga? = null
	private var cover: Bitmap? = null

	override fun onCreate() {
		super.onCreate()
		createNotificationChannel(this)
		session = MediaSession(this, "ReaderTts").apply {
			setCallback(SessionCallback())
			launchIntent()?.let { setSessionActivity(it) }
			isActive = true
		}
		tts.isPlaying.onEach { isPlaying ->
			if (tts.isAttached) {
				update(isPlaying)
			} else {
				stopSelf()
			}
		}.launchIn(lifecycleScope)
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)
		intent?.getParcelableExtraCompat<ParcelableManga>(EXTRA_MANGA)?.manga?.let { setManga(it) }
		when (intent?.action) {
			ACTION_TOGGLE -> tts.toggle()
			ACTION_NEXT -> tts.skip(1)
			ACTION_PREVIOUS -> tts.skip(-1)
			ACTION_STOP -> {
				stopPlayback()
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

	override fun onDestroy() {
		session.release()
		super.onDestroy()
	}

	private fun stopPlayback() {
		// The only explicit "stop" the user has: retire the quick-start button with it.
		settings.isReaderTtsFabVisible = false
		tts.stop()
		stopSelf()
	}

	private fun setManga(value: Manga) {
		if (manga?.id == value.id) {
			return
		}
		manga = value
		cover = null
		updateMetadata()
		lifecycleScope.launch {
			cover = loadCover(value) ?: return@launch
			updateMetadata()
			update(tts.isPlaying.value)
		}
	}

	private suspend fun loadCover(manga: Manga): Bitmap? = runCatchingCancellable {
		coil.execute(
			ImageRequest.Builder(this)
				.data(manga.largeCoverUrl ?: manga.coverUrl)
				// Parceled over to the system UI, so it has to be a software bitmap.
				.allowHardware(false)
				.mangaSourceExtra(manga.source)
				.size(COVER_SIZE)
				.scale(Scale.FILL)
				.build(),
		).getDrawableOrThrow().toBitmap()
	}.getOrNull()

	private fun updateMetadata() {
		session.setMetadata(
			MediaMetadata.Builder()
				.putString(MediaMetadata.METADATA_KEY_TITLE, title())
				.putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.text_to_speech))
				.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover)
				.build(),
		)
	}

	private fun update(isPlaying: Boolean) {
		session.setPlaybackState(
			PlaybackState.Builder()
				.setActions(
					PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
						PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
						PlaybackState.ACTION_STOP,
				)
				.addCustomAction(
					PlaybackState.CustomAction.Builder(ACTION_STOP, getString(R.string.stop), R.drawable.ic_close)
						.build(),
				)
				.setState(
					if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
					PlaybackState.PLAYBACK_POSITION_UNKNOWN,
					if (isPlaying) 1f else 0f,
				)
				.build(),
		)
		NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(isPlaying))
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

	private fun title() = manga?.title.orEmpty().ifEmpty { getString(R.string.text_to_speech) }

	private fun buildNotification(isPlaying: Boolean): Notification {
		val builder = Notification.Builder(this, CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_voice_over)
			.setLargeIcon(cover)
			.setContentTitle(title())
			.setContentText(getString(if (isPlaying) R.string.tts_playing else R.string.tts_paused))
			.setOngoing(isPlaying)
			.setOnlyAlertOnce(true)
			.setCategory(Notification.CATEGORY_TRANSPORT)
			.setVisibility(Notification.VISIBILITY_PUBLIC)
			.setDeleteIntent(actionIntent(ACTION_STOP))
			.setStyle(
				Notification.MediaStyle()
					.setMediaSession(session.sessionToken)
					.setShowActionsInCompactView(0, 1, 2),
			)
			.addAction(action(R.drawable.ic_skip_previous, R.string.tts_previous_sentence, ACTION_PREVIOUS))
			.addAction(
				if (isPlaying) {
					action(R.drawable.ic_pause, R.string.pause, ACTION_TOGGLE)
				} else {
					action(R.drawable.ic_play, R.string.resume, ACTION_TOGGLE)
				},
			)
			.addAction(action(R.drawable.ic_skip_next, R.string.tts_next_sentence, ACTION_NEXT))
			.addAction(action(R.drawable.ic_close, R.string.stop, ACTION_STOP))
		launchIntent()?.let { builder.setContentIntent(it) }
		return builder.build()
	}

	private fun launchIntent() = packageManager.getLaunchIntentForPackage(packageName)?.let {
		PendingIntentCompat.getActivity(this, 0, it, 0, false)
	}

	private fun action(@DrawableRes icon: Int, title: Int, action: String) = Notification.Action.Builder(
		Icon.createWithResource(this, icon),
		getString(title),
		actionIntent(action),
	).build()

	private fun actionIntent(action: String) = PendingIntentCompat.getService(
		this,
		action.hashCode(),
		Intent(this, ReaderTtsService::class.java).setAction(action),
		0,
		false,
	)

	/** Lock screen, quick settings player, Bluetooth and headset buttons all land here. */
	private inner class SessionCallback : MediaSession.Callback() {
		override fun onPlay() = tts.play()
		override fun onPause() = tts.pause()
		override fun onSkipToNext() = tts.skip(1)
		override fun onSkipToPrevious() = tts.skip(-1)
		override fun onStop() = stopPlayback()
		override fun onCustomAction(action: String, extras: android.os.Bundle?) {
			if (action == ACTION_STOP) stopPlayback()
		}
	}

	companion object {

		private const val CHANNEL_ID = "reader_tts"
		private const val NOTIFICATION_ID = 42
		private const val COVER_SIZE = 512
		private const val EXTRA_MANGA = "manga"
		private const val ACTION_TOGGLE = "toggle"
		private const val ACTION_NEXT = "next"
		private const val ACTION_PREVIOUS = "previous"
		private const val ACTION_STOP = "stop"

		fun start(context: Context, manga: Manga?) {
			val intent = Intent(context, ReaderTtsService::class.java)
			manga?.let { intent.putExtra(EXTRA_MANGA, ParcelableManga(it, withDescription = false)) }
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
