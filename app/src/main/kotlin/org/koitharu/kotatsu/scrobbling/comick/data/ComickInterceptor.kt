package org.koitharu.kotatsu.scrobbling.comick.data

import okhttp3.Interceptor
import okhttp3.Response
import okio.IOException
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerStorage
import org.koitharu.kotatsu.scrobbling.common.domain.ScrobblerAuthRequiredException
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import java.net.HttpURLConnection

private const val ERROR_BODY_MAX = 300L

class ComickInterceptor(private val storage: ScrobblerStorage) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val sourceRequest = chain.request()
		val request = sourceRequest.newBuilder()
		request.header(CommonHeaders.ACCEPT, "application/json")
		val isAuthRequest = sourceRequest.url.pathSegments.contains("oauth2")
		if (!isAuthRequest) {
			storage.accessToken?.let {
				request.header(CommonHeaders.AUTHORIZATION, "Bearer $it")
			}
		}
		val response = chain.proceed(request.build())
		if (!isAuthRequest && response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
			response.close()
			throw ScrobblerAuthRequiredException(ScrobblerService.COMICK)
		}
		// 404 means "not in the library" to ComickRepository, everything else is a real failure
		if (!response.isSuccessful && response.code != HttpURLConnection.HTTP_NOT_FOUND) {
			val body = response.use { it.peekBody(ERROR_BODY_MAX).string() }
			throw IOException("Comick: ${response.code} ${body.ifBlank { response.message }}")
		}
		return response
	}
}
