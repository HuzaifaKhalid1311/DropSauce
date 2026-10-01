package org.koitharu.kotatsu.reader.domain

import android.graphics.BitmapFactory
import android.util.Size
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.network.imageproxy.ImageProxyInterceptor
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.local.data.isEpub
import org.koitharu.kotatsu.core.util.ext.isZipUri
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.ReaderState
import java.io.InputStream
import java.util.zip.ZipFile
import javax.inject.Inject
import kotlin.math.roundToInt

class DetectReaderModeUseCase @Inject constructor(
	private val dataRepository: MangaDataRepository,
	private val settings: AppSettings,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	@MangaHttpClient private val okHttpClient: OkHttpClient,
	private val imageProxyInterceptor: ImageProxyInterceptor,
) {

	suspend operator fun invoke(manga: Manga, state: ReaderState?): ReaderMode {
		dataRepository.getReaderMode(manga.id)?.let { return it }
		val defaultMode = settings.defaultReaderMode
		if (manga.isEpub) {
			// text chapters cannot be sampled as images; mode only selects paged vs scroll
			return defaultMode
		}
		if (!settings.isReaderModeDetectionEnabled || defaultMode == ReaderMode.WEBTOON) {
			return defaultMode
		}
		val chapter = state?.let { manga.findChapterById(it.chapterId) }
			?: manga.chapters?.firstOrNull()
			?: error("There are no chapters in this manga")
		val repo = mangaRepositoryFactory.create(manga.source)
		val pages = repo.getPages(manga, chapter)
		return runCatchingCancellable {
			val isWebtoon = guessMangaIsWebtoon(repo, pages)
			if (isWebtoon) ReaderMode.WEBTOON else defaultMode
		}.onSuccess {
			dataRepository.saveReaderMode(manga, it)
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrDefault(defaultMode)
	}

	/**
	 * Samples multiple pages spread across the chapter and uses a majority vote to determine
	 * if the manga is a webtoon. Sampling a single page is unreliable because chapter title
	 * pages and double-page spreads don't represent the typical page dimensions.
	 */
	private suspend fun guessMangaIsWebtoon(repository: MangaRepository, pages: List<MangaPage>): Boolean {
		// The reader waits for this before loading the first page, so sample concurrently: one by
		// one it cost up to three sequential image round trips on every first open.
		val votes = coroutineScope {
			getSampleIndices(pages.size).mapNotNull(pages::getOrNull).map { page ->
				async {
					runCatchingCancellable {
						val url = repository.getPageUrl(page)
						val size = getImageSize(url, page, repository)
						size.width * MIN_WEBTOON_RATIO < size.height
					}.getOrNull()
				}
			}.awaitAll().filterNotNull()
		}
		check(votes.isNotEmpty()) { "No pages could be sampled for webtoon detection" }
		return votes.count { it } * 2 > votes.size
	}

	private suspend fun getImageSize(url: String, page: MangaPage, repository: MangaRepository): Size {
		val uri = url.toUri()
		return when {
			uri.isZipUri() -> runInterruptible(Dispatchers.IO) {
				ZipFile(uri.schemeSpecificPart).use { zip ->
					val entry = zip.getEntry(uri.fragment)
					zip.getInputStream(entry).use { getBitmapSize(it) }
				}
			}
			uri.isFileUri() -> runInterruptible(Dispatchers.IO) {
				uri.toFile().inputStream().use { getBitmapSize(it) }
			}
			else -> {
				// Prefer the extension's getImage() (handles relative imageUrls like MangaDex
				// "/data/...", decryption, and per-source headers); fall back to a direct request.
				val response = repository.getImageStream(url, page)
					?: imageProxyInterceptor.interceptPageRequest(
						PageLoader.createPageRequest(url, page.source),
						okHttpClient,
					)
				response.use {
					runInterruptible(Dispatchers.IO) {
						getBitmapSize(it.body.byteStream())
					}
				}
			}
		}
	}

	private fun getSampleIndices(pageCount: Int): List<Int> = when {
		pageCount < 4 -> if (pageCount > 0) listOf(pageCount / 2) else emptyList()
		else -> listOf(
			(pageCount * 0.25).roundToInt(),
			(pageCount * 0.5).roundToInt(),
			(pageCount * 0.75).roundToInt(),
		).distinct()
	}

	companion object {

		private const val MIN_WEBTOON_RATIO = 1.8

		private fun getBitmapSize(input: InputStream?): Size {
			val options = BitmapFactory.Options().apply {
				inJustDecodeBounds = true
			}
			BitmapFactory.decodeStream(input, null, options)?.recycle()
			val imageHeight: Int = options.outHeight
			val imageWidth: Int = options.outWidth
			check(imageHeight > 0 && imageWidth > 0)
			return Size(imageWidth, imageHeight)
		}
	}
}
