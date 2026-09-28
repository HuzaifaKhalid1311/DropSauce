package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.network.AndroidCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.CountDownLatch

class CloudflareInterceptor(
	private val context: Context,
	private val cookieManager: AndroidCookieJar,
	defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {

	private val executor = ContextCompat.getMainExecutor(context)

	override fun shouldIntercept(response: Response): Boolean {
		if (response.code !in ERROR_CODES || response.header("Server") !in SERVER_CHECK) {
			return false
		}
		// Current Mihon: cf-mitigated is Cloudflare's official challenge marker. The page check below
		// stays as a fallback so nothing handled before is missed.
		if (response.header("cf-mitigated") == "challenge") {
			return true
		}
		val document = Jsoup.parse(
			response.peekBody(Long.MAX_VALUE).string(),
			response.request.url.toString(),
		)
		return document.getElementById("challenge-error-title") != null ||
			document.getElementById("challenge-error-text") != null
	}

	override fun intercept(
		chain: Interceptor.Chain,
		request: Request,
		response: Response,
	): Response {
		return try {
			response.close()
			cookieManager.remove(request.url, COOKIE_NAMES, 0)
			val oldCookie = cookieManager.get(request.url)
				.firstOrNull { it.name == "cf_clearance" }
			resolveWithWebView(request, oldCookie)
			chain.proceed(request)
		} catch (e: CloudflareBypassException) {
			throw IOException("Cloudflare bypass failed", e)
		} catch (e: Exception) {
			throw if (e is IOException) e else IOException(e)
		}
	}

	@SuppressLint("SetJavaScriptEnabled")
	private fun resolveWithWebView(originalRequest: Request, oldCookie: Cookie?) {
		val latch = CountDownLatch(1)
		var webView: WebView? = null
		var challengeFound = false
		var cloudflareBypassed = false
		val requestUrl = originalRequest.url.toString()
		val headers = parseHeaders(originalRequest.headers)

		executor.execute {
			val view = createWebView(originalRequest)
			webView = view
			// As in current Mihon: a challenge that needs a tap can't be solved here, so stop waiting
			// instead of sitting out the full 30 seconds before the user is offered the WebView.
			view.addJavascriptInterface(
				object {
					@Suppress("unused")
					@JavascriptInterface
					fun interactiveDetected() {
						latch.countDown()
					}
				},
				"mihon",
			)
			view.webViewClient = object : WebViewClient() {
				override fun onPageFinished(view: WebView, url: String) {
					val newCookie = cookieManager.get(requestUrl.toHttpUrl())
						.firstOrNull { it.name == "cf_clearance" }
					if (newCookie != null && newCookie != oldCookie) {
						cloudflareBypassed = true
						latch.countDown()
					}
					if (url == requestUrl) {
						if (!challengeFound) {
							latch.countDown()
						} else {
							view.evaluateJavascript(
								"""
									addEventListener("message", ({data}) => {
										if (data?.source === "cloudflare-challenge" && data?.event === "interactiveBegin") {
											mihon.interactiveDetected();
										}
									})
								""".trimIndent(),
								null,
							)
						}
					}
				}

				override fun onReceivedHttpError(
					view: WebView?,
					request: WebResourceRequest?,
					errorResponse: WebResourceResponse?,
				) {
					if (request?.isForMainFrame == true) {
						if (errorResponse?.statusCode in ERROR_CODES) {
							challengeFound = true
						} else {
							latch.countDown()
						}
					}
				}
			}
			view.loadUrl(requestUrl, headers)
		}

		latch.awaitFor30Seconds()

		executor.execute {
			webView?.run {
				stopLoading()
				destroy()
			}
		}

		if (!cloudflareBypassed) {
			throw CloudflareBypassException()
		}
	}
}

private val ERROR_CODES = listOf(403, 503)
private val SERVER_CHECK = arrayOf("cloudflare-nginx", "cloudflare")
private val COOKIE_NAMES = listOf("cf_clearance")

private class CloudflareBypassException : Exception()
