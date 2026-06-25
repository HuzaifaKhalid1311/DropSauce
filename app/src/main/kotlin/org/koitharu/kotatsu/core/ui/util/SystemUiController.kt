package org.koitharu.kotatsu.core.ui.util

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.annotation.RequiresApi
import org.koitharu.kotatsu.core.prefs.AppSettings

sealed class SystemUiController(
	protected val window: Window,
	protected val settings: AppSettings,
) {

	abstract fun setSystemUiVisible(value: Boolean)

	@RequiresApi(Build.VERSION_CODES.S)
	private class Api30Impl(window: Window, settings: AppSettings) : SystemUiController(window, settings) {

		private val insetsController = checkNotNull(window.decorView.windowInsetsController)

		override fun setSystemUiVisible(value: Boolean) {
			if (value) {
				if (settings.isStatusBarHidden) {
					insetsController.show(WindowInsets.Type.navigationBars())
					insetsController.hide(WindowInsets.Type.statusBars())
				} else {
					insetsController.show(WindowInsets.Type.systemBars())
				}
				insetsController.systemBarsBehavior = WindowInsetsController.BEHAVIOR_DEFAULT
			} else {
				insetsController.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
				insetsController.hide(WindowInsets.Type.systemBars())
			}
		}
	}

	@Suppress("DEPRECATION")
	private class LegacyImpl(window: Window, settings: AppSettings) : SystemUiController(window, settings) {

		override fun setSystemUiVisible(value: Boolean) {
			val flags = window.decorView.systemUiVisibility
			window.decorView.systemUiVisibility = if (value) {
				var targetFlags = (flags and LEGACY_FLAGS_HIDDEN.inv()) or LEGACY_FLAGS_VISIBLE
				if (settings.isStatusBarHidden) {
					targetFlags = targetFlags or View.SYSTEM_UI_FLAG_FULLSCREEN
				}
				targetFlags
			} else {
				(flags and LEGACY_FLAGS_VISIBLE.inv()) or LEGACY_FLAGS_HIDDEN
			}
		}
	}

	companion object {

		@Suppress("DEPRECATION")
		private const val LEGACY_FLAGS_VISIBLE = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
			View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
			View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

		@Suppress("DEPRECATION")
		private const val LEGACY_FLAGS_HIDDEN = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
			View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
			View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
			View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
			View.SYSTEM_UI_FLAG_FULLSCREEN or
			View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

		operator fun invoke(window: Window, settings: AppSettings): SystemUiController =
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				Api30Impl(window, settings)
			} else {
				LegacyImpl(window, settings)
			}
	}
}
