package org.koitharu.kotatsu.core.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.view.WindowCompat
import androidx.core.view.doOnPreDraw
import org.koitharu.kotatsu.core.util.ext.applyNightMode

/**
 * Hides the restart that a light/dark switch causes. Android rebuilds every screen for the new
 * mode and the app has no window on screen until the rebuilt one draws, which shows as a black
 * flash. This see-through screen survives the switch (it handles uiMode itself), holds a picture
 * of the old look on top while the screen underneath restarts, then fades away.
 */
class ThemeSwitchCoverActivity : Activity() {

	private var image: ImageView? = null
	private var isRevealed = false

	private val lifecycleCallbacks = object : DefaultActivityLifecycleCallbacks {
		override fun onActivityStarted(activity: Activity) {
			if (activity !== this@ThemeSwitchCoverActivity) {
				// The restarted screen underneath: fade once it has something to show.
				activity.window.decorView.doOnPreDraw { image?.postDelayed(::reveal, REVEAL_DELAY_MS) }
			}
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val shot = pendingShot
		pendingShot = null
		if (shot == null) { // restored without a picture, nothing to cover
			finish()
			return
		}
		WindowCompat.setDecorFitsSystemWindows(window, false)
		window.attributes = window.attributes.apply {
			layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
		}
		val view = ImageView(this).apply {
			scaleType = ImageView.ScaleType.FIT_XY
			setImageBitmap(shot)
		}
		image = view
		setContentView(view)
		application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
		val mode = intent.getIntExtra(EXTRA_MODE, 0)
		// Switch only once the picture is on screen.
		view.doOnPreDraw { view.post { applyNightMode(mode) } }
		view.postDelayed(::reveal, TIMEOUT_MS)
	}

	override fun onDestroy() {
		application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
		super.onDestroy()
	}

	@Deprecated("Deprecated in Java")
	override fun onBackPressed() = Unit

	private fun reveal() {
		val view = image ?: return
		if (isRevealed) return
		isRevealed = true
		view.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
			finish()
			@Suppress("DEPRECATION")
			overridePendingTransition(0, 0)
		}.start()
	}

	companion object {

		private const val EXTRA_MODE = "mode"
		private const val REVEAL_DELAY_MS = 60L
		private const val FADE_MS = 220L
		private const val TIMEOUT_MS = 1500L

		private var pendingShot: Bitmap? = null

		/** Applies night [mode] (an AppCompatDelegate constant) from [activity], covering the restart. */
		fun switchNightMode(activity: Activity, mode: Int) {
			val window = activity.window
			val decor = window.decorView
			// A protected window can't be copied; switch the plain way.
			val isSecure = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
			if (isSecure || decor.width == 0 || decor.height == 0) {
				activity.applyNightMode(mode)
				return
			}
			val shot = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
			PixelCopy.request(window, shot, { result ->
				if (result == PixelCopy.SUCCESS && !activity.isFinishing && !activity.isDestroyed) {
					pendingShot = shot
					activity.startActivity(
						Intent(activity, ThemeSwitchCoverActivity::class.java).putExtra(EXTRA_MODE, mode),
					)
					@Suppress("DEPRECATION")
					activity.overridePendingTransition(0, 0)
				} else {
					activity.applyNightMode(mode)
				}
			}, Handler(Looper.getMainLooper()))
		}
	}
}
