package org.koitharu.kotatsu.core.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import org.koitharu.kotatsu.core.prefs.AppSettings
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.viewbinding.ViewBinding
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.util.ActionModeDelegate
import org.koitharu.kotatsu.core.ui.util.ActivityRecreationHandle
import org.koitharu.kotatsu.core.ui.util.applyTonalTopBarStyle
import org.koitharu.kotatsu.core.util.ext.adjustPopupMenuIcons
import org.koitharu.kotatsu.core.util.ext.isWebViewUnavailable
import org.koitharu.kotatsu.core.util.ext.setOptionalIconsVisibleCompat
import org.koitharu.kotatsu.main.ui.protect.ScreenshotPolicyHelper

abstract class BaseActivity<B : ViewBinding> :
	AppCompatActivity(),
	OnApplyWindowInsetsListener,
	ScreenshotPolicyHelper.ContentContainer {

	private var isAmoledTheme = false

	lateinit var viewBinding: B
		private set

	protected lateinit var exceptionResolver: ExceptionResolver
		private set

	@JvmField
	val actionModeDelegate = ActionModeDelegate()

	protected lateinit var entryPoint: BaseActivityEntryPoint

	private val statusBarPrefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		if (key == AppSettings.KEY_HIDE_STATUS_BAR) {
			applyStatusBarVisibility(entryPoint.settings.isStatusBarHidden)
			ViewCompat.requestApplyInsets(window.decorView)
		}
	}

	override fun attachBaseContext(newBase: Context) {
		entryPoint = EntryPointAccessors.fromApplication<BaseActivityEntryPoint>(newBase.applicationContext)
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
			AppCompatDelegate.setApplicationLocales(entryPoint.settings.appLocales)
		}
		super.attachBaseContext(newBase)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		val settings = entryPoint.settings
		isAmoledTheme = settings.isAmoledTheme
		setTheme(settings.colorScheme.styleResId)
		if (isAmoledTheme) {
			setTheme(R.style.ThemeOverlay_Kotatsu_Amoled)
		}
		putDataToExtras(intent)
		exceptionResolver = entryPoint.exceptionResolverFactory.create(this)
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		maybePlayRecreateFadeIn()
		settings.subscribe(statusBarPrefListener)
		applyStatusBarVisibility(settings.isStatusBarHidden)
	}

	override fun onDestroy() {
		if (::entryPoint.isInitialized) {
			entryPoint.settings.unsubscribe(statusBarPrefListener)
		}
		super.onDestroy()
	}

	override fun onWindowFocusChanged(hasFocus: Boolean) {
		super.onWindowFocusChanged(hasFocus)
		if (hasFocus) {
			applyStatusBarVisibility(entryPoint.settings.isStatusBarHidden)
		}
	}

	private fun applyStatusBarVisibility(hidden: Boolean) {
		if (this is BaseFullscreenActivity<*>) {
			return
		}
		val controller = WindowCompat.getInsetsController(window, window.decorView)
		if (hidden) {
			controller.hide(WindowInsetsCompat.Type.statusBars())
			controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
		} else {
			controller.show(WindowInsetsCompat.Type.statusBars())
		}
	}

	/**
	 * When this activity is being recreated in place by a theme/colour-scheme change there is no
	 * enter transition, so the freshly-inflated toolbar visibly settles (the back button and title
	 * reflow into place). Fade the whole window in briefly to mask that one-off jank. Normal
	 * navigation and configuration changes (rotation) don't set the flag, so they're unaffected.
	 */
	private fun maybePlayRecreateFadeIn() {
		if (!ActivityRecreationHandle.isAnimatedRecreateInProgress) {
			return
		}
		val decor = window.decorView
		decor.alpha = 0f
		decor.animate()
			.alpha(1f)
			.setDuration(RECREATE_FADE_DURATION_MS)
			.withEndAction { decor.alpha = 1f }
			.start()
	}

	override fun onPostCreate(savedInstanceState: Bundle?) {
		super.onPostCreate(savedInstanceState)
		onBackPressedDispatcher.addCallback(actionModeDelegate)
	}

	override fun onNewIntent(intent: Intent) {
		putDataToExtras(intent)
		super.onNewIntent(intent)
	}

	@Deprecated("Use ViewBinding", level = DeprecationLevel.ERROR)
	override fun setContentView(layoutResID: Int) = throw UnsupportedOperationException()

	@Deprecated("Use ViewBinding", level = DeprecationLevel.ERROR)
	override fun setContentView(view: View?) = throw UnsupportedOperationException()

	protected fun setContentView(binding: B) {
		this.viewBinding = binding
		super.setContentView(binding.root)
		ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
			val modifiedInsets = if (entryPoint.settings.isStatusBarHidden) {
				val statusBarInsets = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())
				val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
				val newSystemBars = androidx.core.graphics.Insets.of(
					systemBarsInsets.left,
					maxOf(systemBarsInsets.top, statusBarInsets.top),
					systemBarsInsets.right,
					systemBarsInsets.bottom
				)
				WindowInsetsCompat.Builder(insets)
					.setInsets(WindowInsetsCompat.Type.systemBars(), newSystemBars)
					.build()
			} else {
				insets
			}
			onApplyWindowInsets(v, modifiedInsets)
		}
		val toolbar = (binding.root.findViewById<View>(R.id.toolbar) as? Toolbar)
		toolbar?.let {
			setSupportActionBar(it)
			it.applyTonalTopBarStyle()
		}
	}

	protected fun setDisplayHomeAsUp(isEnabled: Boolean, showUpAsClose: Boolean) {
		supportActionBar?.run {
			setDisplayHomeAsUpEnabled(isEnabled)
			if (showUpAsClose) {
				setHomeAsUpIndicator(R.drawable.ic_close)
			}
		}
		(findViewById<View>(R.id.toolbar) as? Toolbar)?.applyTonalTopBarStyle()
	}

	override fun onPostResume() {
		super.onPostResume()
		(findViewById<View>(R.id.toolbar) as? Toolbar)?.applyTonalTopBarStyle()
	}

	override fun onSupportNavigateUp(): Boolean {
		val fm = supportFragmentManager
		if (fm.isStateSaved) {
			return false
		}
		if (fm.backStackEntryCount > 0) {
			fm.popBackStack()
		} else {
			dispatchNavigateUp()
		}
		return true
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
		if (BuildConfig.DEBUG) {
			if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
				ActivityCompat.recreate(this)
				return true
			} else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
				throw RuntimeException("Test crash")
			}
		}
		return super.onKeyDown(keyCode, event)
	}

	override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean {
		menu.setOptionalIconsVisibleCompat(true)
		menu.adjustPopupMenuIcons(
			resources = resources,
			shouldSkip = { it.requiresActionButtonCompat() },
			iconSizeProvider = {
				if (it.itemId == R.id.action_manage && it.title == getString(R.string.extension_management)) {
					resources.getDimensionPixelSize(R.dimen.explore_extension_menu_icon_size)
				} else {
					resources.getDimensionPixelSize(R.dimen.menu_popup_icon_size)
				}
			},
		)
		return super.onPreparePanel(featureId, view, menu)
	}

	private fun MenuItem.requiresActionButtonCompat(): Boolean {
		return runCatching {
			javaClass.getMethod("requiresActionButton").invoke(this) as? Boolean
		}.getOrNull() == true
	}



	@CallSuper
	override fun onSupportActionModeStarted(mode: ActionMode) {
		super.onSupportActionModeStarted(mode)
		actionModeDelegate.onSupportActionModeStarted(mode, window)
	}

	@CallSuper
	override fun onSupportActionModeFinished(mode: ActionMode) {
		super.onSupportActionModeFinished(mode)
		actionModeDelegate.onSupportActionModeFinished(mode, window)
	}

	protected open fun dispatchNavigateUp() {
		val upIntent = parentActivityIntent
		if (upIntent != null) {
			if (!navigateUpTo(upIntent)) {
				startActivity(upIntent)
			}
		} else {
			finishAfterTransition()
		}
	}

	override fun isNsfwContent(): Flow<Boolean> = flowOf(false)

	private fun putDataToExtras(intent: Intent?) {
		intent?.putExtra(AppRouter.KEY_DATA, intent.data)
	}

	protected fun setContentViewWebViewSafe(viewBindingProducer: () -> B): Boolean {
		return try {
			setContentView(viewBindingProducer())
			true
		} catch (e: Exception) {
			if (e.isWebViewUnavailable()) {
				Toast.makeText(this, R.string.web_view_unavailable, Toast.LENGTH_LONG).show()
				finishAfterTransition()
				false
			} else {
				throw e
			}
		}
	}

	protected fun hasViewBinding() = ::viewBinding.isInitialized

	private companion object {

		private const val RECREATE_FADE_DURATION_MS = 220L
	}
}
