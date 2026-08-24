package org.koitharu.kotatsu.scrobbling.common.domain

import androidx.annotation.FloatRange
import androidx.collection.LongSparseArray
import androidx.collection.getOrElse
import androidx.core.text.parseAsHtml
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.findKeyByValue
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.sanitize
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerRepository
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaType
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import java.util.EnumMap

abstract class Scrobbler(
	protected val db: MangaDatabase,
	val scrobblerService: ScrobblerService,
	private val repository: ScrobblerRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	/** Scale applied to the 0..1 rating before it reaches services that expect a wider range. */
	private val ratingMax: Float = 1f,
) {

	private val infoCache = LongSparseArray<ScrobblerMangaInfo>()
	protected val statuses = EnumMap<ScrobblingStatus, String>(ScrobblingStatus::class.java)

	val user: Flow<ScrobblerUser> = flow {
		repository.cachedUser?.let {
			emit(it)
		}
		runCatchingCancellable {
			repository.loadUser()
		}.onSuccess {
			emit(it)
		}.onFailure {
			it.printStackTraceDebug()
		}
	}

	val isEnabled: Boolean
		get() = repository.isAuthorized

	suspend fun authorize(authCode: String): ScrobblerUser {
		repository.authorize(authCode)
		return repository.loadUser()
	}

	fun logout() {
		repository.logout()
	}

	suspend fun findManga(query: String, offset: Int, type: ScrobblerMangaType): List<ScrobblerManga> {
		return repository.findManga(query, offset, type)
	}

	/**
	 * Links [targetId] to [mangaId] without discarding what the tracker already knows: a rating, a
	 * reading status or a chapter count set on the website always wins over anything the app can infer
	 * from local history. [fallbackStatus] is only written when the entry is brand new.
	 *
	 * @return `true` if the tracker had no progress of its own, so local progress is safe to push.
	 */
	suspend fun linkManga(mangaId: Long, targetId: Long, fallbackStatus: ScrobblingStatus): Boolean {
		// Every service invents a status when it creates an entry ("reading" on most of them), so the
		// stored status cannot tell an adopted entry from a fresh one — only createRate knows.
		val wasAlreadyTracked = repository.createRate(mangaId, targetId)
		if (wasAlreadyTracked) {
			return db.getScrobblingDao().find(scrobblerService.id, mangaId)?.chapter?.let { it <= 0 } != false
		}
		// Brand new entry, so it has no start date of its own worth keeping
		updateScrobblingInfo(mangaId, rating = 0f, fallbackStatus, comment = null, forceStartDate = true)
		return true
	}

	suspend fun scrobble(manga: Manga, chapterId: Long) {
		var chapters = manga.chapters
		if (chapters.isNullOrEmpty()) {
			chapters = mangaRepositoryFactory.create(manga.source).getDetails(manga).chapters
		}
		requireNotNull(chapters)
		val chapter = checkNotNull(chapters.findById(chapterId)) {
			"Chapter $chapterId not found in this manga"
		}
		val number = if (chapter.number > 0f) {
			chapter.number.toInt()
		} else {
			chapters = chapters.filter { x -> x.branch == chapter.branch }
			chapters.indexOf(chapter) + 1
		}
		val entity = db.getScrobblingDao().find(scrobblerService.id, manga.id) ?: return
		repository.updateRate(entity.id, entity.mangaId, number)
		// Reading a chapter means it is no longer merely "planned", and no tracker flips that for us.
		// Only PLANNED is touched, so a manually set "on hold"/"dropped"/"completed" survives.
		if (isNotStarted(entity.status)) {
			updateScrobblingInfo(manga.id, entity.rating, ScrobblingStatus.READING, entity.comment)
		}
	}

	suspend fun getScrobblingInfoOrNull(mangaId: Long): ScrobblingInfo? {
		val entity = db.getScrobblingDao().find(scrobblerService.id, mangaId) ?: return null
		return entity.toScrobblingInfo()
	}

	suspend fun refreshScrobblingOrNull(mangaId: Long): ScrobblingEntity? {
		val entity = db.getScrobblingDao().find(scrobblerService.id, mangaId) ?: return null
		return repository.refreshRate(entity)
	}

	fun isNotStarted(status: String?): Boolean = status == statuses[ScrobblingStatus.PLANNED]

	/**
	 * @param forceStartDate stamps today as the start date even when the status is not changing. Used
	 * for an entry the app just created, which has no date of its own to preserve.
	 */
	suspend fun updateScrobblingInfo(
		mangaId: Long,
		@FloatRange(from = 0.0, to = 1.0) rating: Float,
		status: ScrobblingStatus?,
		comment: String?,
		forceStartDate: Boolean = false,
	) {
		val entity = requireNotNull(db.getScrobblingDao().find(scrobblerService.id, mangaId)) {
			"Scrobbling info for manga $mangaId not found"
		}
		val statusString = statuses[status]
		// The start date marks the day reading began, so it is only rewritten on the way into READING
		val isStartingToRead = status == ScrobblingStatus.READING && entity.status != statusString
		repository.updateRate(
			rateId = entity.id,
			mangaId = entity.mangaId,
			rating = rating * ratingMax,
			status = statusString,
			comment = comment,
			setStartDate = forceStartDate || isStartingToRead,
		)
	}

	fun observeScrobblingInfo(mangaId: Long): Flow<ScrobblingInfo?> {
		return db.getScrobblingDao().observe(scrobblerService.id, mangaId)
			.map { it?.toScrobblingInfo() }
	}

	fun observeAllScrobblingInfo(): Flow<List<ScrobblingInfo>> {
		return db.getScrobblingDao().observe(scrobblerService.id)
			.map { entities ->
				coroutineScope {
					entities.map {
						async {
							it.toScrobblingInfo()
						}
					}.awaitAll()
				}.filterNotNull()
			}
	}

	suspend fun unregisterScrobbling(mangaId: Long) {
		repository.unregister(mangaId)
	}

	protected suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo {
		return repository.getMangaInfo(id)
	}

	private suspend fun ScrobblingEntity.toScrobblingInfo(): ScrobblingInfo? {
		val mangaInfo = infoCache.getOrElse(targetId) {
			runCatchingCancellable {
				getMangaInfo(targetId)
			}.onFailure {
				it.printStackTraceDebug()
			}.onSuccess {
				infoCache.put(targetId, it)
			}.getOrNull() ?: return null
		}
		return ScrobblingInfo(
			scrobbler = scrobblerService,
			mangaId = mangaId,
			targetId = targetId,
			status = statuses.findKeyByValue(status),
			chapter = chapter,
			totalChapters = mangaInfo.totalChapters,
			comment = comment,
			rating = rating,
			title = mangaInfo.name,
			coverUrl = mangaInfo.cover,
			description = mangaInfo.descriptionHtml.parseAsHtml().sanitize(),
			externalUrl = mangaInfo.url,
		)
	}
}

suspend fun Scrobbler.tryScrobble(manga: Manga, chapterId: Long): Boolean {
	return runCatchingCancellable {
		scrobble(manga, chapterId)
	}.onFailure {
		it.printStackTraceDebug()
	}.isSuccess
}
