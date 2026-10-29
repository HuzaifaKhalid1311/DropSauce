package org.koitharu.kotatsu.reader.domain

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import androidx.annotation.AnyThread
import androidx.annotation.CheckResult
import androidx.collection.LongSparseArray
import androidx.collection.MutableLongIntMap
import androidx.collection.forEach
import androidx.collection.set
import androidx.core.net.toFile
import androidx.core.net.toUri
import com.davemorrissey.labs.subscaleview.ImageSource
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.use
import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.exceptions.CloudFlareException
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.network.imageproxy.ImageProxyInterceptor
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.URI_SCHEME_ZIP
import org.koitharu.kotatsu.core.util.ext.compressToPNG
import org.koitharu.kotatsu.core.util.ext.ensureRamAtLeast
import org.koitharu.kotatsu.core.util.ext.ensureSuccess
import org.koitharu.kotatsu.core.util.ext.getCompletionResultOrNull
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.isNotEmpty
import org.koitharu.kotatsu.core.util.ext.isZipUri
import org.koitharu.kotatsu.core.util.ext.lifecycleScope
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.toMimeType
import org.koitharu.kotatsu.core.util.ext.use
import org.koitharu.kotatsu.core.util.ext.withProgress
import org.koitharu.kotatsu.core.util.progress.ProgressDeferred
import org.koitharu.kotatsu.download.ui.worker.DownloadSourceThrottler
import org.koitharu.kotatsu.local.data.LocalStorageCache
import org.koitharu.kotatsu.local.data.PageCache
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import javax.inject.Inject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@ActivityRetainedScoped
class PageLoader @Inject constructor(
	@LocalizedAppContext private val context: Context,
	lifecycle: ActivityRetainedLifecycle,
	@MangaHttpClient private val okHttp: OkHttpClient,
	@PageCache private val cache: LocalStorageCache,
	private val settings: AppSettings,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val imageProxyInterceptor: ImageProxyInterceptor,
	private val downloadSourceThrottler: DownloadSourceThrottler,
) {

	val loaderScope = lifecycle.lifecycleScope + InternalErrorHandler() + Dispatchers.Default

	private val tasks = LongSparseArray<ProgressDeferred<Uri, Float>>()
	// How many page views show each page: the preloader never cancels a load one of them waits for
	private val shownPages = MutableLongIntMap()
	private var preloadJob: Job? = null
	private var preloadPageId = 0L
	private var preloadLoadAhead = false
	// The next chapter's online pages, looked up once per chapter id like Mihon does
	private var nextChapterPages: Pair<Long, List<ReaderPage>?>? = null
	private val convertLock = Mutex()

	@Volatile
	private var repository: MangaRepository? = null
	private val edgeDetector = EdgeDetector(context)

	/**
	 * Mihon's preloading: the pages around [current] first, then the rest of its chapter a few more at a time,
	 * then [nextChapter] one page at a time. Near the chapter's end the next chapter's first pages come before
	 * the pages already passed. Without "Content preloading" only the pages next to [current] load, plus the
	 * next chapter's first pages near the end.
	 */
	@AnyThread
	fun preload(pages: List<ReaderPage>, current: ReaderPage, nextChapter: suspend () -> List<ReaderPage>?) {
		val loadAhead = settings.isContentPrefetchEnabled
		synchronized(tasks) {
			if (current.id == preloadPageId && loadAhead == preloadLoadAhead) return
			preloadPageId = current.id
			preloadLoadAhead = loadAhead
			preloadJob?.cancel()
			preloadJob = loaderScope.launch {
				val chapter = pages.filter { it.chapterId == current.chapterId }
				if (chapter.isEmpty()) return@launch
				val index = current.index
				val nearEnd = index >= chapter.size - NEXT_CHAPTER_RANGE
				val online = chapter.isOnline()
				val window = if (loadAhead) chapter.indices else (index - 1)..(index + LIMITED_PAGES_AHEAD)
				val forwardChunks = if (online) {
					buildList {
						chapter.getOrNull(index)?.let(::add)
						chapter.getOrNull(index - 1)?.let(::add)
						chapter.getOrNull(index + 1)?.let(::add)
						if (index < chapter.lastIndex - 1) addAll(chapter.subList(index + 2, chapter.size))
					}.filter { it.index in window }.rollingChunked(3, 5)
				} else {
					emptyList()
				}
				val backwardChunks = if (online && loadAhead && index > 1) {
					chapter.subList(0, index - 1).asReversed().rollingChunked(4, 5)
				} else {
					emptyList()
				}
				val allChunks = forwardChunks + backwardChunks
				synchronized(tasks) {
					// Free bandwidth held by loads the reader moved away from, keeping the next two unfinished chunks
					val activeChunks = allChunks
						.filter { chunk -> chunk.any { tasks[it.id]?.getCompletionResultOrNull()?.isSuccess != true } }
						.take(2)
						.flatten()
						.mapToSet { it.id }
					val nextKeep = if (loadAhead || nearEnd) {
						val next = nextChapterPages?.takeIf { it.first == current.chapterId }?.second.orEmpty()
						(if (loadAhead) next else next.take(LIMITED_PAGES_AHEAD + 1)).map { it.id }
					} else {
						emptyList()
					}
					val keep = activeChunks + nextKeep
					tasks.forEach { id, task ->
						if (task.isActive && id !in keep && !isShown(id)) task.cancel()
					}
				}
				forwardChunks.forEachIndexed { i, chunk ->
					// The first chunk is what the reader shows now, so it never waits for a source's slowdown
					chunk.mapNotNull { preloadOnce(it, pace = i > 0, checkDisk = i < 2) }.joinAll()
				}
				if (nearEnd) {
					val next = getNextChapterPages(current.chapterId, nextChapter).orEmpty()
					next.take(LIMITED_PAGES_AHEAD + 1).forEach {
						preloadOnce(it, pace = true, checkDisk = true)?.join()
					}
				}
				if (loadAhead) {
					backwardChunks.forEach { chunk ->
						chunk.mapNotNull { preloadOnce(it, pace = true, checkDisk = false) }.joinAll()
					}
					val next = getNextChapterPages(current.chapterId, nextChapter).orEmpty()
					val remaining = if (nearEnd) next.drop(LIMITED_PAGES_AHEAD + 1) else next
					remaining.forEach { preloadOnce(it, pace = true, checkDisk = false)?.join() }
				}
			}
		}
	}

	/** A page view started or stopped showing [pageId]; see [shownPages]. */
	@AnyThread
	fun setPageShown(pageId: Long, isShown: Boolean) {
		synchronized(shownPages) {
			val count = shownPages.getOrDefault(pageId, 0) + if (isShown) 1 else -1
			if (count > 0) {
				shownPages[pageId] = count
			} else {
				shownPages.remove(pageId)
			}
		}
	}

	fun loadPageAsync(page: MangaPage, force: Boolean): ProgressDeferred<Uri, Float> {
		synchronized(tasks) {
			val task = tasks[page.id]
			val reuse = when {
				task == null || task.isCancelled -> false
				// A retry joins a load another view of the page waits for, e.g. the other half of a split page
				task.isActive -> !force || synchronized(shownPages) { shownPages.getOrDefault(page.id, 0) > 1 }
				else -> !force && task.isValid()
			}
			if (reuse) {
				return checkNotNull(task)
			}
			task?.cancel()
			return loadPageAsyncImpl(page, skipCache = force, pace = false).also { tasks[page.id] = it }
		}
	}

	suspend fun loadPage(page: MangaPage, force: Boolean): Uri {
		// Counts as shown while it loads, so the preloader never cancels it
		setPageShown(page.id, true)
		try {
			return loadPageAsync(page, force).await()
		} finally {
			setPageShown(page.id, false)
		}
	}

	@CheckResult
	suspend fun convertBimap(uri: Uri): Uri = convertLock.withLock {
		if (uri.isZipUri()) {
			runInterruptible(Dispatchers.IO) {
				ZipFile(uri.schemeSpecificPart).use { zip ->
					val entry = zip.getEntry(uri.fragment)
					context.ensureRamAtLeast(entry.size * 2)
					zip.getInputStream(entry).use {
						BitmapDecoderCompat.decode(it, MimeTypes.getMimeTypeFromExtension(entry.name))
					}
				}
			}.use { image ->
				cache.set(uri.toString(), image).toUri()
			}
		} else {
			val file = uri.toFile()
			runInterruptible(Dispatchers.IO) {
				context.ensureRamAtLeast(file.length() * 2)
				BitmapDecoderCompat.decode(file)
			}.use { image ->
				image.compressToPNG(file)
			}
			uri
		}
	}

	/** The whole image as a rect. Null when the size can't be read (e.g. a format BitmapFactory doesn't know). */
	suspend fun getImageBounds(uri: Uri): Rect? = runCatchingCancellable {
		runInterruptible(Dispatchers.IO) {
			val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
			if (uri.isZipUri()) {
				ZipFile(uri.schemeSpecificPart).use { zip ->
					zip.getInputStream(zip.getEntry(uri.fragment)).use { BitmapFactory.decodeStream(it, null, options) }
				}
			} else {
				context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
			}
			Rect(0, 0, options.outWidth, options.outHeight).takeUnless { it.isEmpty }
		}
	}.getOrNull()

	suspend fun getTrimmedBounds(uri: Uri): Rect? = runCatchingCancellable {
		edgeDetector.getBounds(ImageSource.uri(uri))
	}.onFailure { error ->
		error.printStackTraceDebug()
	}.getOrNull()

	suspend fun getPageUrl(page: MangaPage): String {
		return getRepository(page.source).getPageUrl(page)
	}

	/**
	 * Mihon's launchLoadOnce: starts preloading [page] unless it's loaded or already loading. Failed pages retry.
	 * [checkDisk] also reloads a page whose file left the cache; only for nearby pages, so a chapter bigger than
	 * the cache can't keep evicting and refetching itself.
	 */
	private fun preloadOnce(page: ReaderPage, pace: Boolean, checkDisk: Boolean): Deferred<Uri>? = synchronized(tasks) {
		val task = tasks[page.id]
		val isLoaded = when {
			task == null || task.isCancelled -> false
			checkDisk -> task.isValid()
			else -> task.getCompletionResultOrNull()?.isSuccess == true
		}
		when {
			task != null && task.isActive -> task
			isLoaded -> null
			else -> loadPageAsyncImpl(page.toMangaPage(), skipCache = false, pace = pace).also {
				tasks[page.id] = it
			}
		}
	}

	private fun isShown(pageId: Long) = synchronized(shownPages) { pageId in shownPages }

	private suspend fun getNextChapterPages(
		chapterId: Long,
		nextChapter: suspend () -> List<ReaderPage>?,
	): List<ReaderPage>? {
		synchronized(tasks) { nextChapterPages }?.let { (id, pages) -> if (id == chapterId) return pages }
		val result = runCatchingCancellable { nextChapter() }.onFailure { it.printStackTraceDebug() }
		val pages = result.getOrNull()?.takeIf { it.isOnline() }
		// A network error may pass, so it's asked again on the next page turn; anything else is the source's answer
		val error = result.exceptionOrNull()
		if (error !is IOException || error is CloudFlareException) {
			synchronized(tasks) { nextChapterPages = chapterId to pages }
		}
		return pages
	}

	// Like Mihon, only online manga chapters preload: local pages are read straight from storage
	private fun List<ReaderPage>.isOnline(): Boolean {
		val source = firstOrNull()?.source ?: return false
		return !source.isNovelSource && mangaRepositoryFactory.create(source) is CachingMangaRepository
	}

	private fun loadPageAsyncImpl(
		page: MangaPage,
		skipCache: Boolean,
		pace: Boolean,
	): ProgressDeferred<Uri, Float> {
		val progress = MutableStateFlow(PROGRESS_UNDEFINED)
		val deferred = loaderScope.async {
			loadPageImpl(
				page = page,
				progress = progress,
				pace = pace,
				skipCache = skipCache,
			)
		}
		return ProgressDeferred(deferred, progress)
	}

	@Synchronized
	private fun getRepository(source: MangaSource): MangaRepository {
		val result = repository
		return if (result != null && result.source == source) {
			result
		} else {
			mangaRepositoryFactory.create(source).also { repository = it }
		}
	}

	private suspend fun loadPageImpl(
		page: MangaPage,
		progress: MutableStateFlow<Float>,
		pace: Boolean,
		skipCache: Boolean,
	): Uri {
		val pageUrl = getPageUrl(page)
		check(pageUrl.isNotBlank()) { "Cannot obtain full image url for $page" }
		if (!skipCache) {
			cache.get(pageUrl)?.let { return it.toUri() }
		}
		val uri = pageUrl.toUri()
		return when {
			uri.isZipUri() -> if (uri.scheme == URI_SCHEME_ZIP) {
				uri
			} else { // legacy uri
				uri.buildUpon().scheme(URI_SCHEME_ZIP).build()
			}

			uri.isFileUri() -> uri
			else -> {
				if (pace) {
					downloadSourceThrottler.pace(page.source)
				}
				val repo = getRepository(page.source)
				// Use extension's getImage() when available — handles decryption/unscrambling.
				// Falls back to direct OkHttp fetch for non-Mihon sources (getImageStream returns null).
				val response = repo.getImageStream(pageUrl, page)
					?: run {
						// Only ask for raw-request headers on the fallback path. A Mihon source's
						// getImage() already calls imageRequest(); invoking it once more just to
						// discard its request can regenerate signatures or other stateful headers.
						val imageHeaders = repo.getImageRequestHeaders(pageUrl, page)
						val request = createPageRequest(pageUrl, page.source, imageHeaders)
						imageProxyInterceptor.interceptPageRequest(request, okHttp)
					}
				response.ensureSuccess().use { r ->
					r.body.withProgress(progress).use {
						cache.set(pageUrl, it.source(), it.contentType()?.toMimeType())
					}
				}.toUri()
			}
		}
	}

	/** Mihon's chunk sizes: [startSize] pages first, one more each chunk up to [endSize]. */
	private fun <T> List<T>.rollingChunked(startSize: Int, endSize: Int): List<List<T>> {
		val result = ArrayList<List<T>>()
		var chunkSize = startSize
		var index = 0
		while (index < size) {
			result.add(subList(index, minOf(index + chunkSize, size)))
			index += chunkSize
			if (chunkSize < endSize) chunkSize++
		}
		return result
	}

	private fun Deferred<Uri>.isValid(): Boolean {
		return getCompletionResultOrNull()?.map { uri ->
			uri.exists() && uri.isTargetNotEmpty()
		}?.getOrDefault(false) != false
	}

	private class InternalErrorHandler : AbstractCoroutineContextElement(CoroutineExceptionHandler),
		CoroutineExceptionHandler {

		override fun handleException(context: CoroutineContext, exception: Throwable) {
			exception.printStackTraceDebug()
		}
	}

	companion object {

		private const val PROGRESS_UNDEFINED = -1f
		private const val LIMITED_PAGES_AHEAD = 4
		// Mihon preloads the next chapter once the reader is within a chapter's last 5 pages
		private const val NEXT_CHAPTER_RANGE = 5

		fun createPageRequest(
			pageUrl: String,
			mangaSource: MangaSource,
			extraHeaders: Headers? = null,
		) = Request.Builder()
			.url(pageUrl)
			.get()
			.apply {
				// Add extension-provided headers (e.g. Referer) before setting Accept,
				// so our Accept always wins if the extension also sets one.
				extraHeaders?.forEach { (name, value) -> addHeader(name, value) }
				header(CommonHeaders.ACCEPT, "image/webp,image/png;q=0.9,image/jpeg,*/*;q=0.8")
			}
			.cacheControl(CommonHeaders.CACHE_CONTROL_NO_STORE)
			.tag(MangaSource::class.java, mangaSource)
			.build()


		@Blocking
		private fun Uri.exists(): Boolean = when {
			isFileUri() -> toFile().exists()
			isZipUri() -> {
				val file = File(requireNotNull(schemeSpecificPart))
				file.exists() && ZipFile(file).use { it.getEntry(fragment) != null }
			}

			else -> false
		}

		@Blocking
		private fun Uri.isTargetNotEmpty(): Boolean = when {
			isFileUri() -> toFile().isNotEmpty()
			isZipUri() -> {
				val file = File(requireNotNull(schemeSpecificPart))
				file.exists() && ZipFile(file).use { (it.getEntry(fragment)?.size ?: 0L) != 0L }
			}

			else -> false
		}
	}
}
