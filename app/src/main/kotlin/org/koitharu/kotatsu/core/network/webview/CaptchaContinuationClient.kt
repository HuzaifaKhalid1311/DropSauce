package org.haziffe.dropsauce.core.network.webview

import android.graphics.Bitmap
import android.webkit.WebView
import org.haziffe.dropsauce.core.network.cookies.MutableCookieJar
import org.haziffe.dropsauce.parsers.network.CloudFlareHelper
import kotlin.coroutines.Continuation

class CaptchaContinuationClient(
	private val cookieJar: MutableCookieJar,
	private val targetUrl: String,
	continuation: Continuation<Unit>,
) : ContinuationResumeWebViewClient(continuation) {

	private val oldClearance = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)

	override fun onPageFinished(view: WebView?, url: String?) = Unit

	override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
		super.onPageStarted(view, url, favicon)
		checkClearance(view)
	}

	private fun checkClearance(view: WebView?) {
		val clearance = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)
		if (clearance != null && clearance != oldClearance) {
			resumeContinuation(view)
		}
	}
}
