package org.koitharu.kotatsu.main.ui.protect

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
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
		if (!ensureProtectionAvailability(activity)) {
			return
		}
		if (!settings.isAppProtectionEnabled) {
			isUnlocked = true
			return
		}
		if (!isUnlocked && activity !is CrashReportDialog) {
			showProtectScreen(activity)
		}
	}

	override fun onActivityStarted(activity: Activity) {
		if (!ensureProtectionAvailability(activity)) {
			return
		}
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
		if (!ensureProtectionAvailability(activity)) {
			return
		}
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

	private fun ensureProtectionAvailability(activity: Activity): Boolean {
		if (!settings.isAppProtectionEnabled) {
			return true
		}
		val canAuthenticate = BiometricManager.from(activity)
			.canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS
		if (canAuthenticate) {
			return true
		}
		settings.isAppProtectionEnabled = false
		isUnlocked = true
		startedActivities = 0
		lastBackgroundAt = 0L
		return false
	}
}
