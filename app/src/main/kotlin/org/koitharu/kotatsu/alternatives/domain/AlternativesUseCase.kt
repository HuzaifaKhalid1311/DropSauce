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
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.domain.SearchV2Helper
import javax.inject.Inject

private const val MAX_PARALLELISM = 4

class AlternativesUseCase @Inject constructor(
	private val sourcesRepository: MangaSourcesRepository,
	private val searchHelperFactory: SearchV2Helper.Factory,
	private val mangaRepositoryFactory: MangaRepository.Factory,
) {

	suspend operator fun invoke(manga: Manga): Flow<Manga> {
		// Same kind only. A manga migrated onto a novel source (or the reverse) lands in the wrong
		// reader with content it cannot load, so those are never offered as alternatives.
		val isNovel = manga.source.isNovelSource
		val sources = getSources().filter { it != manga.source && it.isNovelSource == isNovel }
		if (sources.isEmpty()) {
			return emptyFlow()
		}
		val semaphore = Semaphore(MAX_PARALLELISM)
		return channelFlow {
			for (source in sources) {
				launch {
					val searchHelper = searchHelperFactory.create(source)
					val list = runCatchingCancellable {
						semaphore.withPermit {
							searchHelper(manga.title, SearchKind.TITLE)?.manga
						}
					}.getOrNull()
					// A source may return several same-name results; load details for all of them
					// and offer only the one whose best branch (scanlator) has the most chapters.
					val candidates = list?.filter { it.id != manga.id }
					if (candidates.isNullOrEmpty()) {
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
