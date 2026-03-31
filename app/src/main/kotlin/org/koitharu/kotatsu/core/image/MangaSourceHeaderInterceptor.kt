package org.haziffe.dropsauce.core.image

import coil3.intercept.Interceptor
import coil3.network.httpHeaders
import coil3.request.ImageResult
import org.haziffe.dropsauce.core.model.unwrap
import org.haziffe.dropsauce.core.network.CommonHeaders
import org.haziffe.dropsauce.core.util.ext.mangaSourceKey
import org.haziffe.dropsauce.parsers.model.MangaParserSource

class MangaSourceHeaderInterceptor : Interceptor {

	override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
		val mangaSource = chain.request.extras[mangaSourceKey]?.unwrap() as? org.haziffe.dropsauce.parsers.model.MangaSource ?: return chain.proceed()
		val request = chain.request
		val newHeaders = request.httpHeaders.newBuilder()
			.set(CommonHeaders.MANGA_SOURCE, mangaSource.name)
			.build()
		val newRequest = request.newBuilder()
			.httpHeaders(newHeaders)
			.build()
		return chain.withRequest(newRequest).proceed()
	}
}
