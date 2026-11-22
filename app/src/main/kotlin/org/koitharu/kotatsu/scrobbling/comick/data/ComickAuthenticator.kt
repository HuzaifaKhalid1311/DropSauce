package org.koitharu.kotatsu.scrobbling.comick.data

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerStorage
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerType
import javax.inject.Inject
import javax.inject.Provider

/** Comick access tokens last 15 minutes, so a 401 usually just means it's time to refresh. */
class ComickAuthenticator @Inject constructor(
	@ScrobblerType(ScrobblerService.COMICK) private val storage: ScrobblerStorage,
	private val repositoryProvider: Provider<ComickRepository>,
) : Authenticator {

	override fun authenticate(route: Route?, response: Response): Request? {
		// Refresh at most once per request: a refreshed token that still fails needs a new sign-in
		if (response.priorResponse != null) return null
		val header = response.request.header(CommonHeaders.AUTHORIZATION)
		if (header?.startsWith("Bearer ") != true) return null
		val sentToken = header.removePrefix("Bearer ")
		// Refresh tokens rotate, so only one refresh may run at a time
		synchronized(this) {
			val storedToken = storage.accessToken ?: return null
			// Another request already refreshed while this one was in flight
			if (storedToken != sentToken) {
				return response.request.withToken(storedToken)
			}
			val refreshed = runCatching {
				runBlocking { repositoryProvider.get().authorize(null) }
				storage.accessToken
			}.onFailure {
				it.printStackTraceDebug()
			}.getOrNull() ?: return null
			return response.request.withToken(refreshed)
		}
	}

	private fun Request.withToken(token: String) = newBuilder()
		.header(CommonHeaders.AUTHORIZATION, "Bearer $token")
		.build()
}
