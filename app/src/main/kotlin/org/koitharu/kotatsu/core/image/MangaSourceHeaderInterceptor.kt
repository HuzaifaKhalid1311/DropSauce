package org.koitharu.kotatsu.core.image

import coil3.intercept.Interceptor
import coil3.network.httpHeaders
import coil3.request.ImageResult
import org.json.JSONObject
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.util.ext.mangaSourceKey

class MangaSourceHeaderInterceptor : Interceptor {

	override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
		val mangaSource = chain.request.extras[mangaSourceKey]?.unwrap() ?: return chain.proceed()
		val request = chain.request
		val newHeaders = request.httpHeaders.newBuilder()
			.set(CommonHeaders.MANGA_SOURCE, mangaSource.name)
			.apply {
				// Novel plugins declare cover headers (usually a Referer) as fetch-style imageRequestInit.
				(mangaSource as? LnMangaSource)?.plugin?.imageRequestInit?.let { raw ->
					runCatching { JSONObject(raw).optJSONObject("headers") }.getOrNull()?.let { headers ->
						for (key in headers.keys()) set(key, headers.optString(key))
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
