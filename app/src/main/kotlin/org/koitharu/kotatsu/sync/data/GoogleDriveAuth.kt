@file:Suppress("DEPRECATION")

package org.koitharu.kotatsu.sync.data

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.sync.domain.SyncApiException
import org.koitharu.kotatsu.sync.domain.SyncSignInRequiredException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google authentication for Drive sync, using the classic GoogleSignIn (account chooser + scope
 * consent) plus [GoogleAuthUtil] for minting OAuth2 access tokens. This is more reliable for direct
 * Drive REST calls than the newer Identity AuthorizationClient, whose tokens were being rejected.
 */
@Singleton
class GoogleDriveAuth @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	private val signInClient by lazy {
		val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
			.requestEmail()
			.requestScopes(Scope(SCOPE_APPDATA))
			.build()
		GoogleSignIn.getClient(context, options)
	}

	/** Intent that launches the Google account chooser and Drive scope consent. */
	val signInIntent: Intent
		get() = signInClient.signInIntent

	/** Parses the signed-in account from the sign-in result; throws [ApiException] on failure. */
	fun accountFromIntent(data: Intent?): GoogleSignInAccount =
		GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)

	fun lastAccount(): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)

	suspend fun signOut() {
		runCatching { signInClient.signOut().await() }
		// revokeAccess so the next sign-in prompts for consent / a different account.
		runCatching { signInClient.revokeAccess().await() }
	}

	/** Returns a valid OAuth2 access token for the Drive scopes, or throws [SyncSignInRequiredException]. */
	suspend fun requireAccessToken(): String = withContext(Dispatchers.IO) {
		val account = lastAccount()?.account ?: throw SyncSignInRequiredException()
		try {
			GoogleAuthUtil.getToken(context, account, OAUTH2_SCOPE)
		} catch (e: UserRecoverableAuthException) {
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
			runCatching { GoogleAuthUtil.clearToken(context, token) }
			block(requireAccessToken())
		}
	}

	private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
		addOnSuccessListener { cont.resume(it) }
		addOnFailureListener { cont.resumeWithException(it) }
		addOnCanceledListener { cont.cancel() }
	}

	companion object {

		private const val TAG = "GDriveSync"
		const val SCOPE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
		private const val OAUTH2_SCOPE = "oauth2:$SCOPE_APPDATA"
	}
}
