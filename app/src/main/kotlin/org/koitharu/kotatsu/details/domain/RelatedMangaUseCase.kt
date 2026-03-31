package org.haziffe.dropsauce.details.domain

import org.haziffe.dropsauce.core.parser.MangaRepository
import org.haziffe.dropsauce.core.util.ext.printStackTraceDebug
import org.haziffe.dropsauce.parsers.model.Manga
import org.haziffe.dropsauce.parsers.util.runCatchingCancellable
import javax.inject.Inject

class RelatedMangaUseCase @Inject constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
) {

	suspend operator fun invoke(seed: Manga) = runCatchingCancellable {
		mangaRepositoryFactory.create(seed.source).getRelated(seed)
	}.onFailure {
		it.printStackTraceDebug()
	}.getOrNull()
}
