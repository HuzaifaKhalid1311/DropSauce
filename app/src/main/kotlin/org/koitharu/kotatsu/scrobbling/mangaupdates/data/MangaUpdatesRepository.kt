package org.koitharu.kotatsu.scrobbling.mangaupdates.data

import androidx.core.text.parseAsHtml
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.util.ext.toRequestBody
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.json.getDoubleOrDefault
import org.koitharu.kotatsu.parsers.util.json.getIntOrDefault
import org.koitharu.kotatsu.parsers.util.json.getLongOrDefault
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private const val API_URL = "https://api.mangaupdates.com/v1"
private const val WEB_URL = "https://www.mangaupdates.com"
private const val KEY_UID = "uid"
private const val SEARCH_QUERY_MAX = 100
private const val NOVEL_TYPE = "Novel"
private val JSON_TYPE = "application/json".toMediaType()

/**
 * MangaUpdates tracking, ported from Mihon's MangaUpdates tracker: the same endpoints, the same five
 * lists, chapter progress per list entry and a separate 1.0-10.0 rating.
 *
 * There is no refresh token, only a session token that is kept until the API rejects it. The series id
 * does not fit the entry id column (it is bigger than an Int), so every call goes by the stored target id.
 */
@Singleton
class MangaUpdatesRepository @Inject constructor(
	@ScrobblerType(ScrobblerService.MANGAUPDATES) private val okHttp: OkHttpClient,
	@ScrobblerType(ScrobblerService.MANGAUPDATES) private val storage: ScrobblerStorage,
	private val db: MangaDatabase,
) : ScrobblerRepository {

	override val oauthUrl: String = "kotatsu://mangaupdates-auth"

	override val isAuthorized: Boolean
		get() = storage.accessToken != null

	override val cachedUser: ScrobblerUser?
		get() = storage.user

	/** [code] is the username and password from the login screen, see [encodeCredentials]. */
	override suspend fun authorize(code: String?) {
		val (username, password) = checkNotNull(code?.let(::decodeCredentials)) {
			"MangaUpdates needs a username and a password"
		}
		val body = JSONObject().put("username", username).put("password", password)
		val request = Request.Builder().url("$API_URL/account/login").put(body.toRequestBody()).build()
		val context = okHttp.newCall(request).await().parseJson().getJSONObject("context")
		storage.accessToken = context.getString("session_token")
		storage[KEY_UID] = context.getLongOrDefault("uid", 0L).toString()
	}

	override suspend fun loadUser(): ScrobblerUser {
		val request = Request.Builder().get().url("$API_URL/account/profile").build()
		val json = okHttp.newCall(request).await().parseJson()
		val nickname = json.getStringOrNull("username") ?: "MangaUpdates"
		return ScrobblerUser(
			id = storage[KEY_UID]?.toLongOrNull() ?: json.getLongOrDefault("user_id", 0L),
			nickname = nickname,
			avatar = json.optJSONObject("avatar")?.getStringOrNull("url"),
			service = ScrobblerService.MANGAUPDATES,
		).also { storage.user = it }
	}

	override fun logout() {
		storage.clear()
	}

	override suspend fun unregister(mangaId: Long) {
		db.getScrobblingDao().delete(ScrobblerService.MANGAUPDATES.id, mangaId)
	}

	override suspend fun findManga(query: String, offset: Int, type: ScrobblerMangaType): List<ScrobblerManga> {
		query.seriesIdQueryOrNull()?.let { idText ->
			if (offset > 0) return emptyList()
			val id = idText.toSeriesIdOrNull() ?: return emptyList()
			return listOfNotNull(seriesRecord(id)?.toScrobblerManga(query = null))
		}
		val text = query.trim().take(SEARCH_QUERY_MAX)
		if (text.isEmpty()) return emptyList()
		val records = if (type.isNovel) searchNovels(text, offset) else searchManga(text, offset)
		return records.mapNotNull { it.toScrobblerManga(text) }
	}

	private suspend fun searchManga(text: String, offset: Int): List<JSONObject> {
		val body = searchBody(text, offset)
			.put("filter_types", JSONArray().put("drama cd").put("novel"))
		return search(body)
	}

	private suspend fun searchNovels(text: String, offset: Int): List<JSONObject> {
		val records = try {
			search(searchBody(text, offset).put("type", JSONArray().put(NOVEL_TYPE)))
		} catch (e: MangaUpdatesException) {
			// If the API refuses the type filter, search everything and keep the novels from the first page
			if (e.code != HttpURLConnection.HTTP_BAD_REQUEST) throw e
			if (offset > 0) return emptyList()
			search(searchBody(text, 0))
		}
		return records.filter { it.getStringOrNull("type").equals(NOVEL_TYPE, ignoreCase = true) }
	}

	private fun searchBody(text: String, offset: Int) = JSONObject()
		.put("search", text)
		.put("page", pageOf(offset))
		.put("perpage", SEARCH_PAGE_SIZE)

	private suspend fun search(body: JSONObject): List<JSONObject> {
		val request = Request.Builder().url("$API_URL/series/search").post(body.toRequestBody()).build()
		val results = okHttp.newCall(request).await().parseJson().optJSONArray("results") ?: return emptyList()
		return results.mapJSONNotNull { it.optJSONObject("record") }
	}

	override suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo {
		val record = checkNotNull(seriesRecord(id)) { "Series $id was not found on MangaUpdates" }
		return ScrobblerMangaInfo(
			id = id,
			name = record.getStringOrNull("title")?.htmlDecoded() ?: id.toString(),
			cover = record.coverUrl(thumb = false).orEmpty(),
			url = record.seriesUrl(id),
			// The text is HTML-escaped and uses plain new lines, which HTML would fold into spaces
			descriptionHtml = record.getStringOrNull("description").orEmpty()
				.replace("\r\n", "\n").replace("\n", "<br>"),
			// MangaUpdates only knows the latest released chapter, not a final count
			totalChapters = 0,
		)
	}

	override suspend fun createRate(mangaId: Long, scrobblerMangaId: Long): Boolean {
		val existing = try {
			listItem(scrobblerMangaId)
		} catch (_: MangaUpdatesException) {
			null
		}
		if (existing != null) {
			save(mangaId, scrobblerMangaId, existing, getRating(scrobblerMangaId), comment = null)
			return true
		}
		// A placeholder: the scrobbler sets the real status right after
		writeList("", listBody(scrobblerMangaId, listIdOf(STATUS_WISH_LIST), chapter = null))
		val created = checkNotNull(listItem(scrobblerMangaId)) { "MangaUpdates did not add the series" }
		save(mangaId, scrobblerMangaId, created, rating = null, comment = null)
		return false
	}

	override suspend fun refreshRate(entity: ScrobblingEntity): ScrobblingEntity {
		val item = checkNotNull(listItem(entity.targetId)) { "Series is not on your MangaUpdates lists" }
		return save(entity.mangaId, entity.targetId, item, getRating(entity.targetId), entity.comment)
	}

	override suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int) {
		val current = requireEntity(mangaId)
		// What the list holds right now, not what was stored: the list or progress may have moved on the website
		val item = checkNotNull(listItem(current.targetId)) { "Series is not on your MangaUpdates lists" }
		val remoteChapter = item.chapter()
		// Progress only moves forward, as in Mihon, so re-reading an old chapter never undoes it
		if (chapter > remoteChapter) {
			writeList("/update", listBody(current.targetId, item.listId(), chapter))
			save(mangaId, current.targetId, item.withChapter(chapter), current.rating.toApiRatingOrNull(), current.comment)
		} else {
			save(mangaId, current.targetId, item, current.rating.toApiRatingOrNull(), current.comment)
		}
	}

	override suspend fun updateRate(
		rateId: Int,
		mangaId: Long,
		rating: Float,
		status: String?,
		comment: String?,
		setStartDate: Boolean, // MangaUpdates has no reading dates
	) {
		val current = requireEntity(mangaId)
		val item = checkNotNull(listItem(current.targetId)) { "Series is not on your MangaUpdates lists" }
		val targetList = listIdOf(status)
		var latest = item
		if (targetList >= 0 && targetList != item.listId()) {
			// The chapter sent along is the list's own, so moving between lists never rewinds progress
			writeList("/update", listBody(current.targetId, targetList, item.chapter()))
			latest = item.withList(targetList)
		}
		// The rating is only written when the user changed it. The stored one can be stale, and sending it back unchanged would erase a rating given on the website.
		val newRating = rating.toApiRating()
		val oldScale = current.rating * RATING_SCALE
		val finalRating = if (abs(rating - oldScale) >= 0.05f) {
			writeRating(current.targetId, newRating)
			newRating
		} else {
			current.rating.toApiRatingOrNull()
		}
		save(mangaId, current.targetId, latest, finalRating, comment)
	}

	private suspend fun requireEntity(mangaId: Long) = requireNotNull(
		db.getScrobblingDao().find(ScrobblerService.MANGAUPDATES.id, mangaId),
	) { "Scrobbling info for manga $mangaId not found" }

	private suspend fun call(request: Request): Response = okHttp.newCall(request).await()

	/** `null` when the series is not on any of the user's lists. */
	private suspend fun listItem(targetId: Long): JSONObject? {
		val response = call(Request.Builder().get().url("$API_URL/lists/series/$targetId").build())
		if (response.code == HttpURLConnection.HTTP_NOT_FOUND) {
			response.close()
			return null
		}
		return response.parseJson()
	}

	/** `null` when the user has not rated the series, or the rating cannot be read. */
	private suspend fun getRating(targetId: Long): Double? = try {
		val response = call(Request.Builder().get().url("$API_URL/series/$targetId/rating").build())
		if (response.code == HttpURLConnection.HTTP_NOT_FOUND) {
			response.close()
			null
		} else {
			response.parseJson().getDoubleOrDefault("rating", 0.0).takeIf { it > 0.0 }
		}
	} catch (_: MangaUpdatesException) {
		null
	}

	private suspend fun seriesRecord(id: Long): JSONObject? {
		val response = call(Request.Builder().get().url("$API_URL/series/$id").build())
		if (response.code == HttpURLConnection.HTTP_NOT_FOUND) {
			response.close()
			return null
		}
		return response.parseJson()
	}

	/** [path] is `""` to add a series to a list and `"/update"` to change an entry. */
	private suspend fun writeList(path: String, body: JSONArray) {
		val request = Request.Builder()
			.url("$API_URL/lists/series$path".toHttpUrl())
			.post(body.toString().toRequestBody(JSON_TYPE))
			.build()
		call(request).use {
			if (it.code == HttpURLConnection.HTTP_NOT_FOUND) {
				throw MangaUpdatesException(it.code, "MangaUpdates: series not found")
			}
		}
	}

	/** A `null` [rating] removes the user's rating, which is how the API takes "no rating". */
	private suspend fun writeRating(targetId: Long, rating: Double?) {
		val builder = Request.Builder().url("$API_URL/series/$targetId/rating")
		val request = if (rating != null) {
			builder.put(JSONObject().put("rating", rating).toRequestBody()).build()
		} else {
			builder.delete().build()
		}
		call(request).close()
	}

	private fun listBody(targetId: Long, listId: Int, chapter: Int?): JSONArray {
		val entry = JSONObject()
			.put("series", JSONObject().put("id", targetId))
			.put("list_id", listId)
		if (chapter != null) {
			entry.put("status", JSONObject().put("chapter", chapter))
		}
		return JSONArray().put(entry)
	}

	private suspend fun save(
		mangaId: Long,
		targetId: Long,
		item: JSONObject,
		rating: Double?,
		comment: String?,
	): ScrobblingEntity {
		val entity = ScrobblingEntity(
			scrobbler = ScrobblerService.MANGAUPDATES.id,
			// Series ids outgrow an Int, so this is only a stable key. Calls use targetId.
			id = targetId.toInt(),
			mangaId = mangaId,
			targetId = targetId,
			status = LIST_STATUSES.getOrNull(item.listId()),
			chapter = item.chapter(),
			comment = comment,
			rating = rating.toRatingFraction(),
		)
		db.getScrobblingDao().upsert(entity)
		return entity
	}

	// A list entry looks like {"series": {...}, "list_id": 0, "status": {"volume": null, "chapter": 12}}
	private fun JSONObject.listId(): Int = getIntOrDefault("list_id", 0)

	private fun JSONObject.chapter(): Int = optJSONObject("status")?.getIntOrDefault("chapter", 0) ?: 0

	private fun JSONObject.withChapter(chapter: Int): JSONObject = JSONObject(toString())
		.put("status", (optJSONObject("status") ?: JSONObject()).put("chapter", chapter))

	private fun JSONObject.withList(listId: Int): JSONObject = JSONObject(toString()).put("list_id", listId)

	private fun Float.toApiRatingOrNull(): Double? = (this * RATING_SCALE).toApiRating()

	private fun JSONObject.coverUrl(thumb: Boolean): String? {
		val url = optJSONObject("image")?.optJSONObject("url") ?: return null
		val (first, second) = if (thumb) "thumb" to "original" else "original" to "thumb"
		return url.getStringOrNull(first) ?: url.getStringOrNull(second)
	}

	private fun JSONObject.seriesUrl(id: Long): String =
		getStringOrNull("url") ?: "$WEB_URL/series.html?id=$id"

	/** @param query what was searched for, to flag an exact title match; `null` when looked up by id */
	private fun JSONObject.toScrobblerManga(query: String?): ScrobblerManga? {
		val id = getLongOrDefault("series_id", 0L).takeIf { it > 0 } ?: return null
		val title = getStringOrNull("title")?.htmlDecoded() ?: return null
		return ScrobblerManga(
			id = id,
			name = title,
			altName = null,
			cover = coverUrl(thumb = true),
			url = seriesUrl(id),
			isBestMatch = query == null || title.equals(query, ignoreCase = true),
		)
	}

	// Titles come back HTML-escaped ("&amp;", "&#039;")
	private fun String.htmlDecoded(): String = parseAsHtml().toString()
}
