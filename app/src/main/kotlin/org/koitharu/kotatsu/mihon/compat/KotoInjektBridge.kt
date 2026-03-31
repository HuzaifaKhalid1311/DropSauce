package org.haziffe.dropsauce.mihon.compat

import android.app.Application
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.StringFormat
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.haziffe.dropsauce.core.exceptions.CloudFlareBlockedException
import org.haziffe.dropsauce.core.exceptions.CloudFlareProtectedException
import org.haziffe.dropsauce.core.exceptions.InteractiveActionRequiredException
import org.haziffe.dropsauce.core.network.MangaHttpClient
import org.haziffe.dropsauce.core.network.cookies.MutableCookieJar
import org.haziffe.dropsauce.core.network.webview.WebViewExecutor
import org.haziffe.dropsauce.parsers.model.MangaSource
import org.haziffe.dropsauce.parsers.network.CloudFlareHelper
import org.haziffe.dropsauce.parsers.network.UserAgents
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

class KotoNetworkHelper(
	baseClient: OkHttpClient,
	private val cookieJar: MutableCookieJar,
	private val webViewExecutor: WebViewExecutor? = null,
) : NetworkHelper() {
    
    override val client: OkHttpClient = OkHttpClient.Builder().apply {
		connectTimeout(baseClient.connectTimeoutMillis.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
		readTimeout(baseClient.readTimeoutMillis.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
		writeTimeout(baseClient.writeTimeoutMillis.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
		cookieJar(baseClient.cookieJar)
		dns(baseClient.dns)
		cache(baseClient.cache)
		dispatcher(baseClient.dispatcher)
		connectionPool(baseClient.connectionPool)
		followRedirects(baseClient.followRedirects)
		followSslRedirects(baseClient.followSslRedirects)
		retryOnConnectionFailure(baseClient.retryOnConnectionFailure)
		proxy(baseClient.proxy)
		proxySelector(baseClient.proxySelector)
		proxyAuthenticator(baseClient.proxyAuthenticator)
		socketFactory(baseClient.socketFactory)
		hostnameVerifier(baseClient.hostnameVerifier)
		baseClient.interceptors.forEach { interceptor ->
			if (interceptor.javaClass.simpleName != "GZipInterceptor") {
				addInterceptor(interceptor)
			}
		}

        // Add a Mihon-specific fallback detector.
        addInterceptor { chain ->
            val originalRequest = chain.request()
            val request = enrichApiRequestHeadersIfNeeded(originalRequest)
            val response = chain.proceed(request)
            val challengeUrl = request.toChallengeUrl()
            when (CloudFlareHelper.checkResponseForProtection(response)) {
                CloudFlareHelper.PROTECTION_BLOCKED -> response.closeThrowing(
                    CloudFlareBlockedException(
                        url = challengeUrl,
                        source = request.tag(MangaSource::class.java),
                    ),
                )

                CloudFlareHelper.PROTECTION_CAPTCHA -> {
                    val host = request.url.host.lowercase()
                    val clearance = cookieJar.loadForRequest(request.url)
                        .firstOrNull { it.name == "cf_clearance" }
                        ?.value
                        
                    val source = request.tag(MangaSource::class.java)

                    if (webViewExecutor != null && source != null) {
                        val cfEx = CloudFlareProtectedException(
                            url = challengeUrl,
                            source = source,
                            headers = request.headers
                        )
                        val resolved = runCatching {
                            runBlocking { webViewExecutor.tryResolveCaptcha(cfEx, 15000L) }
                        }.getOrDefault(false)
                        
                        if (resolved) {
                            android.util.Log.i("MihonNetwork", "WebView headless fallback succeeded for host=$host")
                            response.close()
                            // Proceed again with original request since the cookie jar now has the cf_clearance!
                            return@addInterceptor chain.proceed(request)
                        } else {
                            android.util.Log.w("MihonNetwork", "WebView headless fallback failed for host=$host")
                        }
                    }

                    if (shouldSkipInteractiveAction(host, clearance)) {
                        android.util.Log.w(
                            "MihonNetwork",
                            "Skip interactive action for host=$host: repeated challenge with same cf_clearance",
                        )
                        response.closeThrowing(
                            CloudFlareBlockedException(
                                url = challengeUrl,
                                source = source,
                            ),
                        )
                    } else {
                        if (source == null) {
                            android.util.Log.e("MihonNetwork", "Missing MangaSource tag for interactive action fallback")
                            response.closeThrowing(CloudFlareBlockedException(url = challengeUrl, source = null))
                        } else {
                            response.closeThrowing(
                                InteractiveActionRequiredException(
                                    source = source,
                                    url = challengeUrl,
                                    userAgent = request.header("User-Agent"),
                                    successCookieUrl = challengeUrl,
                                    successCookieName = "cf_clearance",
                                ),
                            )
                        }
                    }
                }

                else -> response
            }
        }

		// Also log the Mihon network flow
		addInterceptor { chain ->
			val req = chain.request()
			val response = chain.proceed(req)
			val cf = response.header("server")
			val status = response.code
			if (cf == "cloudflare" || status == 403 || status == 503) {
				android.util.Log.d("MihonNetwork", "Response: $status for ${req.url}")
			}
			response
		}

		baseClient.networkInterceptors.forEach(::addNetworkInterceptor)
	}.build()

	@Deprecated("The regular client handles Cloudflare by default")
	override val cloudflareClient: OkHttpClient
		get() = client

	override fun defaultUserAgentProvider(): String = UserAgents.CHROME_MOBILE

    private fun Response.closeThrowing(error: Throwable): Nothing {
        try {
            close()
        } catch (e: Exception) {
            error.addSuppressed(e)
        }
        throw error
    }

    private fun Request.toChallengeUrl(): String {
        val referer = header("Referer")?.toHttpUrlOrNull()
        if (referer != null && referer.host == url.host) {
            return referer.newBuilder()
                .query(null)
                .fragment(null)
                .build()
                .toString()
        }
        return url.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    private fun enrichApiRequestHeadersIfNeeded(request: Request): Request {
        if (!request.url.encodedPath.startsWith("/api/")) return request
        val cookies = cookieJar.loadForRequest(request.url)
        val hasCfClearance = cookies.any { it.name == "cf_clearance" }
        if (!hasCfClearance) return request
        val origin = "${request.url.scheme}://${request.url.host}"
        var modified = false
        val builder = request.newBuilder()
        if (request.header("Referer").isNullOrBlank()) {
            builder.header("Referer", "$origin/")
            modified = true
        }
        if (request.header("Origin").isNullOrBlank()) {
            builder.header("Origin", origin)
            modified = true
        }
        if (request.header("Accept").isNullOrBlank()) {
            builder.header("Accept", "application/json, text/plain, */*")
            modified = true
        }
        if (request.header("Accept-Language").isNullOrBlank()) {
            builder.header("Accept-Language", "en-US,en;q=0.9")
            modified = true
        }
        if (request.header("Sec-Fetch-Site").isNullOrBlank()) {
            builder.header("Sec-Fetch-Site", "same-origin")
            modified = true
        }
        if (request.header("Sec-Fetch-Mode").isNullOrBlank()) {
            builder.header("Sec-Fetch-Mode", "cors")
            modified = true
        }
        if (request.header("Sec-Fetch-Dest").isNullOrBlank()) {
            builder.header("Sec-Fetch-Dest", "empty")
            modified = true
        }
        if (request.header("X-Requested-With").isNullOrBlank()) {
            builder.header("X-Requested-With", "XMLHttpRequest")
            modified = true
        }
        if (request.header("X-XSRF-TOKEN").isNullOrBlank()) {
            val xsrf = cookies.firstOrNull { it.name == "XSRF-TOKEN" }?.value
            val decodedXsrf = xsrf?.let {
                runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrDefault(it)
            }
            if (!decodedXsrf.isNullOrBlank()) {
                builder.header("X-XSRF-TOKEN", decodedXsrf)
                modified = true
            }
        }
        return if (modified) builder.build() else request
    }

    private fun shouldSkipInteractiveAction(host: String, clearance: String?): Boolean {
        if (clearance.isNullOrBlank()) return false
        val now = System.currentTimeMillis()
        val last = recentChallengeAttempts[host]
        if (last == null || now - last.timestampMs > INTERACTIVE_RETRY_WINDOW_MS || last.clearance != clearance) {
            recentChallengeAttempts[host] = ChallengeAttempt(
                clearance = clearance,
                timestampMs = now,
                count = 1,
            )
            return false
        }
        val nextCount = last.count + 1
        recentChallengeAttempts[host] = last.copy(
            timestampMs = now,
            count = nextCount,
        )
        return nextCount >= 2
    }

    private data class ChallengeAttempt(
        val clearance: String,
        val timestampMs: Long,
        val count: Int,
    )

    companion object {
        private const val INTERACTIVE_RETRY_WINDOW_MS = 10 * 60 * 1000L
        private val recentChallengeAttempts = ConcurrentHashMap<String, ChallengeAttempt>()
    }
}

@Singleton
class KotoInjektBridge @Inject constructor(
	@ApplicationContext private val context: Context,
	@MangaHttpClient private val httpClient: OkHttpClient,
	private val cookieJar: MutableCookieJar,
    private val webViewExecutor: WebViewExecutor,
) {
	@Volatile
	private var initialized = false

	@Synchronized
	fun initialize() {
		if (initialized) return
		val application = context.applicationContext as Application
		val networkHelper = KotoNetworkHelper(httpClient, cookieJar, webViewExecutor)
		val json = Json {
			ignoreUnknownKeys = true
			explicitNulls = false
		}
		Injekt.importModule(object : InjektModule {
			override fun InjektRegistrar.registerInjectables() {
				addSingleton(application)
				addSingletonFactory<Context> { context.applicationContext }
				addSingletonFactory<NetworkHelper> { networkHelper }
				addSingletonFactory<OkHttpClient> { httpClient }
				addSingletonFactory<CookieJar> { cookieJar }
				addSingletonFactory<Json> { json }
				addSingletonFactory<StringFormat> { json }
				addSingletonFactory<SerialFormat> { json }
			}
		})
		initialized = true
	}
}
