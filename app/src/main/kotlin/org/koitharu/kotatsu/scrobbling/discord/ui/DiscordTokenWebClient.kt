package org.haziffe.dropsauce.scrobbling.discord.ui

import android.graphics.Bitmap
import android.webkit.WebView
import org.haziffe.dropsauce.browser.BrowserCallback
import org.haziffe.dropsauce.browser.BrowserClient
import org.haziffe.dropsauce.parsers.util.removeSurrounding

class DiscordTokenWebClient(private val callback: Callback) : BrowserClient(callback, null) {

	override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
		super.onPageStarted(view, url, favicon)
		if (view != null) {
			checkToken(view)
		}
	}

	private fun checkToken(view: WebView) {
		view.evaluateJavascript("window.localStorage.token") { result ->
			val token = result
				?.replace("\\\"", "")
				?.removeSurrounding('"')
				?.takeUnless { it == "null" }
			if (!token.isNullOrEmpty()) {
				callback.onTokenObtained(token)
			}
		}
	}

	interface Callback : BrowserCallback {

		fun onTokenObtained(token: String)
	}
}
