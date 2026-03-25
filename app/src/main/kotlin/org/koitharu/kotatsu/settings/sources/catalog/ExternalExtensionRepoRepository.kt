package org.koitharu.kotatsu.settings.sources.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.network.BaseHttpClient
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExternalExtensionRepoRepository @Inject constructor(
	@BaseHttpClient private val okHttpClient: OkHttpClient,
) {

	private val json = Json {
		ignoreUnknownKeys = true
	}

	suspend fun getExtensions(repoUrl: String): List<ExternalExtensionRepoEntry> = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url(repoUrl)
			.get()
			.build()
		okHttpClient.newCall(request).execute().use { response ->
			if (!response.isSuccessful) {
				throw IllegalStateException("Unable to load repo: HTTP ${response.code}")
			}
			val body = response.body?.string().orEmpty()
			if (body.isBlank()) {
				emptyList()
			} else {
				json.decodeFromString<List<ExternalExtensionRepoEntry>>(body)
			}
		}
	}

	fun resolveApkUrl(repoUrl: String, apkName: String): String {
		return URL(URL(repoUrl), apkName).toString()
	}
}
