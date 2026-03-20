package eu.kanade.tachiyomi.network

import okhttp3.OkHttpClient

abstract class NetworkHelper {
	abstract val client: OkHttpClient

	@Deprecated("The regular client handles Cloudflare by default")
	open val cloudflareClient: OkHttpClient
		get() = client

	abstract fun defaultUserAgentProvider(): String
}
