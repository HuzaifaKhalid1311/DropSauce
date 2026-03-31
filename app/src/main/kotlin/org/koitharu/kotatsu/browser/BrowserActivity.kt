package org.haziffe.dropsauce.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.core.exceptions.InteractiveActionRequiredException
import org.haziffe.dropsauce.core.nav.AppRouter
import org.haziffe.dropsauce.core.nav.router
import org.haziffe.dropsauce.core.parser.ParserMangaRepository
import org.haziffe.dropsauce.core.util.ext.getDisplayMessage
import org.haziffe.dropsauce.core.util.ext.printStackTraceDebug
import org.haziffe.dropsauce.parsers.model.MangaSource

import android.webkit.CookieManager

@AndroidEntryPoint
class BrowserActivity : BaseBrowserActivity() {

	private var successCookieUrl: String? = null
	private var successCookieName: String? = null
	private var initialCookieValue: String? = null

	override fun onCreate2(savedInstanceState: Bundle?, source: MangaSource, repository: ParserMangaRepository?) {
		successCookieUrl = intent?.getStringExtra(AppRouter.KEY_SUCCESS_COOKIE_URL)
		successCookieName = intent?.getStringExtra(AppRouter.KEY_SUCCESS_COOKIE_NAME)
		if (successCookieUrl != null && successCookieName != null) {
			initialCookieValue = getCookieValue(successCookieUrl!!, successCookieName!!)
		}

		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)
		viewBinding.webView.webViewClient = BrowserClient(this, adBlock)
		lifecycleScope.launch {
			try {
				proxyProvider.applyWebViewConfig()
			} catch (e: Exception) {
				e.printStackTraceDebug()
				Snackbar.make(viewBinding.webView, e.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
			}
			if (savedInstanceState == null) {
				val url = intent?.dataString
				if (url.isNullOrEmpty()) {
					finishAfterTransition()
				} else {
					onTitleChanged(
						intent?.getStringExtra(AppRouter.KEY_TITLE) ?: getString(R.string.loading_),
						url,
					)
					viewBinding.webView.loadUrl(url)
				}
			}
		}
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		super.onCreateOptionsMenu(menu)
		menuInflater.inflate(R.menu.opt_browser, menu)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		android.R.id.home -> {
			viewBinding.webView.stopLoading()
			finishAfterTransition()
			true
		}

		R.id.action_browser -> {
			if (!router.openExternalBrowser(viewBinding.webView.url.orEmpty(), item.title)) {
				Snackbar.make(viewBinding.webView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
			}
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	override fun finish() {
		if (successCookieUrl != null && successCookieName != null) {
			val currentValue = getCookieValue(successCookieUrl!!, successCookieName!!)
			if (currentValue != initialCookieValue && !currentValue.isNullOrBlank()) {
				setResult(RESULT_OK)
			} else {
				setResult(RESULT_CANCELED)
			}
		} else {
            setResult(RESULT_OK)
        }
		super.finish()
	}

	private fun getCookieValue(url: String, cookieName: String): String? {
		val cookies = CookieManager.getInstance().getCookie(url) ?: return null
		return cookies.split(";")
			.map { it.trim() }
			.firstOrNull { it.startsWith("$cookieName=") }
			?.substringAfter("=")
	}

	class Contract : ActivityResultContract<InteractiveActionRequiredException, Boolean>() {
		override fun createIntent(
			context: Context,
			input: InteractiveActionRequiredException
		): Intent = AppRouter.browserIntent(
			context = context,
			url = input.url,
			source = input.source,
			title = null,
		).apply {
			putExtra(AppRouter.KEY_SUCCESS_COOKIE_URL, input.successCookieUrl)
			putExtra(AppRouter.KEY_SUCCESS_COOKIE_NAME, input.successCookieName)
			if (input.userAgent != null) {
				putExtra(AppRouter.KEY_USER_AGENT, input.userAgent)
			}
		}

		override fun parseResult(resultCode: Int, intent: Intent?): Boolean {
			return resultCode == android.app.Activity.RESULT_OK
		}
	}

	companion object {

		const val TAG = "BrowserActivity"
	}
}
