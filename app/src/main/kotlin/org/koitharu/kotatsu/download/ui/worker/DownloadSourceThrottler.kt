package org.koitharu.kotatsu.download.ui.worker

import android.content.Context
import android.os.SystemClock
import androidx.collection.MutableObjectLongMap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.parsers.model.MangaSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps every running download polite to the site it reads from: at most
 * [MAX_CONCURRENT_REQUESTS_PER_SOURCE] requests in flight per source across the whole app, plus the
 * optional per-source slowdown.
 *
 * The per-chapter page parallelism inside a download is unaware of other downloads, so without a
 * shared limit two or three downloads from the same site would multiply it and earn a rate-limit
 * block, which costs far more time than it saves.
 */
@Singleton
class DownloadSourceThrottler @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	private val nextSlot = MutableObjectLongMap<MangaSource>()
	private val semaphores = HashMap<MangaSource, Semaphore>()
	private val settingsMap = HashMap<MangaSource, SourceSettings>()

	/**
	 * Waits for [source]'s next slowdown slot without taking a request permit. For background work
	 * that should be paced but must never queue behind a download, such as reader prefetch.
	 */
	suspend fun pace(source: MangaSource) = awaitSlot(source)

	/** Runs [block] as one request against [source], waiting for a free slot first. */
	suspend fun <T> withRequestPermit(source: MangaSource, block: suspend () -> T): T {
		val semaphore = synchronized(semaphores) {
			semaphores.getOrPut(source) { Semaphore(MAX_CONCURRENT_REQUESTS_PER_SOURCE) }
		}
		return semaphore.withPermit {
			awaitSlot(source)
			block()
		}
	}

	private suspend fun awaitSlot(source: MangaSource) {
		val settings = synchronized(settingsMap) {
			settingsMap.getOrPut(source) { SourceSettings(context, source) }
		}
		if (!settings.isSlowdownEnabled) {
			return
		}
		val now = SystemClock.elapsedRealtime()
		// Reserve the next free slot up front. Stamping "now" and only then sleeping made every
		// waiting request compute the same wake-up time and fire as one burst - the opposite of what
		// a slowdown is for.
		val slot = synchronized(nextSlot) {
			val reserved = maxOf(nextSlot.getOrDefault(source, 0L), now)
			nextSlot[source] = reserved + REQUEST_INTERVAL
			reserved
		}
		delay(slot - now)
	}

	private companion object {

		const val MAX_CONCURRENT_REQUESTS_PER_SOURCE = 5
		const val REQUEST_INTERVAL = 1_600L
	}
}
