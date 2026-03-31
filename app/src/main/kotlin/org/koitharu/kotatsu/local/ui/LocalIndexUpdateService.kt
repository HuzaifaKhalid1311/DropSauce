package org.haziffe.dropsauce.local.ui

import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import org.haziffe.dropsauce.core.ui.CoroutineIntentService
import org.haziffe.dropsauce.local.data.index.LocalMangaIndex
import javax.inject.Inject

@AndroidEntryPoint
class LocalIndexUpdateService : CoroutineIntentService() {

	@Inject
	lateinit var localMangaIndex: LocalMangaIndex

	override suspend fun IntentJobContext.processIntent(intent: Intent) {
		localMangaIndex.update()
	}

	override fun IntentJobContext.onError(error: Throwable) = Unit
}
