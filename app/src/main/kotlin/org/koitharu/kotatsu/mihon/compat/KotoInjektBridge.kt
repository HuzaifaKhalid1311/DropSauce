package org.koitharu.kotatsu.mihon.compat

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.StringFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import eu.kanade.tachiyomi.network.AndroidCookieJar
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.network.CacheLimitInterceptor
import org.koitharu.kotatsu.core.network.CloudFlareInterceptor
import org.koitharu.kotatsu.core.network.CommonHeadersInterceptor
import org.koitharu.kotatsu.core.network.GZipInterceptor
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.network.RateLimitInterceptor
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.network.webview.WebViewExecutor
import org.koitharu.kotatsu.parsers.network.UserAgents
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import javax.inject.Inject
import javax.inject.Singleton

class KotoNetworkHelper(
	private val context: Context,
	private val baseClient: OkHttpClient,
	private val androidCookieJar: AndroidCookieJar,
	private val userAgentProvider: () -> String = { UserAgents.CHROME_MOBILE },
) : NetworkHelper() {

	/** Expose the cookie jar so extensions can read/write session cookies. */
	override val cookieJar: AndroidCookieJar get() = androidCookieJar

	/**
	 * Never expose Kotatsu's native base here: it contains the app-wide rate limiter. Current Mihon
	 * deprecates the split client because the regular extension client handles Cloudflare safely.
	 */
	override val nonCloudflareClient: OkHttpClient get() = client

	override val client: OkHttpClient = baseClient.newBuilder().apply {
		// Preserve the complete transport configuration (custom TLS trust, certificate pinning,
		// proxy/authenticator, protocols and event listeners), then rebuild only the interceptor
		// chains. A field-by-field Builder reconstruction silently drops those settings.
		interceptors().clear()
		networkInterceptors().clear()
		// Mihon caps a complete call at two minutes. Copying only connect/read/write timeouts leaves
		// redirects and retries able to hang indefinitely, which is observably different to extensions.
		callTimeout(2, java.util.concurrent.TimeUnit.MINUTES)
		// Mihon uses one AndroidCookieJar as the extension client's authoritative store. Do the
		// same: merging Kotatsu's separate jar by cookie name can let a stale value override a
		// WebView-issued cookie (mhub_access/cf_clearance), even though the extension just solved
		// the challenge. The single system CookieManager also gives extension-created WebViews and
		// OkHttp the exact shared session semantics extensions are compiled and tested against.
		cookieJar(androidCookieJar)

		// Mirror Mihon's NetworkHelper exactly. UncaughtExceptionInterceptor runs outermost so
		// any non-IOException thrown deeper in the chain (e.g. by an extension interceptor) is
		// wrapped as IOException — extensions' RxJava/retry code expects only IOExceptions.
		addInterceptor(UncaughtExceptionInterceptor())

		// Ensure every extension request carries a User-Agent when the source didn't set one,
		// using the same configurable default as Mihon. Added before Cloudflare detection so it
		// runs before the challenge check sees the request.
		addInterceptor(UserAgentInterceptor(::defaultUserAgentProvider))

		// Do NOT add IgnoreGzipInterceptor/BrotliInterceptor here: current Mihon removed them,
		// and new keiyoushi extensions (KeiSource core) add their own CompressionInterceptor and
		// hard-fail with "must not be present in default client" if the host still injects them.
		// OkHttp handles gzip transparently on its own. (The app's GZipInterceptor is skipped below.)

		baseClient.interceptors.forEach { interceptor ->
			// Skip GZip (handled by OkHttp) and Kotatsu's CloudFlareInterceptor: the latter throws
			// CloudFlareBlockedException on any 403/503 block page, which aborts extensions (e.g.
			// Kagane) that deliberately request a Cloudflare-fronted page and ignore the result.
			// Cloudflare handling for extensions is done by the dedicated interceptor below instead.
			// Kotatsu throttles its native parsers globally, while Mihon only rate-limits when an
			// extension opts in with OkHttpClient.Builder.rateLimit(). Never leak the app-wide
			// limiter into the shared extension client.
			if (
				interceptor !is GZipInterceptor &&
				interceptor !is CloudFlareInterceptor &&
				interceptor !is RateLimitInterceptor &&
				interceptor !is CommonHeadersInterceptor
			) {
				addInterceptor(interceptor)
			}
		}

		// Use Mihon's own Cloudflare/WebView interceptor. Its response detection, cookie removal,
		// WebView headers, timeout and one-time retry are part of extension-observable behavior.
		addInterceptor(CloudflareInterceptor(context, androidCookieJar, ::defaultUserAgentProvider))

		baseClient.networkInterceptors
			// Kotatsu clamps cache freshness to one hour for native parsers. Mihon preserves the
			// server/extension cache policy, which avoids needless API and cover refetches.
			.filterNot { it is CacheLimitInterceptor }
			.forEach(::addNetworkInterceptor)
	}.build()

	@Deprecated("The regular client handles Cloudflare by default")
	override val cloudflareClient: OkHttpClient
		get() = client

	override fun defaultUserAgentProvider(): String = userAgentProvider()
}

