package org.koitharu.kotatsu.core.parser

import androidx.annotation.AnyThread
import androidx.collection.ArrayMap
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.MihonMangaRepository
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

interface MangaRepository {

	val source: MangaSource

	val sortOrders: Set<SortOrder>

	var defaultSortOrder: SortOrder

	val filterCapabilities: MangaListFilterCapabilities

	suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga>

	suspend fun getDetails(manga: Manga): Manga

	suspend fun getPages(chapter: MangaChapter): List<MangaPage>

	suspend fun getPageUrl(page: MangaPage): String

	suspend fun getFilterOptions(): MangaListFilterOptions

	suspend fun getRelated(seed: Manga): List<Manga>

	suspend fun find(manga: Manga): Manga? {
		val list = getList(0, SortOrder.RELEVANCE, MangaListFilter(query = manga.title))
		return list.find { x -> x.id == manga.id }
	}

	@Singleton
	class Factory @Inject constructor(
		private val localMangaRepository: LocalMangaRepository,
		private val contentCache: MemoryContentCache,
		private val mihonExtensionManager: MihonExtensionManager,
	) {

		private val cache = ArrayMap<MangaSource, WeakReference<MangaRepository>>()

		@AnyThread
		fun create(source: MangaSource): MangaRepository {
			val unwrapped = unwrap(source)
			when (unwrapped) {
				LocalMangaSource -> return localMangaRepository
				UnknownMangaSource -> return EmptyMangaRepository(unwrapped)
				is MihonMangaSource -> mihonExtensionManager.initialize()
			}
			cache[unwrapped]?.get()?.let { return it }
			return synchronized(cache) {
				cache[unwrapped]?.get()?.let { return it }
				val repository = createRepository(unwrapped)
				if (repository != null) {
					cache[unwrapped] = WeakReference(repository)
					repository
				} else {
					EmptyMangaRepository(unwrapped)
				}
			}
		}

		private fun unwrap(source: MangaSource): MangaSource = when (source) {
			is MangaSourceInfo -> source.mangaSource
			else -> source
		}

		private fun createRepository(source: MangaSource): MangaRepository? = when (source) {
			is MihonMangaSource -> MihonMangaRepository(
				source = source,
				cache = contentCache,
			)

			else -> null
		}
	}
}
