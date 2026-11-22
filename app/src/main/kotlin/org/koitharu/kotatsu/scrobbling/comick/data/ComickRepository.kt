package org.koitharu.kotatsu.scrobbling.comick.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.json.getStringOrNull
import org.koitharu.kotatsu.parsers.util.json.mapJSONNotNull
import org.koitharu.kotatsu.parsers.util.parseJson
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerRepository
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerStorage
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

// Comick only accepts reverse-domain schemes without a host (`scheme:/path`) for native apps
private const val REDIRECT_URI = "org.haziffe.dropsauce:/comick-auth"
private const val OAUTH_URL = "https://comick.dev/api/auth/oauth2"
private const val API_URL = "https://api.comick.dev/integrations/v1"
private const val LIBRARY_WRITE_SCOPE = "library:write"
private const val SCOPES = "library:read $LIBRARY_WRITE_SCOPE offline_access"
private const val SEARCH_LIMIT = 20
private const val SEARCH_QUERY_MAX = 100
private const val RATING_MAX = 10

/** Comick's library statuses by their API number, named as the API's `status_name`. */
private val STATUSES = listOf("reading", "completed", "on_hold", "dropped", "plan_to_read")

@Singleton
class ComickRepository @Inject constructor(
	@ApplicationContext private val context: Context,
	@ScrobblerType(ScrobblerService.COMICK) private val okHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.COMICK) private val storage: ScrobblerStorage,
	private val db: MangaDatabase,
) : ScrobblerRepository {

	private val clientId = context.getString(R.string.comick_clientId)

	private val codeVerifier: String by lazy { randomBase64Url(32) }

	override val oauthUrl: String
		get() = "$OAUTH_URL/authorize".toHttpUrl().newBuilder()
			.addQueryParameter("response_type", "code")
			.addQueryParameter("client_id", clientId)
			.addQueryParameter("redirect_uri", REDIRECT_URI)
			.addQueryParameter("scope", SCOPES)
			.addQueryParameter("resource", API_URL)
			// shortcut: state is sent but not checked on the way back (the config screen only reads `code`); check it if login CSRF ever matters
			.addQueryParameter("state", randomBase64Url(16))
			.addQueryParameter("code_challenge", codeVerifier.toS256Challenge())
			.addQueryParameter("code_challenge_method", "S256")
			.build()
			.toString()

	override val isAuthorized: Boolean
		get() = storage.accessToken != null

	override val cachedUser: ScrobblerUser?
		get() = storage.user

	override suspend fun authorize(code: String?) {
		val body = FormBody.Builder()
			.add("client_id", clientId)
			.add("resource", API_URL)
		if (code != null) {
			body.add("grant_type", "authorization_code")
			body.add("code", code)
			body.add("redirect_uri", REDIRECT_URI)
			body.add("code_verifier", codeVerifier)
		} else {
			body.add("grant_type", "refresh_token")
			body.add("refresh_token", checkNotNull(storage.refreshToken) { "Not authorized" })
		}
		val request = Request.Builder().post(body.build()).url("$OAUTH_URL/token").build()
		val response = okHttp.newCall(request).await().parseJson()
		if (code != null) {
			// Both are optional on Comick's consent page, but tracking can't work without them
			val scope = response.getStringOrNull("scope").orEmpty()
			check(LIBRARY_WRITE_SCOPE in scope && response.has("refresh_token")) {
				context.getString(R.string.comick_permissions_required)
			}
		}
		// Refresh tokens rotate and the old one dies at once, so the new one is saved first
		storage.refreshToken = response.getStringOrNull("refresh_token") ?: storage.refreshToken
		storage.accessToken = response.getString("access_token")
	}

	override suspend fun loadUser(): ScrobblerUser {
		val request = Request.Builder().get().url("$API_URL/me").build()
		val json = okHttp.newCall(request).await().parseJson()
		val id = json.getString("id")
		return ScrobblerUser(
			// Comick user ids are UUIDs, ScrobblerStorage persists a Long
			id = id.hashCode().toLong(),
			nickname = json.getStringOrNull("username") ?: id,
			avatar = json.getStringOrNull("avatar_url"),
			service = ScrobblerService.COMICK,
		).also { storage.user = it }
	}

	override fun logout() {
		storage.clear()
	}

	override suspend fun unregister(mangaId: Long) {
		db.getScrobblingDao().delete(ScrobblerService.COMICK.id, mangaId)
	}

	override suspend fun findManga(query: String, offset: Int, type: ScrobblerMangaType): List<ScrobblerManga> {
		// Comick has no novels, and its title search has a single page
		if (type.isNovel || offset > 0) return emptyList()
		val url = "$API_URL/titles".toHttpUrl().newBuilder()
			.addQueryParameter("q", query.take(SEARCH_QUERY_MAX))
			.addQueryParameter("media_type", "manga")
			.addQueryParameter("limit", SEARCH_LIMIT.toString())
			.build()
		val response = okHttp.newCall(Request.Builder().url(url).get().build()).await().parseJson()
		return response.getJSONArray("data").mapJSONNotNull { json ->
			val id = json.getString("hid").hidToLongOrNull() ?: return@mapJSONNotNull null
			val title = json.getString("title")
			ScrobblerManga(
				id = id,
				name = title,
				altName = null,
				cover = json.getStringOrNull("cover_url"),
				url = json.getString("url"),
				isBestMatch = title.equals(query, ignoreCase = true),
			)
		}
	}

	override suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo {
		val request = Request.Builder().url("$API_URL/titles/${id.toHid()}").get().build()
		val json = okHttp.newCall(request).await().parseJson().getJSONObject("data")
		return ScrobblerMangaInfo(
			id = id,
			name = json.getString("title"),
			cover = json.getStringOrNull("cover_url").orEmpty(),
			url = json.getString("url"),
			descriptionHtml = json.getStringOrNull("description").orEmpty(),
			// Comick doesn't know final chapter counts
			totalChapters = 0,
		)
	}

	override suspend fun createRate(mangaId: Long, scrobblerMangaId: Long): Boolean {
		libraryEntry(scrobblerMangaId)?.let {
			saveLibraryEntry(mangaId, scrobblerMangaId, it, comment = null)
			return true
		}
		// status is required when adding; the scrobbler sets the real one right after
		val entry = send("PUT", scrobblerMangaId, JSONObject().put("status", 1))
		saveLibraryEntry(mangaId, scrobblerMangaId, entry, comment = null)
		return false
	}

	override suspend fun refreshRate(entity: ScrobblingEntity): ScrobblingEntity {
		val json = checkNotNull(libraryEntry(entity.targetId)) { "Title is not in the Comick library" }
		return saveLibraryEntry(entity.mangaId, entity.targetId, json, entity.comment)
	}

	// rateId is a truncated copy of the HID number, so the stored targetId is used instead
	override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
		val current = requireEntity(mangaId)
		val body = JSONObject().put("progress", JSONObject().put("number", chapter.toString()))
		saveLibraryEntry(mangaId, current.targetId, send("PATCH", current.targetId, body), current.comment)
	}

	override suspend fun updateRate(
		rateId: Int,
		mangaId: Long,
		rating: Float,
		status: String?,
		comment: String?,
		setStartDate: Boolean, // Comick has no reading dates
	) {
		val current = requireEntity(mangaId)
		val body = JSONObject()
			// Comick takes whole 1..10 scores, unrated is null rather than 0
			.put("rating", rating.roundToInt().coerceIn(0, RATING_MAX).takeIf { it > 0 } ?: JSONObject.NULL)
		STATUSES.indexOf(status).takeIf { it >= 0 }?.let { body.put("status", it + 1) }
		saveLibraryEntry(mangaId, current.targetId, send("PATCH", current.targetId, body), comment)
	}

	private suspend fun requireEntity(mangaId: Long) = requireNotNull(
		db.getScrobblingDao().find(ScrobblerService.COMICK.id, mangaId),
	) { "Scrobbling info for manga $mangaId not found" }

	/** Comick keeps every field the body leaves out, and answers with the whole entry. */
	private suspend fun send(method: String, targetId: Long, body: JSONObject): JSONObject {
		val request = Request.Builder()
			.url("$API_URL/me/library/${targetId.toHid()}")
			.method(method, body.toString().toRequestBody("application/json".toMediaType()))
			.build()
		return okHttp.newCall(request).await().parseJson().getJSONObject("data")
	}

	/** `null` when the title is not in the user's library yet. */
	private suspend fun libraryEntry(targetId: Long): JSONObject? {
		val request = Request.Builder().url("$API_URL/me/library/${targetId.toHid()}").get().build()
		val response = okHttp.newCall(request).await()
		if (response.code == HttpURLConnection.HTTP_NOT_FOUND) {
			response.close()
			return null
		}
		return response.parseJson().getJSONObject("data")
	}

	private suspend fun saveLibraryEntry(
		mangaId: Long,
		targetId: Long,
		json: JSONObject,
		comment: String?,
	): ScrobblingEntity {
		val entity = ScrobblingEntity(
			scrobbler = ScrobblerService.COMICK.id,
			id = targetId.toInt(),
			mangaId = mangaId,
			targetId = targetId,
			status = json.getStringOrNull("status_name") ?: STATUSES.getOrNull(json.optInt("status") - 1),
			// progress is a string like "12.5", or a non-number label that counts as nothing read
			chapter = json.optJSONObject("progress")?.getStringOrNull("number")?.toFloatOrNull()?.toInt() ?: 0,
			// Comick has no notes field available, comments stay local
			comment = comment,
			rating = (json.optInt("rating", 0).toFloat() / RATING_MAX).coerceIn(0f, 1f),
		)
		db.getScrobblingDao().upsert(entity)
		return entity
	}

	private fun randomBase64Url(size: Int): String {
		val bytes = ByteArray(size)
		SecureRandom().nextBytes(bytes)
		return base64Url.encodeToString(bytes)
	}

	private fun String.toS256Challenge(): String =
		base64Url.encodeToString(MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.US_ASCII)))
}

private val base64Url = Base64.getUrlEncoder().withoutPadding()

/**
 * Comick titles are 8-character base64url HIDs, which are exactly 6 bytes, so they fit a Long
 * losslessly. Same encoding as Mihon's Comick tracker, so Mihon backups link the same titles.
 */
internal fun String.hidToLongOrNull(): Long? {
	if (length != 8) return null
	val bytes = runCatching { Base64.getUrlDecoder().decode(this) }.getOrNull() ?: return null
	return bytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
}

internal fun Long.toHid(): String = base64Url.encodeToString(ByteArray(6) { (this shr (8 * (5 - it))).toByte() })
