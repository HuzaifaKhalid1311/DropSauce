package org.koitharu.kotatsu.core.network

import dagger.Lazy
import okhttp3.Interceptor
import okhttp3.Interceptor.Chain
import okhttp3.Response
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.parser.MangaLoaderContextImpl
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.mergeWith
import org.koitharu.kotatsu.mihon.MihonMangaRepository
import eu.kanade.tachiyomi.source.online.HttpSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommonHeadersInterceptor @Inject constructor(
	private val mangaRepositoryFactoryLazy: Lazy<MangaRepository.Factory>,
	private val mangaLoaderContextLazy: Lazy<MangaLoaderContextImpl>,
) : Interceptor {

	override fun intercept(chain: Chain): Response {
		val request = chain.request()
		val source = request.tag(MangaSource::class.java)
			?: request.headers[CommonHeaders.MANGA_SOURCE]?.let { MangaSource(it) }
		val repository = if (source != null) {
			mangaRepositoryFactoryLazy.get().create(source)
		} else {
			if (BuildConfig.DEBUG) {
				IllegalArgumentException("Request without source tag: ${request.url}")
					.printStackTrace()
			}
			null
		}
		val headersBuilder = request.headers.newBuilder()
			.removeAll(CommonHeaders.MANGA_SOURCE)
			
		val requestHeaders = when (repository) {
            is MihonMangaRepository -> (repository.mihonSource as? HttpSource)?.headers
            else -> null
        }
        
		requestHeaders?.let {
			headersBuilder.mergeWith(it, replaceExisting = false)
		}
		if (headersBuilder[CommonHeaders.USER_AGENT] == null) {
			headersBuilder[CommonHeaders.USER_AGENT] = mangaLoaderContextLazy.get().getDefaultUserAgent()
		}
		val newRequest = request.newBuilder().headers(headersBuilder.build()).build()
		return chain.proceed(newRequest)
	}
}
