package org.koitharu.kotatsu.lnreader.js

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.koitharu.kotatsu.core.exceptions.CloudFlareException
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.lnreader.LnPluginManager
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs LNReader novel plugins in a single headless [WebView].
 *
 * A WebView rather than the already-available QuickJS because the whole plugin contract is
 * Promise-based and `app.cash.quickjs` has no job-queue pump. It also shares the app's cookie jar,
 * so the existing Cloudflare resolver flow works for novels too.
 *
 * Plugin `fetch` is routed back into the app's OkHttp through [Bridge.httpRequest], which is why
 * CORS never applies and UA/DoH/proxy/rate-limiting all still hold.
 *
 * ponytail: one shared realm for every plugin, like LNReader itself. A misbehaving plugin can
 * poison a global; per-plugin realms would need one WebView each. Split only if that actually bites.
 */
@Singleton
class JsHost @Inject constructor(
	@ApplicationContext private val context: Context,
	@MangaHttpClient private val okHttpClient: OkHttpClient,
) {

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val bootMutex = Mutex()
	private val seq = AtomicLong()
	private val pending = ConcurrentHashMap<Long, CompletableDeferred<String>>()

	@Volatile
	private var webView: WebView? = null

	// The bridge can only carry an error string, so a Cloudflare failure would reach the UI as a plain
	// JsException and never trigger the captcha flow. Remember the real exception and rethrow it.
	// ponytail: one slot, not one per call — the plugin id is unknown at fetch time anyway, and two
	// plugins failing on the same tick would only mean the captcha prompt names the other site.
	@Volatile
	private var lastCloudFlareError: CloudFlareException? = null

	@SuppressLint("SetJavaScriptEnabled")
	private suspend fun ensureReady(): WebView {
		webView?.let { return it }
		return bootMutex.withLock {
			webView?.let { return@withLock it }
			// Assets are read off the main thread; only the WebView itself is touched on it.
			val hostJs = context.assets.open(ASSET_HOST).use { it.readBytes().decodeToString() }
			val libsJs = context.assets.open(ASSET_LIBS).use { it.readBytes().decodeToString() }
			withContext(Dispatchers.Main) {
				val loaded = CompletableDeferred<Unit>()
				val view = WebView(context)
				view.settings.javaScriptEnabled = true
				view.settings.domStorageEnabled = true
				view.webViewClient = object : WebViewClient() {
					override fun onPageFinished(view: WebView, url: String?) {
						loaded.complete(Unit)
					}
				}
				view.addJavascriptInterface(Bridge(), "Native")
				// A real https origin rather than about:blank: DOM storage is unavailable on an opaque
				// origin, and some plugins build urls against location.
				view.loadDataWithBaseURL(BASE_URL, "<html><body></body></html>", "text/html", "utf-8", null)
				loaded.await()
				// Host glue first - it installs globalThis.fetch, and the libs bundle would otherwise
				// capture the real one and bypass OkHttp.
				view.evaluateJavascript(hostJs, null)
				view.evaluateJavascript(libsJs, null)
				webView = view
				view
			}
		}
	}

	/** Evaluates [script] and returns its value, which the JS side must produce synchronously. */
	private suspend fun eval(script: String): String {
		val view = ensureReady()
		return withContext(Dispatchers.Main) {
			val result = CompletableDeferred<String>()
			view.evaluateJavascript(script) { result.complete(it ?: "null") }
			result.await()
		}.unwrapJsString()
	}

	/**
	 * Loads [rawCode] as plugin [pluginId] and returns the metadata the JS side extracted from it.
	 * @throws JsException when the plugin fails to evaluate or exports no default.
	 */
	suspend fun install(pluginId: String, rawCode: String): JSONObject =
		eval("__host.install(${JSONObject.quote(pluginId)},${JSONObject.quote(rawCode)})").requireOk()

	suspend fun uninstall(pluginId: String) {
		eval("__host.uninstall(${JSONObject.quote(pluginId)});''")
	}

	suspend fun isInstalled(pluginId: String): Boolean =
		eval("''+__host.isInstalled(${JSONObject.quote(pluginId)})") == "true"

	/** `resolveUrl` is sync in the plugin contract, so it needs no round trip through [pending]. */
	suspend fun resolveUrl(pluginId: String, path: String, isNovel: Boolean): String =
		eval("__host.resolveUrl(${JSONObject.quote(pluginId)},${JSONObject.quote(path)},$isNovel)")

	/**
	 * Invokes `pluginId.fn(...args)` and awaits the Promise it returns.
	 *
	 * `evaluateJavascript` is fire-and-forget, so calls multiplex for free: JS replies through
	 * [Bridge.resolve] with the id handed to it, and concurrent calls never queue behind each other.
	 */
	suspend fun call(
		pluginId: String,
		fn: String,
		args: List<Any?>,
		timeoutMs: Long = DEFAULT_TIMEOUT_MS,
	): Any? {
		val argsJson = args.toJsonArrayString()
		val view = ensureReady()
		val id = seq.incrementAndGet()
		val deferred = CompletableDeferred<String>()
		pending[id] = deferred
		try {
			withContext(Dispatchers.Main) {
				view.evaluateJavascript(
					"__host.call($id,${JSONObject.quote(pluginId)},${JSONObject.quote(fn)},${JSONObject.quote(argsJson)})",
					null,
				)
			}
			val reply = withTimeout(timeoutMs) { deferred.await() }
			val json = JSONObject(reply)
			json.optString("err").takeIf { it.isNotEmpty() }?.let { err ->
				lastCloudFlareError?.let { cf ->
					lastCloudFlareError = null
					throw cf
				}
				throw JsException("$pluginId.$fn: $err")
			}
			// A call that came back fine means the challenge is gone — don't hold a stale one.
			lastCloudFlareError = null
			return json.opt("ok").takeUnless { it == JSONObject.NULL }
		} finally {
			pending.remove(id)
		}
	}

	private inner class Bridge {

		@JavascriptInterface
		fun resolve(id: String, json: String) {
			pending[id.toLongOrNull() ?: return]?.complete(json)
		}

		@JavascriptInterface
		fun httpRequest(id: String, spec: String) {
			// Arrives on a JavaBridge thread, so the reply has to be posted back to Main.
			scope.launch {
				val reply = runCatching { performRequest(JSONObject(spec)) }
					.getOrElse { JSONObject().put("err", it.toString()) }
				val view = webView ?: return@launch
				withContext(Dispatchers.Main) {
					view.evaluateJavascript(
						"__host.httpResolve(${JSONObject.quote(id)},${JSONObject.quote(reply.toString())})",
						null,
					)
				}
			}
		}

		@JavascriptInterface
		fun storageGet(pluginId: String, ns: String, key: String): String? = prefs(pluginId, ns).getString(key, null)

		@JavascriptInterface
		fun storageSet(pluginId: String, ns: String, key: String, value: String?) {
			prefs(pluginId, ns).edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
		}

		@JavascriptInterface
		fun storageKeys(pluginId: String, ns: String): String =
			prefs(pluginId, ns).all.keys.toList().toJsonArrayString()

		@JavascriptInterface
		fun storageClear(pluginId: String, ns: String) {
			prefs(pluginId, ns).edit().clear().apply()
		}
	}

	private fun prefs(pluginId: String, ns: String) =
		context.getSharedPreferences("ln_${pluginId}_$ns", Context.MODE_PRIVATE)

	private fun performRequest(spec: JSONObject): JSONObject {
		val method = spec.optString("method", "GET").ifEmpty { "GET" }
		val headers = spec.optJSONObject("headers")
		val rawBody = spec.optString("body").takeIf { !spec.isNull("body") && it.isNotEmpty() }
		val body = rawBody?.let {
			val bytes = if (spec.optBoolean("bodyIsBase64")) {
				Base64.decode(it, Base64.DEFAULT)
			} else {
				it.encodeToByteArray()
			}
			bytes.toRequestBody(headers?.optString("Content-Type")?.toMediaTypeOrNull())
		}
		val url = spec.getString("url").toHttpUrl()
		val builder = Request.Builder().url(url).method(method, body)
		headers?.let { h -> h.keys().forEach { key -> builder.header(key, h.optString(key)) } }
		// Tagging the source is what makes the shared interceptors work for novels: CommonHeaders can
		// resolve the repository, and a CloudFlareException carries the source the captcha flow keys on.
		LnPluginManager.findBySiteHost(url.host)?.let { source ->
			builder.tag(MangaSource::class.java, source)
		}
		try {
			return performRequest(builder.build(), spec)
		} catch (e: CloudFlareException) {
			lastCloudFlareError = e
			throw e
		}
	}

	private fun performRequest(request: Request, spec: JSONObject): JSONObject {
		okHttpClient.newCall(request).execute().use { response ->
			val responseHeaders = JSONObject()
			response.headers.forEach { (name, value) -> responseHeaders.put(name, value) }
			val result = JSONObject()
				.put("status", response.code)
				.put("statusText", response.message)
				.put("url", response.request.url.toString())
				.put("headers", responseHeaders)
			val bytes = response.body.bytes()
			// Legacy-charset and binary reads ask for base64 so the JS side picks the decoder;
			// everything else is plain utf-8 text.
			if (spec.optBoolean("wantBase64")) {
				result.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
			} else {
				result.put("body", bytes.decodeToString())
			}
			return result
		}
	}

	private companion object {

		const val ASSET_HOST = "lnreader-host.js"
		const val ASSET_LIBS = "lnreader-libs.js"
		const val BASE_URL = "https://lnreader.localhost/"
		const val DEFAULT_TIMEOUT_MS = 60_000L
	}
}

class JsException(message: String) : Exception(message)

/** `evaluateJavascript` hands back a JSON-encoded value, so a string result arrives quoted. */
private fun String.unwrapJsString(): String = when (val value = runCatching { JSONTokener(this).nextValue() }.getOrNull()) {
	is String -> value
	null, JSONObject.NULL -> ""
	else -> value.toString()
}

private fun String.requireOk(): JSONObject {
	val json = JSONObject(this)
	json.optString("err").takeIf { it.isNotEmpty() }?.let { throw JsException(it) }
	return json.getJSONObject("ok")
}

private fun List<Any?>.toJsonArrayString(): String = JSONArray().also { array ->
	forEach { array.put(it ?: JSONObject.NULL) }
}.toString()
