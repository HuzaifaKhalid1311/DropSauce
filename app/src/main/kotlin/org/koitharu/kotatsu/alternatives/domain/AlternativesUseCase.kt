package org.koitharu.kotatsu.alternatives.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.core.model.chaptersCount
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.almostEquals
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.domain.SearchV2Helper
import javax.inject.Inject

private const val MAX_PARALLELISM = 4
private const val MATCH_THRESHOLD = 0.2f
private const val MIN_LOOSE_LENGTH = 4
private val BRACKETED = Regex("""\([^)]*\)|\[[^]]*]""")
private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

/** Title and alt titles, lowercased, without bracketed notes, punctuation or a leading "the". */
internal fun Manga.normalizedTitles(): List<String> = (altTitles + title).mapNotNull { raw ->
	raw.lowercase().replace(BRACKETED, " ").replace(NON_WORD, " ").trim().removePrefix("the ").ifEmpty { null }
}.distinct()

/** [loose] also accepts one title inside the other as whole words (a subtitle, a sequel). */
internal fun Manga.matchesTitles(refNames: List<String>, loose: Boolean): Boolean = normalizedTitles().any { name ->
	refNames.any { ref ->
		name.almostEquals(ref, MATCH_THRESHOLD) || loose && minOf(name.length, ref.length) >= MIN_LOOSE_LENGTH &&
			(" $name ".contains(" $ref ") || " $ref ".contains(" $name "))
	}
}

class AlternativesUseCase @Inject constructor(
	private val sourcesRepository: MangaSourcesRepository,
	private val searchHelperFactory: SearchV2Helper.Factory,
	private val mangaRepositoryFactory: MangaRepository.Factory,
) {

	/**
	 * @param targets source names to search, or null for every enabled source.
	 * @param loose also accept titles that contain one another (a subtitle, a sequel). Only for the
	 * manual screen, where the user picks: auto-fix and migrate would jump to a spin-off.
	 */
	suspend operator fun invoke(
		manga: Manga,
		targets: Collection<String>? = null,
		loose: Boolean = false,
	): Flow<Manga> {
		// Same kind only. A manga migrated onto a novel source (or the reverse) lands in the wrong
		// reader with content it cannot load, so those are never offered as alternatives.
		val isNovel = manga.source.isNovelSource
		val sources = getSources().filter {
			it != manga.source && it.isNovelSource == isNovel && (targets == null || it.name in targets)
		}
		if (sources.isEmpty()) {
			return emptyFlow()
		}
		val refNames = manga.normalizedTitles()
		val semaphore = Semaphore(MAX_PARALLELISM)
		return channelFlow {
			for (source in sources) {
				launch {
					val searchHelper = searchHelperFactory.create(source)
					val list = runCatchingCancellable {
						semaphore.withPermit {
							searchHelper(manga.title, SearchKind.SIMPLE)?.manga
						}
					}.getOrNull()
					// A source may return several same-name results; load details for all of them
					// and offer only the one whose best branch (scanlator) has the most chapters.
					// Close matches win over loose ones, so a sequel never beats the title itself.
					val others = list?.filter { it.id != manga.id }.orEmpty()
					val candidates = others.filter { it.matchesTitles(refNames, loose = false) }.ifEmpty {
						if (loose) others.filter { it.matchesTitles(refNames, loose = true) } else emptyList()
					}
					if (candidates.isEmpty()) {
						return@launch
					}
					val best = candidates.map { m ->
						async {
							runCatchingCancellable {
								mangaRepositoryFactory.create(m.source).getDetails(m)
							}.getOrDefault(m)
						}
					}.awaitAll().maxByOrNull { it.chaptersCount() }
					if (best != null) {
						send(best)
					}
				}
			}
		}
	}

	private suspend fun getSources(): List<MangaSource> {
		return sourcesRepository.getEnabledSources().toList()
	}
}
