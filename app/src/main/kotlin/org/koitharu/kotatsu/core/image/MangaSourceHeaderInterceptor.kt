package org.koitharu.kotatsu.core.image

import coil3.intercept.Interceptor
import coil3.network.httpHeaders
import coil3.request.ImageResult
import org.json.JSONObject
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.network.webview.WebViewExecutor
import org.koitharu.kotatsu.core.util.ext.mangaSourceKey
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.parsers.network.UserAgents
import javax.inject.Provider

class MangaSourceHeaderInterceptor(
	private val webViewExecutor: Provider<WebViewExecutor>,
) : Interceptor {

	private val lnUserAgent: String by lazy {
		(webViewExecutor.get().defaultUserAgent ?: UserAgents.FIREFOX_MOBILE)
			.replace(Regex("; Android .*?\\)"), "; Android 10; K)")
			.replace(Regex("Version/.* Chrome/"), "Chrome/")
	}

	override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
		val mangaSource = chain.request.extras[mangaSourceKey]?.unwrap() ?: return chain.proceed()
		val request = chain.request
		val newHeaders = request.httpHeaders.newBuilder()
			.set(CommonHeaders.MANGA_SOURCE, mangaSource.name)
			.apply {
				// Novel plugins declare cover headers (usually a Referer) as fetch-style imageRequestInit.
				(mangaSource as? LnMangaSource)?.let { source ->
					// Cloudflare cookies are UA-bound; match LN fetches and the resolver WebView.
					// An extension-declared User-Agent below intentionally overrides this default.
					set(CommonHeaders.USER_AGENT, lnUserAgent)
					source.plugin.imageRequestInit?.let { raw ->
						runCatching { JSONObject(raw).optJSONObject("headers") }.getOrNull()?.let { headers ->
							for (key in headers.keys()) set(key, headers.optString(key))
						}
					}
				}
			}
			.build()
		val newRequest = request.newBuilder()
			.httpHeaders(newHeaders)
			.build()
		return chain.withRequest(newRequest).proceed()
	}
}
