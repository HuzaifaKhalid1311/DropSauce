package org.koitharu.kotatsu.scrobbling.common.data

import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser

interface ScrobblerRepository {

	val oauthUrl: String

	val isAuthorized: Boolean

	val cachedUser: ScrobblerUser?

	suspend fun authorize(code: String?)

	suspend fun loadUser(): ScrobblerUser

	fun logout()

	suspend fun unregister(mangaId: Long)

	suspend fun findManga(query: String, offset: Int, type: ScrobblerMangaType): List<ScrobblerManga>

	suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo

	/**
	 * Registers the manga on the service, or adopts the entry already there.
	 *
	 * @return `true` when the service already had an entry, whose status, rating and progress were
	 * stored as-is. Callers must not overwrite them; `false` means the entry was just created and its
	 * fields are placeholders that still need filling in.
	 */
	suspend fun createRate(mangaId: Long, scrobblerMangaId: Long): Boolean

	suspend fun refreshRate(entity: ScrobblingEntity): ScrobblingEntity

	suspend fun updateRate(rateId: Int, mangaId: Long, chapter: Int)

	/**
	 * @param setStartDate writes today as the reading start date. Left alone otherwise, so a date the
	 * user set on the website is never disturbed by an unrelated rating or note edit.
	 */
	suspend fun updateRate(
		rateId: Int,
		mangaId: Long,
		rating: Float,
		status: String?,
		comment: String?,
		setStartDate: Boolean,
	)
}
