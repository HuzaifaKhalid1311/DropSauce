package org.koitharu.kotatsu.alternatives.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.core.model.chaptersCount
import org.koitharu.kotatsu.core.model.isExternalSource
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

class AutoFixUseCase @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val alternativesUseCase: AlternativesUseCase,
	private val migrateUseCase: MigrateUseCase,
	private val mangaDataRepository: MangaDataRepository,
) {

	/**
	 * @param targets source names in priority order. Null is the library "Fix" action: only a broken
	 * entry is replaced, by the healthy match with the most chapters among every enabled source.
	 * Otherwise the entry is always replaced, by the first working match in [targets] order.
	 */
	suspend operator fun invoke(mangaId: Long, targets: List<String>? = null): Pair<Manga, Manga?> {
		val seed = loadSeed(mangaId)
		val replacement = if (targets == null) {
			if (seed.isHealthy()) {
				return seed to null // no fix required
			}
			alternativesUseCase(seed)
				.filter { it.isHealthy() }
				.runningFold<Manga, Manga?>(null) { best, candidate ->
					if (best == null || best < candidate) {
						candidate
					} else {
						best
					}
				}.selectLastWithTimeout(4, 40, TimeUnit.SECONDS)
		} else {
			pickWorking(findCandidates(seed, targets))
		}
		migrateUseCase(seed, replacement ?: throw NoAlternativesException(ParcelableManga(seed)))
		return seed to replacement
	}

	suspend fun loadSeed(mangaId: Long): Manga = checkNotNull(
		mangaDataRepository.findMangaById(mangaId, withChapters = true),
	) { "Manga $mangaId not found" }.getDetailsSafe()

	/** Every match found in [targets], in [targets] order. Sources still searching after 40s are dropped. */
	suspend fun findCandidates(seed: Manga, targets: List<String>): List<Manga> {
		val found = ArrayList<Manga>()
		withTimeoutOrNull(TimeUnit.SECONDS.toMillis(40)) {
			alternativesUseCase(seed, targets.toSet()).toList(found)
		}
		return found.sortedBy { targets.indexOf(it.source.name) }
	}

	/** The first candidate whose first chapter actually serves pages. */
	suspend fun pickWorking(candidates: List<Manga>): Manga? = candidates.firstOrNull { it.isHealthy() }

	private suspend fun Manga.isHealthy(): Boolean = runCatchingCancellable {
		val repo = mangaRepositoryFactory.create(source)
		val details = if (this.chapters != null) this else repo.getDetails(this)
		val firstChapter = details.chapters?.firstOrNull() ?: return@runCatchingCancellable false
		val pageUrl = repo.getPageUrl(repo.getPages(details, firstChapter).first())
		// Most sources expose a fetchable http(s) page url. Some extensions (e.g. MangaDex) return a
		// relative imageUrl whose host is resolved internally by getImage(), so a non-blank url from
		// an external source still indicates a healthy, page-serving source.
		pageUrl.toHttpUrlOrNull() != null || (source.isExternalSource() && pageUrl.isNotEmpty())
	}.getOrDefault(false)

	private suspend fun Manga.getDetailsSafe() = runCatchingCancellable {
		mangaRepositoryFactory.create(source).getDetails(this)
	}.getOrDefault(this)

	private operator fun Manga.compareTo(other: Manga) = chaptersCount().compareTo(other.chaptersCount())

	@Suppress("UNCHECKED_CAST", "OPT_IN_USAGE")
	private suspend fun <T> Flow<T>.selectLastWithTimeout(
		minCount: Int,
		timeout: Long,
		timeUnit: TimeUnit
	): T? = channelFlow<T?> {
		var lastValue: T? = null
		launch {
			delay(timeUnit.toMillis(timeout))
			close(InternalTimeoutException(lastValue))
		}
		withIndex().transformWhile { (index, value) ->
			lastValue = value
			emit(value)
			index < minCount && !isClosedForSend
		}.collect {
			send(it)
		}
	}.catch { e ->
		if (e is InternalTimeoutException) {
			emit(e.value as T?)
		} else {
			throw e
		}
	}.lastOrNull()

	class NoAlternativesException(val seed: ParcelableManga) : NoSuchElementException()

	private class InternalTimeoutException(val value: Any?) : CancellationException()
}
