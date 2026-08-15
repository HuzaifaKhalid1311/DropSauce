package org.koitharu.kotatsu.browser.cloudflare

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.yield
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.browser.BaseBrowserActivity
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaAutoResolveCoordinator
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaHandler
import org.koitharu.kotatsu.core.model.MangaSource as ResolveMangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@AndroidEntryPoint
class CloudFlareActivity : BaseBrowserActivity() {

	private var pendingResult = RESULT_CANCELED
	private val isHidden: Boolean by lazy { intent?.getBooleanExtra(EXTRA_HIDDEN, false) == true }
	private val isAutoResolve: Boolean by lazy { intent?.getBooleanExtra(EXTRA_AUTO_RESOLVE, false) == true }
	private var resultNotified = false
	private var hiddenTimeoutJob: Job? = null
	private var clearanceVerificationJob: Job? = null

	/** Clearance cookie a silent check already found insufficient, so it is not re-tested on every page load. */
	private var rejectedClearance: String? = null

	@Inject
	lateinit var cookieJar: MutableCookieJar

	@Inject
	@MangaHttpClient
	lateinit var okHttpClient: OkHttpClient

	@Inject
	@BaseHttpClient
	lateinit var baseHttpClient: OkHttpClient

	@Inject
	lateinit var captchaHandler: CaptchaHandler

	@Inject
	lateinit var captchaAutoResolveCoordinator: CaptchaAutoResolveCoordinator

	private lateinit var cfClient: CloudFlareClient

	override fun onCreate2(savedInstanceState: Bundle?, source: MangaSource, repository: MangaRepository?) {
		if (isHidden) {
			supportActionBar?.hide()
			viewBinding.appbar.isVisible = false
			viewBinding.progressBar.isVisible = false
			viewBinding.root.alpha = 0.01f
			window.addFlags(
				WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
					WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
			)
			hiddenTimeoutJob = lifecycleScope.launch {
				delay(HIDDEN_TIMEOUT_MS)
				viewBinding.webView.stopLoading()
				finishAfterTransition()
			}
		} else {
			setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		}
		val url = intent?.dataString
		if (url.isNullOrEmpty()) {
			finishAfterTransition()
			return
		}
		lifecycleScope.launch {
			try {
				proxyProvider.applyWebViewConfig()
			} catch (e: Exception) {
				showSnackbar(e.getDisplayMessage(resources))
			}
			cfClient = if (shouldUseInterception(source)) {
				CloudFlareInterceptClient(cookieJar, this@CloudFlareActivity, adBlock, url, baseHttpClient)
			} else {
				CloudFlareClient(cookieJar, this@CloudFlareActivity, adBlock, url)
			}
			viewBinding.webView.webViewClient = cfClient
			if (savedInstanceState == null) {
				onTitleChanged(getString(R.string.loading_), url)
				viewBinding.webView.loadUrl(url)
			}
		}
	}

