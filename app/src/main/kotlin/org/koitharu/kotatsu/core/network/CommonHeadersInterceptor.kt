package org.haziffe.dropsauce.core.network

import dagger.Lazy
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Interceptor.Chain
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import org.haziffe.dropsauce.BuildConfig
import org.haziffe.dropsauce.core.model.MangaSource
import org.haziffe.dropsauce.core.parser.MangaLoaderContextImpl
import org.haziffe.dropsauce.core.parser.MangaRepository
import org.haziffe.dropsauce.core.parser.ParserMangaRepository
import org.haziffe.dropsauce.core.util.ext.printStackTraceDebug
import org.haziffe.dropsauce.parsers.model.MangaParserSource
import org.haziffe.dropsauce.parsers.model.MangaSource
import org.haziffe.dropsauce.parsers.util.mergeWith
import org.haziffe.dropsauce.parsers.util.runCatchingCancellable
import org.haziffe.dropsauce.mihon.MihonMangaRepository
import eu.kanade.tachiyomi.source.online.HttpSource
import java.net.IDN
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
            is ParserMangaRepository -> repository.getRequestHeaders()
            is MihonMangaRepository -> (repository.mihonSource as? HttpSource)?.headers
            else -> null
        }
        
		requestHeaders?.let {
			headersBuilder.mergeWith(it, replaceExisting = false)
		}
		if (headersBuilder[CommonHeaders.USER_AGENT] == null) {
			headersBuilder[CommonHeaders.USER_AGENT] = mangaLoaderContextLazy.get().getDefaultUserAgent()
		}
		if (headersBuilder[CommonHeaders.REFERER] == null && repository is ParserMangaRepository) {
			val idn = IDN.toASCII(repository.domain)
			headersBuilder.trySet(CommonHeaders.REFERER, "https://$idn/")
		}
		val newRequest = request.newBuilder().headers(headersBuilder.build()).build()
		val targetInterceptor = repository as? ParserMangaRepository
		return targetInterceptor?.interceptSafe(ProxyChain(chain, newRequest)) ?: chain.proceed(newRequest)
	}

	private fun Headers.Builder.trySet(name: String, value: String) = try {
		set(name, value)
	} catch (e: IllegalArgumentException) {
		e.printStackTraceDebug()
	}

	private fun Interceptor.interceptSafe(chain: Chain): Response = runCatchingCancellable {
		intercept(chain)
	}.getOrElse { e ->
		if (e is IOException || e is Error) {
			throw e
		} else {
			// only IOException can be safely thrown from an Interceptor
			throw IOException("Error in interceptor: ${e.message}", e)
		}
	}

	private class ProxyChain(
		private val delegate: Chain,
		private val request: Request,
	) : Chain by delegate {

		override fun request(): Request = request
	}
}
