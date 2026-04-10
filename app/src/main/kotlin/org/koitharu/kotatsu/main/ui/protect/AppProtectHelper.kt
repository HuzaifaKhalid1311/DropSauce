package org.koitharu.kotatsu.main.ui.protect

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import org.acra.dialog.CrashReportDialog
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.DefaultActivityLifecycleCallbacks
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppProtectHelper @Inject constructor(private val settings: AppSettings) :
	DefaultActivityLifecycleCallbacks {

	private var isUnlocked = !settings.isAppProtectionEnabled
	private var startedActivities = 0
	private var lastBackgroundAt = 0L

	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
		if (!settings.isAppProtectionEnabled) {
			isUnlocked = true
			return
		}
		if (!isUnlocked && activity !is CrashReportDialog) {
			showProtectScreen(activity)
		}
	}

	override fun onActivityStarted(activity: Activity) {
		if (activity is ProtectActivity || !settings.isAppProtectionEnabled) {
			return
		}
		startedActivities++
		if (startedActivities == 1 && lastBackgroundAt > 0L) {
			val elapsed = SystemClock.elapsedRealtime() - lastBackgroundAt
			if (elapsed >= settings.appProtectionTimeoutMillis) {
				isUnlocked = false
			}
		}
		if (!isUnlocked && activity !is CrashReportDialog) {
			showProtectScreen(activity)
		}
	}

	override fun onActivityStopped(activity: Activity) {
		if (activity is ProtectActivity || !settings.isAppProtectionEnabled) {
			return
		}
		startedActivities = (startedActivities - 1).coerceAtLeast(0)
		if (startedActivities == 0) {
			lastBackgroundAt = SystemClock.elapsedRealtime()
			if (settings.appProtectionTimeoutMillis == 0L) {
				isUnlocked = false
			}
		}
	}

	override fun onActivityDestroyed(activity: Activity) {
		if (activity !is ProtectActivity && activity.isFinishing && activity.isTaskRoot && settings.isAppProtectionEnabled) {
			restoreLock()
		}
	}

	fun unlock() {
		isUnlocked = true
		lastBackgroundAt = 0L
	}

	private fun restoreLock() {
		isUnlocked = !settings.isAppProtectionEnabled
	}

	private fun showProtectScreen(activity: Activity) {
		if (activity is ProtectActivity || activity.isFinishing || activity.isDestroyed) {
			return
		}
		val intent = Intent(activity, ProtectActivity::class.java).apply {
			addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
		}
		activity.startActivity(intent)
		@Suppress("DEPRECATION")
		activity.overridePendingTransition(0, 0)
	}
}
