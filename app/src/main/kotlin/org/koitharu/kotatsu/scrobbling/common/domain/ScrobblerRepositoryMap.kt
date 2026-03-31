package org.haziffe.dropsauce.scrobbling.common.domain

import org.haziffe.dropsauce.scrobbling.anilist.data.AniListRepository
import org.haziffe.dropsauce.scrobbling.common.data.ScrobblerRepository
import org.haziffe.dropsauce.scrobbling.common.domain.model.ScrobblerService
import org.haziffe.dropsauce.scrobbling.kitsu.data.KitsuRepository
import org.haziffe.dropsauce.scrobbling.mal.data.MALRepository
import org.haziffe.dropsauce.scrobbling.shikimori.data.ShikimoriRepository
import javax.inject.Inject
import javax.inject.Provider

class ScrobblerRepositoryMap @Inject constructor(
	private val shikimoriRepository: Provider<ShikimoriRepository>,
	private val aniListRepository: Provider<AniListRepository>,
	private val malRepository: Provider<MALRepository>,
	private val kitsuRepository: Provider<KitsuRepository>,
) {

	operator fun get(scrobblerService: ScrobblerService): ScrobblerRepository = when (scrobblerService) {
		ScrobblerService.SHIKIMORI -> shikimoriRepository
		ScrobblerService.ANILIST -> aniListRepository
		ScrobblerService.MAL -> malRepository
		ScrobblerService.KITSU -> kitsuRepository
	}.get()
}
