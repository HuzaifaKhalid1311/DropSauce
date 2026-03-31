package org.haziffe.dropsauce.sync.data

import dagger.Reusable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.haziffe.dropsauce.core.exceptions.SyncApiException
import org.haziffe.dropsauce.core.network.BaseHttpClient
import org.haziffe.dropsauce.core.util.ext.toRequestBody
import org.haziffe.dropsauce.parsers.util.await
import org.haziffe.dropsauce.parsers.util.parseJson
import org.haziffe.dropsauce.parsers.util.parseRaw
import org.haziffe.dropsauce.parsers.util.removeSurrounding
import javax.inject.Inject

@Reusable
class SyncAuthApi @Inject constructor(
	@BaseHttpClient private val okHttpClient: OkHttpClient,
) {

	suspend fun authenticate(syncURL: String, email: String, password: String): String {
		val body = JSONObject(
			mapOf("email" to email, "password" to password),
		).toRequestBody()
		val request = Request.Builder()
			.url("$syncURL/auth")
			.post(body)
			.build()
		val response = okHttpClient.newCall(request).await()
		if (response.isSuccessful) {
			return response.parseJson().getString("token")
		} else {
			val code = response.code
			val message = response.parseRaw().removeSurrounding('"')
			throw SyncApiException(message, code)
		}
	}
}
