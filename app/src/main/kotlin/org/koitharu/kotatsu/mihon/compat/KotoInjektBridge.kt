package org.koitharu.kotatsu.mihon.compat

import android.app.Application
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.StringFormat
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.parsers.network.UserAgents
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import javax.inject.Inject
import javax.inject.Singleton

class KotoNetworkHelper(
	baseClient: OkHttpClient,
	private val cookieJar: CookieJar,
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
		baseClient.networkInterceptors.forEach(::addNetworkInterceptor)
	}.build()

	@Deprecated("The regular client handles Cloudflare by default")
	override val cloudflareClient: OkHttpClient
		get() = client

	override fun defaultUserAgentProvider(): String = UserAgents.FIREFOX_MOBILE
}

@Singleton
class KotoInjektBridge @Inject constructor(
	@ApplicationContext private val context: Context,
	@MangaHttpClient private val httpClient: OkHttpClient,
	private val cookieJar: MutableCookieJar,
) {
	@Volatile
	private var initialized = false

	@Synchronized
	fun initialize() {
		if (initialized) return
		val application = context.applicationContext as Application
		val networkHelper = KotoNetworkHelper(httpClient, cookieJar)
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
