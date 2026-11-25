package org.koitharu.kotatsu.scrobbling.common.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import org.koitharu.kotatsu.scrobbling.common.domain.ScrobblerRepositoryMap
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import org.koitharu.kotatsu.scrobbling.kitsu.ui.KitsuAuthActivity
import org.koitharu.kotatsu.scrobbling.mangaupdates.ui.MangaUpdatesAuthActivity
import javax.inject.Inject

class ScrobblerAuthHelper @Inject constructor(
	private val repositoriesMap: ScrobblerRepositoryMap,
) {

	fun isAuthorized(scrobbler: ScrobblerService) = repositoriesMap[scrobbler].isAuthorized

	fun getCachedUser(scrobbler: ScrobblerService): ScrobblerUser? {
		return repositoriesMap[scrobbler].cachedUser
	}

	suspend fun getUser(scrobbler: ScrobblerService): ScrobblerUser {
		return repositoriesMap[scrobbler].loadUser()
	}

	@SuppressLint("UnsafeImplicitIntentLaunch")
	fun startAuth(context: Context, scrobbler: ScrobblerService) = runCatching {
		when (scrobbler) {
			// These two sign in with a username and password, on screens of their own
			ScrobblerService.KITSU -> context.startActivity(Intent(context, KitsuAuthActivity::class.java))
			ScrobblerService.MANGAUPDATES -> context.startActivity(Intent(context, MangaUpdatesAuthActivity::class.java))
			else -> {
				val repository = repositoriesMap[scrobbler]
				val intent = Intent(Intent.ACTION_VIEW)
				intent.data = repository.oauthUrl.toUri()
				context.startActivity(intent)
			}
		}
	}
}
