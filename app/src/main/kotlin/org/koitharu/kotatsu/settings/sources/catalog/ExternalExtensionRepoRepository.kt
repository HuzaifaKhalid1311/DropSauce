package org.koitharu.kotatsu.settings.sources.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.network.BaseHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExternalExtensionRepoRepository @Inject constructor(
	@BaseHttpClient private val okHttpClient: OkHttpClient,
) {

	private val json = Json {
		ignoreUnknownKeys = true
	}

	suspend fun getExtensions(repoUrl: String, forceRefresh: Boolean = false): List<ExternalExtensionRepoEntry> = withContext(Dispatchers.IO) {
		val indexUrl = buildIndexUrl(repoUrl)
		val requestBuilder = Request.Builder()
			.url(indexUrl)
			.get()
		
		if (forceRefresh) {
			requestBuilder.cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
		}
		
		val request = requestBuilder.build()
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

	/**
	 * Resolves the APK download URL for an extension.
	 * Follows the Mihon convention: APKs are stored at `${baseRepoUrl}/apk/${apkName}`.
	 * If [apkName] is already an absolute URL it is returned unchanged.
	 */
	fun resolveApkUrl(repoUrl: String, apkName: String): String {
		if (apkName.startsWith("http://") || apkName.startsWith("https://")) {
			return apkName
		}
		val base = getBaseUrl(repoUrl)
		return "$base/apk/$apkName"
	}

	/**
	 * Resolves the icon URL for an extension package.
	 * Icons are stored at `${baseRepoUrl}/icon/${packageName}.png`.
	 */
	fun resolveIconUrl(repoUrl: String, packageName: String): String {
		val base = getBaseUrl(repoUrl)
		return "$base/icon/$packageName.png"
	}

	/**
	 * Ensures the repo URL points to the index.min.json file.
	 * Accepts both the base URL and the full index URL.
	 */
	private fun buildIndexUrl(repoUrl: String): String {
		val base = repoUrl.trimEnd('/')
		return if (base.endsWith(".json")) base else "$base/index.min.json"
	}

	/**
	 * Returns the base repo URL (without the trailing /index.min.json or other filename).
	 */
	private fun getBaseUrl(repoUrl: String): String {
		val trimmed = repoUrl.trimEnd('/')
		return if (trimmed.endsWith(".json")) {
			trimmed.substringBeforeLast('/')
		} else {
			trimmed
		}
	}
}
