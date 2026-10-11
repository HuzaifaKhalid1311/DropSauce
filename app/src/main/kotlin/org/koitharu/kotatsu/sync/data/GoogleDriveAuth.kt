package org.koitharu.kotatsu.sync.data

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import org.koitharu.kotatsu.sync.domain.SyncApiException
import org.koitharu.kotatsu.sync.domain.SyncSignInRequiredException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google authorization for Drive sync via the Identity [com.google.android.gms.auth.api.identity.AuthorizationClient]:
 * an interactive request (account chooser + consent) when connecting, silent requests for the
 * access tokens background sync needs afterwards. The account is pinned to the stored email.
 */
@Singleton
class GoogleDriveAuth @Inject constructor(
	@ApplicationContext context: Context,
	private val syncSettings: SyncSettings,
) {

	private val client = Identity.getAuthorizationClient(context)

	private val account: Account?
		get() = syncSettings.accountEmail?.takeIf { '@' in it }?.let { Account(it, ACCOUNT_TYPE) }

	/** Asks for Drive access plus the email/profile needed to show who is signed in. */
	suspend fun authorize(): AuthorizationResult = client.authorize(
		AuthorizationRequest.builder().setRequestedScopes(SIGN_IN_SCOPES).build(),
	).await()

	/** Parses the result of the consent screen from [AuthorizationResult.getPendingIntent]. */
	fun resultFromIntent(data: Intent?): AuthorizationResult = client.getAuthorizationResultFromIntent(data)

	suspend fun signOut() {
		val account = account ?: return
		// Revoke so the next sign-in prompts for consent / a different account.
		runCatching {
			client.revokeAccess(RevokeAccessRequest.builder().setAccount(account).setScopes(SIGN_IN_SCOPES).build())
				.await()
		}.onFailure { Log.w(TAG, "revoke failed", it) }
	}

	/** Returns a valid OAuth2 access token for the Drive scopes, or throws [SyncSignInRequiredException]. */
	suspend fun requireAccessToken(): String {
		val request = AuthorizationRequest.builder()
			.setRequestedScopes(DRIVE_SCOPES)
			.apply { account?.let { setAccount(it) } }
			.build()
		val result = try {
			client.authorize(request).await()
		} catch (e: ApiException) {
			// Offline is a normal, retryable failure — not a reason to ask the user to sign in again.
			if (e.statusCode == CommonStatusCodes.NETWORK_ERROR) throw IOException(e.message, e)
			throw SyncSignInRequiredException(e.message ?: "Authorization required")
		}
		if (result.hasResolution()) throw SyncSignInRequiredException()
		return result.accessToken ?: throw SyncSignInRequiredException()
	}

	/** Runs [block] with an access token; a cached token the server rejects (401) is refreshed once. */
	suspend fun <T> withToken(block: suspend (token: String) -> T): T {
		val token = requireAccessToken()
		return try {
			block(token)
		} catch (e: SyncApiException) {
			if (e.code != 401) throw e
			Log.w(TAG, "token rejected (401), refreshing")
			runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
			block(requireAccessToken())
		}
	}

	private companion object {

		const val TAG = "GDriveSync"
		const val ACCOUNT_TYPE = "com.google"
		const val SCOPE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
		val DRIVE_SCOPES = listOf(Scope(SCOPE_APPDATA))
		val SIGN_IN_SCOPES = DRIVE_SCOPES + Scope(Scopes.EMAIL) + Scope(Scopes.PROFILE)
	}
}
