package org.koitharu.kotatsu.sync.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.encodeToStream
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.sync.domain.SyncApiException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal Google Drive v3 REST client scoped to the hidden `appDataFolder`. Stores a single plain
 * JSON snapshot. Uses the app's shared OkHttp client (stripped of manga-specific interceptors);
 * auth tokens come from [GoogleDriveAuth].
 */
@Singleton
class GoogleDriveApi @Inject constructor(
	@BaseHttpClient baseHttpClient: OkHttpClient,
) {

	// A clean client for Google APIs: reuse the base DNS/proxy/timeouts but drop the manga-oriented
	// interceptors (Cloudflare, rate-limit, gzip) and the response cache, which can interfere with
	// these REST calls (e.g. returning stale results or mangling request/response bodies).
	private val httpClient = baseHttpClient.newBuilder().apply {
		interceptors().clear()
		networkInterceptors().clear()
		cache(null)
		connectTimeout(15, TimeUnit.SECONDS)
		readTimeout(30, TimeUnit.SECONDS)
		writeTimeout(30, TimeUnit.SECONDS)
		callTimeout(60, TimeUnit.SECONDS)
	}.build()

	private val json = Json { ignoreUnknownKeys = true }

	@Serializable
	class DriveFile(
		@SerialName("id") val id: String,
		@SerialName("name") val name: String? = null,
		@SerialName("modifiedTime") val modifiedTime: String? = null,
		@SerialName("createdTime") val createdTime: String? = null,
		// Drive's monotonically-increasing change counter; bumps on every content/metadata write. Used
		// as an optimistic-concurrency token to detect a concurrent write from another device. Drive
		// encodes int64 fields as JSON strings, so this is a String and only ever compared for equality.
		@SerialName("version") val version: String? = null,
		@SerialName("etag") val etag: String? = null,
	)

	@Serializable
	class FileVersion(
		@SerialName("version") val version: String? = null,
		@SerialName("etag") val etag: String? = null,
	)

	@Serializable
	class DriveUser(
		@SerialName("displayName") val displayName: String? = null,
		@SerialName("emailAddress") val emailAddress: String? = null,
		@SerialName("photoLink") val photoLink: String? = null,
	)

	@Serializable
	private class FileList(
		@SerialName("files") val files: List<DriveFile> = emptyList(),
		@SerialName("nextPageToken") val nextPageToken: String? = null,
	)

	@Serializable
	private class AboutResponse(@SerialName("user") val user: DriveUser? = null)

	@Serializable
	private class IdResponse(@SerialName("id") val id: String)

	/** Returns the signed-in user's profile, or null if it can't be resolved. */
	suspend fun getUser(token: String): DriveUser? = withContext(Dispatchers.IO) {
		val url = "$DRIVE_BASE/about".toHttpUrl().newBuilder()
			.addQueryParameter("fields", "user")
			.build()
		val request = Request.Builder().url(url).get().authorize(token).build()
		httpClient.newCall(request).execute().parse<AboutResponse>()?.user
	}

	/**
	 * Lists all sync files in appDataFolder, oldest first. Normally there is exactly one, but a
	 * first-run race between two devices can create duplicates; the caller merges and de-duplicates
	 * them. Trashed files are excluded so a not-yet-purged delete can't shadow the live file.
	 */
	suspend fun findSyncFiles(token: String): List<DriveFile> = withContext(Dispatchers.IO) {
		val files = mutableListOf<DriveFile>()
		var pageToken: String? = null
		do {
			val url = "$DRIVE_BASE/files".toHttpUrl().newBuilder().apply {
				addQueryParameter("spaces", "appDataFolder")
				addQueryParameter("q", "name = '$FILE_NAME' and trashed = false")
				addQueryParameter("fields", "nextPageToken,files(id,name,modifiedTime,createdTime,version,etag)")
				addQueryParameter("orderBy", "createdTime")
				addQueryParameter("pageSize", "100")
				if (pageToken != null) {
					addQueryParameter("pageToken", pageToken)
				}
			}.build()
			val request = Request.Builder().url(url).get().authorize(token).build()
			val response = httpClient.newCall(request).execute().parse<FileList>()
			if (response != null) {
				files.addAll(response.files)
				pageToken = response.nextPageToken
			} else {
				pageToken = null
			}
		} while (pageToken != null)
		files
	}

	/** Reads just the current [DriveFile.version] of a file, for a pre-upload concurrency re-check. */
	suspend fun getFileVersion(token: String, fileId: String): FileVersion? = withContext(Dispatchers.IO) {
		val url = "$DRIVE_BASE/files/$fileId".toHttpUrl().newBuilder()
			.addQueryParameter("fields", "version,etag")
			.build()
		val request = Request.Builder().url(url).get().authorize(token).build()
		httpClient.newCall(request).execute().parse<FileVersion>()
	}

	/** Downloads the raw sync file content (plain JSON bytes). */
	suspend fun <T> download(token: String, fileId: String, block: (okio.BufferedSource) -> T): T = withContext(Dispatchers.IO) {
		val url = "$DRIVE_BASE/files/$fileId".toHttpUrl().newBuilder()
			.addQueryParameter("alt", "media")
			.build()
		val request = Request.Builder().url(url).get().authorize(token).build()
		httpClient.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw response.toError()
			block(response.body.source())
		}
	}

	/**
	 * Uploads [snapshot] (plain JSON bytes). Creates the file in appDataFolder when [fileId] is null
	 * (a metadata-only create followed by a media upload — avoids the fragile multipart path),
	 * otherwise overwrites the existing file. Returns the file id.
	 */
	suspend fun upload(
		token: String,
		snapshot: org.koitharu.kotatsu.sync.data.model.SyncSnapshot,
		fileId: String?,
		expectedEtag: String? = null
	): String =
		withContext(Dispatchers.IO) {
			val targetId = fileId ?: createEmptyFile(token)
			val url = "$UPLOAD_BASE/files/$targetId".toHttpUrl().newBuilder()
				.addQueryParameter("uploadType", "media")
				.addQueryParameter("fields", "id")
				.build()

			val requestBody = object : okhttp3.RequestBody() {
				override fun contentType() = JSON_MEDIA_TYPE
				override fun writeTo(sink: okio.BufferedSink) {
					@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
					json.encodeToStream(org.koitharu.kotatsu.sync.data.model.SyncSnapshot.serializer(), snapshot, sink.outputStream())
				}
			}

			val request = Request.Builder()
				.url(url)
				.patch(requestBody)
				.authorize(token)
				.apply {
					if (expectedEtag != null) {
						val formattedEtag = if (expectedEtag.startsWith("\"")) expectedEtag else "\"$expectedEtag\""
						header("If-Match", formattedEtag)
					}
				}
				.build()
			httpClient.newCall(request).execute().parse<IdResponse>()?.id ?: targetId
		}

	private fun createEmptyFile(token: String): String {
		val metadata = """{"name":"$FILE_NAME","parents":["appDataFolder"]}"""
		val url = "$DRIVE_BASE/files".toHttpUrl().newBuilder()
			.addQueryParameter("fields", "id")
			.build()
		val request = Request.Builder()
			.url(url)
			.post(metadata.toRequestBody(JSON_MEDIA_TYPE))
			.authorize(token)
			.build()
		return httpClient.newCall(request).execute().parse<IdResponse>()?.id
			?: throw SyncApiException(0, "Failed to create sync file")
	}

	suspend fun delete(token: String, fileId: String) = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url("$DRIVE_BASE/files/$fileId")
			.delete()
			.authorize(token)
			.build()
		httpClient.newCall(request).execute().use { response ->
			// 404 means it's already gone — treat as success.
			if (!response.isSuccessful && response.code != 404) throw response.toError()
		}
	}

	/** Revokes the OAuth grant so the next sign-in re-prompts (and can pick a different account). */
	suspend fun revokeToken(token: String) = withContext(Dispatchers.IO) {
		val url = "https://oauth2.googleapis.com/revoke".toHttpUrl().newBuilder()
			.addQueryParameter("token", token)
			.build()
		val request = Request.Builder()
			.url(url)
			.post(ByteArray(0).toRequestBody(FORM_MEDIA_TYPE))
			.build()
		runCatching { httpClient.newCall(request).execute().close() }
		Unit
	}

	private fun Request.Builder.authorize(token: String) = header("Authorization", "Bearer $token")

	private inline fun <reified T> Response.parse(): T? = use { response ->
		if (!response.isSuccessful) throw response.toError()
		val text = response.body.string()
		if (text.isBlank()) null else json.decodeFromString<T>(text)
	}

	private fun Response.toError(): SyncApiException {
		val message = runCatching { body.string() }.getOrNull()?.takeIf { it.isNotBlank() } ?: message
		return SyncApiException(code, "Drive API error $code: $message")
	}

	private companion object {

		const val FILE_NAME = "dropsauce_sync.json"
		const val DRIVE_BASE = "https://www.googleapis.com/drive/v3"
		const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
		val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
		val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
	}
}