	override fun onCreateOptionsMenu(menu: Menu?): Boolean {
		if (isHidden) {
			return false
		}
		menuInflater.inflate(R.menu.opt_captcha, menu)
		return super.onCreateOptionsMenu(menu)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		android.R.id.home -> {
			viewBinding.webView.stopLoading()
			finishAfterTransition()
			true
		}

		R.id.action_retry -> {
			restartCheck()
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	override fun finish() {
		hiddenTimeoutJob?.cancel()
		setResult(pendingResult)
		if (isAutoResolve && !resultNotified) {
			resultNotified = true
			intent?.getStringExtra(AppRouter.KEY_SOURCE)?.let { sourceName ->
				captchaAutoResolveCoordinator.notifyResolveResult(
					ResolveMangaSource(sourceName),
					pendingResult == RESULT_OK,
				)
			}
		}
		super.finish()
	}

	override fun onLoadingStateChanged(isLoading: Boolean) = Unit

	fun onPageLoaded() {
		if (!isHidden) {
			viewBinding.progressBar.isInvisible = true
		}
		// CloudFlareClient can only detect a clearance cookie that *changed*, so a still-valid
		// cookie we already had reads as a failure there. Whenever a clearance cookie exists at
		// all, hand over to onCheckPassed - it settles the question with a real request instead
		// of guessing from the cookie. Silent, and only once per distinct cookie: this fires on
		// every page load, including the challenge pages the user is still working through, and
		// neither a "captcha required" toast nor a repeated request belongs there.
		val clearance = intent?.dataString?.let { CloudFlareHelper.getClearanceCookie(cookieJar, it) }
		if (clearance != null && clearance != rejectedClearance) {
			onCheckPassed(silentFor = clearance)
		}
	}

	fun onLoopDetected() {
		// Deliberately no restartCheck() in the hidden/auto pass: it deletes the site's Cloudflare
		// cookies, and this activity is FLAG_NOT_TOUCHABLE with alpha 0.01, so an interactive
		// challenge can never be solved here anyway. It used to throw away a clearance the user had
		// just earned by hand, putting them back on the challenge page - an endless loop of solving
		// and being un-solved. Let the WebView keep going until HIDDEN_TIMEOUT_MS instead, which
		// leaves a slow but genuine JS challenge exactly as much time as it had before.
		cfClient.reset()
		if (!isHidden && !isAutoResolve) {
			android.util.Log.w(TAG, "Cloudflare loop detected; keeping manual browser open for user action")
		}
	}

	/**
	 * [silentFor] is the exact clearance cookie the silent probe is testing; null means a real cookie
	 * change reported by [CloudFlareClient], which is allowed to complain out loud. It has to be
	 * carried in rather than re-read on failure: the WebView and the verify request itself both write
	 * to the cookie jar, so by the time the verdict lands the jar may already hold a *newer* cookie -
	 * blacklisting that one would ignore a clearance nobody ever tested.
	 */
	fun onCheckPassed(silentFor: String? = null) {
		if (clearanceVerificationJob?.isActive == true) {
			return
		}
		clearanceVerificationJob = lifecycleScope.launch {
			// Verify against the originally blocked url, not the rewritten challenge (root) url:
			// the root may be unprotected while the actual resource is still blocked
			val url = intent?.getStringExtra(EXTRA_ORIGINAL_URL)?.takeIf { it.isNotBlank() } ?: intent?.dataString
			if (url.isNullOrBlank() || !verifyClearance(url)) {
				if (silentFor != null) {
					rejectedClearance = silentFor
				} else if (!isHidden) {
					showSnackbar(getString(R.string.captcha_required_message))
				}
				return@launch
			}
			pendingResult = RESULT_OK
			val source = intent?.getStringExtra(AppRouter.KEY_SOURCE)
			if (source != null) {
				runCatchingCancellable {
					captchaHandler.discard(ResolveMangaSource(source))
				}.onFailure {
					it.printStackTraceDebug()
				}
			}
			finishAfterTransition()
		}
	}

	private fun showSnackbar(message: CharSequence) {
		if (isFinishing || isDestroyed || !viewBinding.root.isAttachedToWindow) {
			return
		}
		Snackbar.make(viewBinding.root, message, Snackbar.LENGTH_LONG).show()
	}

	private suspend fun verifyClearance(url: String): Boolean = runCatchingCancellable {
		runInterruptible(Dispatchers.IO) {
			val request = Request.Builder()
				.url(url)
				.get()
				.apply {
					intent?.getStringExtra(AppRouter.KEY_USER_AGENT)?.takeIf { it.isNotBlank() }?.let {
						header(CommonHeaders.USER_AGENT, it)
					}
					intent?.getStringExtra(AppRouter.KEY_SOURCE)?.takeIf { it.isNotBlank() }?.let {
						header(CommonHeaders.MANGA_SOURCE, it)
					}
				}
				.build()
			// MangaHttpClient's CloudFlareInterceptor throws if the challenge is still active
			okHttpClient.newCall(request).execute().use { response ->
				android.util.Log.i(TAG, "verify clearance: url=$url status=${response.code}")
				response.isSuccessful
			}
		}
	}.onFailure { error ->
		android.util.Log.w(TAG, "verify clearance failed: url=$url", error)
	}.getOrDefault(false)

	override fun onTitleChanged(title: CharSequence, subtitle: CharSequence?) {
		setTitle(title)
		supportActionBar?.subtitle = subtitle?.toString()?.toHttpUrlOrNull()?.host.ifNullOrEmpty { subtitle }
	}

	private fun restartCheck() {
		lifecycleScope.launch {
			viewBinding.webView.stopLoading()
			yield()
			cfClient.reset()
			val targetUrl = intent?.dataString?.toHttpUrlOrNull()
			if (targetUrl != null) {
				clearCfCookies(targetUrl)
				viewBinding.webView.loadUrl(targetUrl.toString())
			}
		}
	}

	private suspend fun clearCfCookies(url: HttpUrl) = runInterruptible(Dispatchers.Default) {
		cookieJar.removeCookies(url) { cookie ->
			CloudFlareHelper.isCloudFlareCookie(cookie.name)
		}
	}

	private fun shouldUseInterception(source: MangaSource): Boolean {
		return SourceSettings(this, ResolveMangaSource(source.name)).isInterceptCloudflareEnabled
	}

	class Contract : ActivityResultContract<CloudFlareProtectedException, Boolean>() {
		override fun createIntent(context: Context, input: CloudFlareProtectedException): Intent {
			return AppRouter.cloudFlareResolveIntent(context, input)
		}

		override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
			return resultCode == RESULT_OK
		}
	}

	companion object {

		const val TAG = "CloudFlareActivity"
		const val EXTRA_HIDDEN = "hidden"
		const val EXTRA_AUTO_RESOLVE = "auto_resolve"
		const val EXTRA_ORIGINAL_URL = "original_url"
		private const val HIDDEN_TIMEOUT_MS = 45_000L
	}
}
