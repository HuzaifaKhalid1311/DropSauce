package org.koitharu.kotatsu.sync.data

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.sync.domain.SyncApiException
import org.koitharu.kotatsu.sync.domain.SyncSignInRequiredException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google authorization for Drive sync via the Identity [com.google.android.gms.auth.api.identity.AuthorizationClient]:
 * an interactive request (account chooser + consent) when connecting, silent requests for the
 * access tokens background sync needs afterwards. Play Services keeps the account picked when
 * connecting as the app's default, so silent requests don't pin an account first: the profile email
 * stays the old address after a Gmail address change while the phone's account is renamed.
 */
@Singleton
class GoogleDriveAuth @Inject constructor(
	@ApplicationContext context: Context,
	private val syncSettings: SyncSettings,
	private val api: GoogleDriveApi,
) {

	private val client = Identity.getAuthorizationClient(context)

	/**
	 * Asks for Drive access plus the email/profile needed to show who is signed in.
	 * [AuthorizationRequest.Prompt.SELECT_ACCOUNT] forces the account chooser even when a previous
	 * grant is still active (e.g. after an offline sign-out or with multiple authorized accounts).
	 */
	suspend fun authorize(): AuthorizationResult = client.authorize(
		AuthorizationRequest.builder()
			.setRequestedScopes(SCOPES)
			.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
			.build(),
	).await()

	/** Parses the result of the consent screen from [AuthorizationResult.getPendingIntent]. */
	fun resultFromIntent(data: Intent?): AuthorizationResult = client.getAuthorizationResultFromIntent(data)

	suspend fun clearToken(token: String) {
		runCatchingCancellable {
			client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
		}.onFailure { Log.w(TAG, "clearToken failed", it) }
	}

	/** Revokes the app's access and evicts the cached token so a reconnect starts clean. */
	suspend fun signOut(fallbackEmail: String? = syncSettings.accountEmail) {
		withContext(NonCancellable) {
			runCatchingCancellable {
				val token = requireAccessToken(fallbackEmail)
				try {
					api.revoke(token)
				} finally {
					// Or a later connect to the same account would get the revoked token back from the cache.
					clearToken(token)
				}
			}.onFailure { Log.w(TAG, "revoke failed", it) }
		}
	}

	/** Returns a valid OAuth2 access token for [SCOPES], or throws [SyncSignInRequiredException]. */
	suspend fun requireAccessToken(fallbackEmail: String? = syncSettings.accountEmail): String {
		val result = authorizeSilently(account = null)
		if (!result.hasResolution()) {
			return result.accessToken ?: throw SyncSignInRequiredException()
		}
		// Pre-1.1.0 sign-ins used legacy GoogleSignInClient, which never saved a default account in
		// AuthorizationClient; try the stored address once when unpinned lookup has none. For a renamed
		// Gmail account that doesn't exist in AccountManager, that fails too and still asks to sign in.
		val fallbackAccount = fallbackEmail?.takeIf { '@' in it }?.let { Account(it, ACCOUNT_TYPE) }
			?: throw SyncSignInRequiredException()
		val fallback = runCatchingCancellable { authorizeSilently(fallbackAccount) }.getOrElse { e ->
			if (e is IOException && e !is SyncSignInRequiredException) throw e
			throw SyncSignInRequiredException(e.message ?: "Authorization required")
		}
		if (fallback.hasResolution()) throw SyncSignInRequiredException()
		return fallback.accessToken ?: throw SyncSignInRequiredException()
	}

	private suspend fun authorizeSilently(account: Account?): AuthorizationResult {
		val request = AuthorizationRequest.builder()
			.setRequestedScopes(SCOPES)
			.apply { account?.let { setAccount(it) } }
			.build()
		return try {
			client.authorize(request).await()
		} catch (e: ApiException) {
			// Offline is a normal, retryable failure — not a reason to ask the user to sign in again.
			if (e.statusCode == CommonStatusCodes.NETWORK_ERROR) throw IOException(e.message, e)
			throw SyncSignInRequiredException(e.message ?: "Authorization required")
		}
	}

	/** Runs [block] with an access token; a cached token the server rejects (401) is refreshed once. */
	suspend fun <T> withToken(block: suspend (token: String) -> T): T {
		val token = requireAccessToken()
		return try {
			block(token)
		} catch (e: SyncApiException) {
			if (e.code != 401) throw e
			Log.w(TAG, "token rejected (401), refreshing")
			clearToken(token)
			block(requireAccessToken())
		}
	}

	private companion object {

		const val TAG = "GDriveSync"
		const val ACCOUNT_TYPE = "com.google"
		const val SCOPE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"

		// One scope list everywhere so Play Services caches a single token for connect, sync, and clearToken.
		val SCOPES = listOf(Scope(SCOPE_APPDATA), Scope(Scopes.EMAIL), Scope(Scopes.PROFILE))
	}
}
