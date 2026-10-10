package org.koitharu.kotatsu.tracker.work

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.VISIBILITY_PRIVATE
import androidx.core.app.NotificationCompat.VISIBILITY_SECRET
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import coil3.ImageLoader
import coil3.request.ImageRequest
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.model.getLocalizedTitle
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.checkNotificationPermission
import org.koitharu.kotatsu.core.util.ext.getNotificationIconSize
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.goAsync
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.toBitmapOrNull
import org.koitharu.kotatsu.tracker.domain.TrackingRepository
import org.koitharu.kotatsu.tracker.domain.model.MangaUpdates
import javax.inject.Inject

class TrackerNotificationHelper @Inject constructor(
	@LocalizedAppContext private val applicationContext: Context,
	private val settings: AppSettings,
	private val coil: ImageLoader,
) {

	private val manager by lazy { NotificationManagerCompat.from(applicationContext) }

	fun getAreNotificationsEnabled(): Boolean {
		if (!manager.areNotificationsEnabled()) {
			return false
		}
		val channel = manager.getNotificationChannel(CHANNEL_ID)
		return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
	}

	/**
	 * Posts one manga's new chapters into the bundle the moment they're found, the way a chat app groups
	 * messages: the summary is re-posted to count it in, then the manga's own silent child goes under it.
	 * Only [alert] makes a sound, so a check rings once. Children still in the tray from earlier checks
	 * stay in the bundle; a manga updated again replaces its own child.
	 *
	 * @return whether anything was posted
	 */
	suspend fun showNewChaptersNotification(update: MangaUpdates.Success, alert: Boolean): Boolean {
		if (update.newChapters.isEmpty() || isHidden(update) || !applicationContext.checkNotificationPermission(CHANNEL_ID)) {
			return false
		}
		postNewChaptersNotification(update, alert)
		return true
	}

	/** Removes one manga's notification and silently re-syncs the summary with the children left. */
	fun cancelNotification(id: Int) {
		manager.cancel(TAG, id)
		// The cancelled child can still be listed as active for a moment, so drop it explicitly
		val items = getActiveChildren().filterNot { it.id == id }
		if (items.isEmpty()) {
			manager.cancel(TAG, GROUP_NOTIFICATION_ID)
		} else if (applicationContext.checkNotificationPermission(CHANNEL_ID)) {
			postGroupNotification(items, isSilent = true)
		}
	}

	@RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
	private fun postGroupNotification(items: List<SummaryItem>, isSilent: Boolean) {
		manager.notify(TAG, GROUP_NOTIFICATION_ID, createGroupNotification(items, isSilent))
	}

	@RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
	private suspend fun postNewChaptersNotification(update: MangaUpdates.Success, alert: Boolean) {
		// Summary from older app versions, posted under the worker's tag
		manager.cancel(LEGACY_GROUP_TAG, GROUP_NOTIFICATION_ID)
		// Built first (it loads the cover) so the summary and its new child land back to back
		val child = createNotification(update)
		val item = SummaryItem(
			id = update.notificationId(),
			title = update.manga.title,
			newChapters = update.newChapters.size,
			isNsfw = update.manga.isNsfw(),
		)
		postGroupNotification(listOf(item) + getActiveChildren().filterNot { it.id == item.id }, isSilent = !alert)
		manager.notify(TAG, item.id, child)
	}

	private fun isHidden(updates: MangaUpdates.Success): Boolean {
		return updates.manga.isNsfw() && (settings.isTrackerNsfwDisabled || settings.isNsfwContentDisabled)
	}

	private suspend fun createNotification(updates: MangaUpdates.Success): Notification {
		val manga = updates.manga
		val newChapters = updates.newChapters
		val id = updates.notificationId()
		val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
		val summary = applicationContext.resources.getQuantityStringSafe(
			R.plurals.new_chapters,
			newChapters.size,
			newChapters.size,
		)
		with(builder) {
			setContentText(summary)
			setContentTitle(manga.title)
			setNumber(newChapters.size)
			setLargeIcon(
				coil.execute(
					ImageRequest.Builder(applicationContext)
						.data(manga.coverUrl)
						.size(applicationContext.resources.getNotificationIconSize())
						.mangaSourceExtra(manga.source)
						.build(),
				).toBitmapOrNull(),
			)
			setSmallIcon(R.drawable.read_notification)
			setGroup(GROUP_NEW_CHAPTERS)
			setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
			val style = NotificationCompat.InboxStyle(this)
			for (chapter in newChapters) {
				style.addLine(chapter.getLocalizedTitle(applicationContext.resources))
			}
			style.setSummaryText(manga.title)
			style.setBigContentTitle(summary)
			setStyle(style)
			val intent = AppRouter.detailsIntent(applicationContext, manga)
			setContentIntent(
				PendingIntentCompat.getActivity(
					applicationContext,
					id,
					intent,
					PendingIntent.FLAG_UPDATE_CURRENT,
					false,
				),
			)
			addAction(
				R.drawable.ic_playlist_add_check,
				applicationContext.getString(R.string.mark_as_read),
				PendingIntentCompat.getBroadcast(
					applicationContext,
					id,
					Intent(applicationContext, MarkAsReadReceiver::class.java)
						.putExtra(AppRouter.KEY_ID, manga.id)
						.putExtra(EXTRA_NOTIFICATION_ID, id),
					PendingIntent.FLAG_UPDATE_CURRENT,
					false,
				),
			)
			setVisibility(if (manga.isNsfw()) VISIBILITY_SECRET else VISIBILITY_PRIVATE)
			setShortcutId(manga.id.toString())
			applyCommonSettings(this)
		}
		return builder.build()
	}

	private fun createGroupNotification(items: List<SummaryItem>, isSilent: Boolean): Notification {
		val newChaptersCount = items.sumOf { it.newChapters }
		val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
		with(builder) {
			val title = applicationContext.resources.getQuantityStringSafe(
				R.plurals.manga_updated,
				items.size,
				items.size,
			)
			setContentTitle(title)
			setContentText(items.joinToString { it.title })
			setSmallIcon(R.drawable.read_notification)
			val style = NotificationCompat.InboxStyle(this)
			for (item in items) {
				style.addLine(
					applicationContext.getString(R.string.new_chapters_pattern, item.title, item.newChapters),
				)
			}
			style.setBigContentTitle(title)
			setStyle(style)
			setNumber(newChaptersCount)
			setGroup(GROUP_NEW_CHAPTERS)
			setGroupSummary(true)
			setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
			setSilent(isSilent)
			setVisibility(if (items.any { it.isNsfw }) VISIBILITY_SECRET else VISIBILITY_PRIVATE)
			val intent = AppRouter.mangaUpdatesIntent(applicationContext)
			setContentIntent(
				PendingIntentCompat.getActivity(
					applicationContext,
					GROUP_NOTIFICATION_ID,
					intent,
					PendingIntent.FLAG_UPDATE_CURRENT,
					false,
				),
			)
			applyCommonSettings(this)
		}
		return builder.build()
	}

	private fun getActiveChildren(): List<SummaryItem> = manager.activeNotifications.mapNotNull { sbn ->
		val notification = sbn.notification
		if (sbn.tag != TAG || sbn.id == GROUP_NOTIFICATION_ID || notification.group != GROUP_NEW_CHAPTERS) {
			return@mapNotNull null
		}
		val title = notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE) ?: return@mapNotNull null
		SummaryItem(
			id = sbn.id,
			title = title.toString(),
			newChapters = notification.number,
			isNsfw = notification.visibility == VISIBILITY_SECRET,
		)
	}

	private fun MangaUpdates.Success.notificationId() = manga.url.hashCode()

	fun createFailedChecksNotification(failedCount: Int): Notification? {
		if (failedCount <= 0 ||
			!settings.isTrackerFailureNotificationEnabled ||
			!applicationContext.checkNotificationPermission(CHANNEL_ID)
		) {
			return null
		}
		val title = applicationContext.resources.getQuantityStringSafe(
			R.plurals.manga_failed_to_fetch_new_chapters,
			failedCount,
			failedCount,
		)
		val text = applicationContext.getString(R.string.tap_to_review_affected_manga)
		val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
		with(builder) {
			setContentTitle(title)
			setContentText(text)
			setStyle(NotificationCompat.BigTextStyle().bigText(text))
			setSmallIcon(R.drawable.general_notification)
			setNumber(failedCount)
			setContentIntent(
				PendingIntentCompat.getActivity(
					applicationContext,
					FAILED_CHECKS_NOTIFICATION_ID,
					AppRouter.trackerDebugIntent(applicationContext),
					PendingIntent.FLAG_UPDATE_CURRENT,
					false,
				),
			)
			setVisibility(VISIBILITY_PRIVATE)
			applyCommonSettings(this)
			setCategory(NotificationCompat.CATEGORY_ERROR)
		}
		return builder.build()
	}

	fun updateChannels() {
		manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
		manager.deleteNotificationChannel(LEGACY_CHANNEL_ID_HISTORY)
		manager.deleteNotificationChannelGroup(LEGACY_CHANNELS_GROUP_ID)

		val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
			.setName(applicationContext.getString(R.string.new_chapters))
			.setDescription(applicationContext.getString(R.string.show_notification_new_chapters_on))
			.setShowBadge(true)
			.setLightColor(ContextCompat.getColor(applicationContext, R.color.blue_primary))
			.build()
		manager.createNotificationChannel(channel)
	}

	private fun applyCommonSettings(builder: NotificationCompat.Builder) {
		builder.setAutoCancel(true)
		builder.setCategory(NotificationCompat.CATEGORY_SOCIAL)
		builder.priority = NotificationCompat.PRIORITY_DEFAULT
	}

	private class SummaryItem(
		val id: Int,
		val title: String,
		val newChapters: Int,
		val isNsfw: Boolean,
	)

	/** "Mark as read" on a manga's new-chapters notification: same as marking it read in the Updates feed. */
	@AndroidEntryPoint
	class MarkAsReadReceiver : BroadcastReceiver() {

		@Inject
		lateinit var trackingRepository: TrackingRepository

		@Inject
		lateinit var notificationHelper: TrackerNotificationHelper

		override fun onReceive(context: Context?, intent: Intent?) {
			val mangaId = intent?.getLongExtra(AppRouter.KEY_ID, 0L) ?: return
			val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, GROUP_NOTIFICATION_ID)
			if (mangaId == 0L || notificationId == GROUP_NOTIFICATION_ID) {
				return
			}
			notificationHelper.cancelNotification(notificationId)
			goAsync {
				trackingRepository.clearUpdates(setOf(mangaId))
			}
		}
	}

	companion object {

		const val CHANNEL_ID = "tracker_chapters"
		const val GROUP_NOTIFICATION_ID = 0
		const val FAILED_CHECKS_NOTIFICATION_ID = 1
		const val GROUP_NEW_CHAPTERS = "org.koitharu.kotatsu.NEW_CHAPTERS"
		const val TAG = "tracker"
		const val TAG_FAILED_CHECKS = "tracker_failed_checks"

		private const val EXTRA_NOTIFICATION_ID = "notification_id"
		private const val LEGACY_GROUP_TAG = "tracking"
		private const val LEGACY_CHANNELS_GROUP_ID = "trackers"
		private const val LEGACY_CHANNEL_ID_HISTORY = "track_history"
		private const val LEGACY_CHANNEL_ID = "tracking"
	}
}
