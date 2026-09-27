package org.koitharu.kotatsu.sync.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.sync.domain.SyncApiException
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal Google Drive v3 REST client for the hidden `appDataFolder` the sync data lives in.
 * Transient failures (network, 429, 5xx) are retried with backoff; 401 is surfaced so the caller can
 * refresh the token.
 */
@Singleton
class GoogleDriveApi @Inject constructor(
	@BaseHttpClient private val baseHttpClient: dagger.Lazy<OkHttpClient>,
) {

	// The manga-oriented interceptors (Cloudflare, rate limit, gzip) and the cache interfere with
	// these calls, so start from a clean client. No overall call timeout: a replica can be large, and
	// read/write timeouts already catch a stalled transfer. Lazy because screens build this class on
	// the main thread, where the base client must not be created.
	private val httpClient by lazy {
		buildClient()
	}

	private fun buildClient() = baseHttpClient.get().newBuilder().apply {
		interceptors().clear()
		networkInterceptors().clear()
		cache(null)
		connectTimeout(20, TimeUnit.SECONDS)
		readTimeout(60, TimeUnit.SECONDS)
		writeTimeout(60, TimeUnit.SECONDS)
		callTimeout(0, TimeUnit.SECONDS)
	}.build()

	private val json = Json { ignoreUnknownKeys = true }

	@Serializable
	class DriveFile(
		@SerialName("id") val id: String,
		@SerialName("name") val name: String = "",
		@SerialName("modifiedTime") val modifiedTime: String? = null,
		@SerialName("md5Checksum") val md5: String? = null,
	) {

		val modifiedAt: Long
			get() = modifiedTime?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
	}

	@Serializable
	private class FileList(
		@SerialName("files") val files: List<DriveFile> = emptyList(),
		@SerialName("nextPageToken") val nextPageToken: String? = null,
	)

	/** Every file in the hidden app folder. */
	suspend fun listAppData(token: String): List<DriveFile> {
		val result = ArrayList<DriveFile>()
		var pageToken: String? = null
		do {
			val url = "$DRIVE_BASE/files".toHttpUrl().newBuilder()
				.addQueryParameter("spaces", APP_DATA)
				.addQueryParameter("q", "trashed = false")
				.addQueryParameter("fields", "nextPageToken,files($FILE_FIELDS)")
				.addQueryParameter("pageSize", "1000")
				.apply { pageToken?.let { addQueryParameter("pageToken", it) } }
				.build()
			val page = execute(Request.Builder().url(url).get().authorize(token).build()) { it.parse<FileList>() }
			result += page.files
			pageToken = page.nextPageToken
		} while (pageToken != null)
		return result
	}

	suspend fun download(token: String, fileId: String): ByteArray {
		val request = Request.Builder().url(mediaUrl(fileId)).get().authorize(token).build()
		return execute(request) { it.body.bytes() }
	}

	/** Creates a file in the app folder and returns it with its md5. */
	suspend fun create(
		token: String,
		name: String,
		content: RequestBody,
	): DriveFile {
		val metadata = buildJsonObject {
			put("name", name)
			put("parents", buildJsonArray { add(JsonPrimitive(APP_DATA)) })
		}.toString()
		return resumableUpload(token, "$UPLOAD_BASE/files".toHttpUrl(), "POST", metadata, content)
	}

	/** Replaces the content of [fileId]; returns the updated file with its new md5. */
	suspend fun update(token: String, fileId: String, content: RequestBody): DriveFile {
		val length = content.contentLength()
		if (length in 0..SIMPLE_UPLOAD_LIMIT) {
			val url = "$UPLOAD_BASE/files/$fileId".toHttpUrl().newBuilder()
				.addQueryParameter("uploadType", "media")
				.addQueryParameter("fields", FILE_FIELDS)
				.build()
			val request = Request.Builder().url(url).patch(content).authorize(token).build()
			return execute(request) { it.parse<DriveFile>() }
		}
		return resumableUpload(token, "$UPLOAD_BASE/files/$fileId".toHttpUrl(), "PATCH", "{}", content)
	}

	suspend fun delete(token: String, fileId: String) {
		val request = Request.Builder().url("$DRIVE_BASE/files/$fileId").delete().authorize(token).build()
		try {
			execute(request) { }
		} catch (e: SyncApiException) {
			if (e.code != 404) throw e // already gone
		}
	}

	/** Two-step upload that works for any size; the whole body is re-sent on retry. */
	private suspend fun resumableUpload(
		token: String,
		baseUrl: HttpUrl,
		method: String,
		metadata: String,
		content: RequestBody,
	): DriveFile {
		val initUrl = baseUrl.newBuilder()
			.addQueryParameter("uploadType", "resumable")
			.addQueryParameter("fields", FILE_FIELDS)
			.build()
		val init = Request.Builder()
			.url(initUrl)
			.method(method, metadata.toRequestBody(JSON_MEDIA_TYPE))
			.header("X-Upload-Content-Type", OCTET_STREAM.toString())
			.authorize(token)
			.build()
		val session = execute(init) { response ->
			response.header("Location") ?: throw SyncApiException(response.code, "Drive returned no upload session")
		}
		val upload = Request.Builder().url(session).put(content).build()
		return execute(upload) { it.parse<DriveFile>() }
	}

	private fun mediaUrl(fileId: String) = "$DRIVE_BASE/files/$fileId".toHttpUrl().newBuilder()
		.addQueryParameter("alt", "media")
		.build()

	private suspend fun <T> execute(request: Request, block: (Response) -> T): T {
		var attempt = 0
		while (true) {
			val failure: IOException = try {
				return withContext(Dispatchers.IO) {
					httpClient.newCall(request).execute().use { response ->
						if (!response.isSuccessful) throw response.toError()
						block(response)
					}
				}
			} catch (e: SyncApiException) {
				if (e.code != 429 && e.code < 500) throw e
				e
			} catch (e: IOException) {
				e
			}
			if (++attempt >= MAX_ATTEMPTS) throw failure
			delay(RETRY_DELAY_MS shl (attempt - 1))
		}
	}

	private fun Request.Builder.authorize(token: String) = header("Authorization", "Bearer $token")

	private inline fun <reified T> Response.parse(): T = json.decodeFromString(body.string())

	private fun Response.toError(): SyncApiException {
		val details = runCatching { body.string() }.getOrNull()?.takeIf { it.isNotBlank() } ?: message
		return SyncApiException(code, "Drive API error $code: $details")
	}

	companion object {

		private const val APP_DATA = "appDataFolder"
		private const val FILE_FIELDS = "id,name,modifiedTime,md5Checksum"
		private const val DRIVE_BASE = "https://www.googleapis.com/drive/v3"
		private const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
		private const val SIMPLE_UPLOAD_LIMIT = 5L * 1024 * 1024
		private const val MAX_ATTEMPTS = 3
		private const val RETRY_DELAY_MS = 1500L
		private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
		private val OCTET_STREAM = "application/octet-stream".toMediaType()

		fun body(bytes: ByteArray): RequestBody = bytes.toRequestBody(OCTET_STREAM)
	}
}
