package org.haziffe.dropsauce.core.parser

import android.content.Context
import androidx.annotation.AnyThread
import androidx.collection.ArrayMap
import dagger.hilt.android.qualifiers.ApplicationContext
import org.haziffe.dropsauce.core.cache.MemoryContentCache
import org.haziffe.dropsauce.core.model.LocalMangaSource
import org.haziffe.dropsauce.core.model.MangaSourceInfo
import org.haziffe.dropsauce.core.model.TestMangaSource
import org.haziffe.dropsauce.core.model.UnknownMangaSource
import org.haziffe.dropsauce.core.parser.external.ExternalMangaRepository
import org.haziffe.dropsauce.core.parser.external.ExternalMangaSource
import org.haziffe.dropsauce.local.data.LocalMangaRepository
import org.haziffe.dropsauce.mihon.MihonExtensionManager
import org.haziffe.dropsauce.mihon.MihonMangaRepository
import org.haziffe.dropsauce.mihon.model.MihonMangaSource
import org.haziffe.dropsauce.parsers.MangaLoaderContext
import org.haziffe.dropsauce.parsers.model.Manga
import org.haziffe.dropsauce.parsers.model.MangaChapter
import org.haziffe.dropsauce.parsers.model.MangaListFilter
import org.haziffe.dropsauce.parsers.model.MangaListFilterCapabilities
import org.haziffe.dropsauce.parsers.model.MangaListFilterOptions
import org.haziffe.dropsauce.parsers.model.MangaPage
import org.haziffe.dropsauce.parsers.model.MangaParserSource
import org.haziffe.dropsauce.parsers.model.MangaSource
import org.haziffe.dropsauce.parsers.model.SortOrder
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
		@ApplicationContext private val context: Context,
		private val localMangaRepository: LocalMangaRepository,
		private val loaderContext: MangaLoaderContext,
		private val contentCache: MemoryContentCache,
		private val mirrorSwitcher: MirrorSwitcher,
		private val mihonExtensionManager: MihonExtensionManager,
	) {

		private val cache = ArrayMap<MangaSource, WeakReference<MangaRepository>>()

		@AnyThread
		fun create(source: MangaSource): MangaRepository {
			when (source) {
				is MangaSourceInfo -> return create(source.mangaSource)
				LocalMangaSource -> return localMangaRepository
				UnknownMangaSource -> return EmptyMangaRepository(source)
				is MihonMangaSource -> mihonExtensionManager.initialize()
			}
			cache[source]?.get()?.let { return it }
			return synchronized(cache) {
				cache[source]?.get()?.let { return it }
				val repository = createRepository(source)
				if (repository != null) {
					cache[source] = WeakReference(repository)
					repository
				} else {
					EmptyMangaRepository(source)
				}
			}
		}

		private fun createRepository(source: MangaSource): MangaRepository? = when (source) {
			is MangaParserSource -> ParserMangaRepository(
				parser = loaderContext.newParserInstance(source),
				cache = contentCache,
				mirrorSwitcher = mirrorSwitcher,
			)

			is MihonMangaSource -> MihonMangaRepository(
				source = source,
				cache = contentCache,
			)

			TestMangaSource -> TestMangaRepository(
				loaderContext = loaderContext,
				cache = contentCache,
			)

			is ExternalMangaSource -> if (source.isAvailable(context)) {
				ExternalMangaRepository(
					contentResolver = context.contentResolver,
					source = source,
					cache = contentCache,
				)
			} else {
				EmptyMangaRepository(source)
			}

			else -> null
		}
	}
}
