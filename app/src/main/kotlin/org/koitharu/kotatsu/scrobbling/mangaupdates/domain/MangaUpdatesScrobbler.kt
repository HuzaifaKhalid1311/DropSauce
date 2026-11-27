package org.koitharu.kotatsu.scrobbling.mangaupdates.domain

import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.MangaUpdatesRepository
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.RATING_SCALE
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.STATUS_COMPLETE
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.STATUS_ON_HOLD
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.STATUS_READING
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.STATUS_UNFINISHED
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.STATUS_WISH_LIST
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaUpdatesScrobbler @Inject constructor(
	repository: MangaUpdatesRepository,
	db: MangaDatabase,
	mangaRepositoryFactory: MangaRepository.Factory,
) : Scrobbler(db, ScrobblerService.MANGAUPDATES, repository, mangaRepositoryFactory, RATING_SCALE) {

	init {
		statuses[ScrobblingStatus.PLANNED] = STATUS_WISH_LIST
		statuses[ScrobblingStatus.READING] = STATUS_READING
		statuses[ScrobblingStatus.COMPLETED] = STATUS_COMPLETE
		statuses[ScrobblingStatus.ON_HOLD] = STATUS_ON_HOLD
		statuses[ScrobblingStatus.DROPPED] = STATUS_UNFINISHED
	}
}
