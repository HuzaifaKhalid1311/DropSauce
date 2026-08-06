package org.koitharu.kotatsu.scrobbling.mangabaka.domain

import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import org.koitharu.kotatsu.scrobbling.mangabaka.data.MangaBakaRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val RATING_MAX = 100f

@Singleton
class MangaBakaScrobbler @Inject constructor(
	repository: MangaBakaRepository,
	db: MangaDatabase,
	mangaRepositoryFactory: MangaRepository.Factory,
) : Scrobbler(db, ScrobblerService.MANGABAKA, repository, mangaRepositoryFactory, RATING_MAX) {

	init {
		statuses[ScrobblingStatus.PLANNED] = "plan_to_read"
		statuses[ScrobblingStatus.READING] = "reading"
		statuses[ScrobblingStatus.RE_READING] = "rereading"
		statuses[ScrobblingStatus.COMPLETED] = "completed"
		statuses[ScrobblingStatus.ON_HOLD] = "paused"
		statuses[ScrobblingStatus.DROPPED] = "dropped"
	}
}