@Singleton
class KotoInjektBridge @Inject constructor(
	@ApplicationContext private val context: Context,
	@MangaHttpClient private val httpClient: OkHttpClient,
	private val webViewExecutor: WebViewExecutor,
	private val settings: AppSettings,
) {
	@Volatile
	private var initialized = false

	// Single AndroidCookieJar instance shared across Injekt and KotoNetworkHelper.
	// It wraps Android's system CookieManager so extensions and WebViews share one cookie store.
	private val androidCookieJar = AndroidCookieJar()

	@Synchronized
	fun initialize() {
		if (initialized) return
		val application = context.applicationContext as Application
		val networkHelper = KotoNetworkHelper(
			context = context,
			baseClient = httpClient,
			androidCookieJar = androidCookieJar,
			// Cloudflare binds cf_clearance to the UA that earned it.  configureForParser()
			// strips "Version/x.x" and normalises the device string in the WebView UA, so
			// OkHttp must use the same transformed UA or cf_clearance will be rejected.
			userAgentProvider = {
				val base = settings.mihonUserAgentOverride
					?: webViewExecutor.defaultUserAgent
					?: AppSettings.DEFAULT_MIHON_USER_AGENT
				base
					.replace(Regex("; Android .*?\\)"), "; Android 10; K)")
					.replace(Regex("Version/.* Chrome/"), "Chrome/")
			},
		)
		val json = Json {
			ignoreUnknownKeys = true
			explicitNulls = false
		}
		val xml = XML.v1 {
			policy {
				ignoreUnknownChildren()
				autoPolymorphic = true
			}
			xmlDeclMode = XmlDeclMode.Charset
			xmlVersion = XmlVersion.XML10
			setIndent(2)
		}
		val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
		val preferenceStore = AndroidPreferenceStore(context, sharedPreferences)
		Injekt.importModule(object : InjektModule {
			override fun InjektRegistrar.registerInjectables() {
				addSingleton(application)
				addSingletonFactory<Context> { context.applicationContext }
				addSingletonFactory<NetworkHelper> { networkHelper }
				// Direct injections must resolve to the same unthrottled base and cookie jar used by
				// HttpSource.client; returning Kotatsu's native client reintroduces the global limiter.
				addSingletonFactory<OkHttpClient> { networkHelper.client }
				addSingletonFactory<CookieJar> { networkHelper.client.cookieJar }
				// AndroidCookieJar wraps Android's system CookieManager.  Extensions that do
				// `val cookieManager: AndroidCookieJar by injectLazy()` (e.g. for custom CF
				// interceptors) need this registration or they crash with a missing-binding error.
				addSingletonFactory<AndroidCookieJar> { androidCookieJar }
				// Extensions compile PreferenceStore as a host-provided service. Register both the
				// typed Mihon facade and its backing default SharedPreferences instance.
				addSingletonFactory<SharedPreferences> { sharedPreferences }
				addSingletonFactory<PreferenceStore> { preferenceStore }
				addSingletonFactory<Json> { json }
				addSingletonFactory<StringFormat> { json }
				addSingletonFactory<SerialFormat> { json }
				// XML serialization (used by extensions that parse XML manga sites)
				addSingletonFactory<XML> { xml }
				// ProtoBuf serialization (used by extensions that use protobuf APIs)
				addSingletonFactory<ProtoBuf> { ProtoBuf }
				// JavaScript engine — uses QuickJS for synchronous, thread-safe JS evaluation.
				// Context and executor params are kept for Injekt/API compat but QuickJS ignores them.
				addSingletonFactory<JavaScriptEngine> { JavaScriptEngine(context) }
			}
		})
		initialized = true
	}
}
