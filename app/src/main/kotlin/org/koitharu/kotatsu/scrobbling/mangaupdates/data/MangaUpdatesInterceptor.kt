package org.koitharu.kotatsu.scrobbling.mangaupdates.data

import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerStorage
import org.koitharu.kotatsu.scrobbling.common.domain.ScrobblerAuthRequiredException
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import java.io.IOException
import java.net.HttpURLConnection

private const val ERROR_BODY_MAX = 500L

/** MangaUpdates answered with an error status; [code] is its HTTP status. */
class MangaUpdatesException(val code: Int, message: String) : IOException(message)

class MangaUpdatesInterceptor(private val storage: ScrobblerStorage) : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val sourceRequest = chain.request()
		val isLogin = sourceRequest.url.pathSegments.contains("login")
		val request = sourceRequest.newBuilder()
		request.header(CommonHeaders.ACCEPT, "application/json")
		if (!isLogin) {
			storage.accessToken?.let {
				request.header(CommonHeaders.AUTHORIZATION, "Bearer $it")
			}
		}
		val response = chain.proceed(request.build())
		// 404 is how the API says "not on your list" / "not rated", which the repository handles itself
		if (response.isSuccessful || response.code == HttpURLConnection.HTTP_NOT_FOUND) {
			return response
		}
		val message = response.use { errorMessage(it) }
		if (!isLogin && response.code == HttpURLConnection.HTTP_UNAUTHORIZED) {
			// A session token never refreshes, so an expired one means signing in again. Dropping it makes the service count as signed out, which is what sends the user to the login screen.
			storage.clear()
			throw ScrobblerAuthRequiredException(ScrobblerService.MANGAUPDATES)
		}
		throw MangaUpdatesException(response.code, "MangaUpdates: $message")
	}

	private fun errorMessage(response: Response): String {
		val body = response.peekBody(ERROR_BODY_MAX).string()
		val reason = runCatching { JSONObject(body).getStringOrNull("reason") }.getOrNull()
		return reason ?: body.ifBlank { response.message.ifBlank { "HTTP ${response.code}" } }
	}
}
