package org.koitharu.kotatsu.core.util.ext

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.CheckResult
import coil3.Extras
import coil3.ImageLoader
import coil3.asDrawable
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.Options
import coil3.request.SuccessResult
import coil3.request.bitmapConfig
import coil3.toBitmap
import okio.buffer
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.core.image.RegionBitmapDecoder
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource

fun ImageRequest.Builder.enqueueWith(loader: ImageLoader) = loader.enqueue(build())

fun ImageResult.getDrawableOrThrow() = when (this) {
	is SuccessResult -> image.asDrawable(request.context.resources)
	is ErrorResult -> throw throwable
}

val ImageResult.drawable: Drawable?
	get() = image?.asDrawable(request.context.resources)

fun ImageResult.toBitmapOrNull() = when (this) {
	is SuccessResult -> try {
		image.toBitmap(image.width, image.height, request.bitmapConfig)
	} catch (_: Throwable) {
		null
	}

	is ErrorResult -> null
}

fun ImageRequest.Builder.decodeRegion(
	scroll: Int = RegionBitmapDecoder.SCROLL_UNDEFINED,
): ImageRequest.Builder = apply {
	decoderFactory(RegionBitmapDecoder.Factory)
	extras[RegionBitmapDecoder.regionScrollKey] = scroll
}

fun ImageRequest.Builder.mangaSourceExtra(source: MangaSource?): ImageRequest.Builder = apply {
	extras[mangaSourceKey] = source
}

fun ImageRequest.Builder.mangaExtra(manga: Manga?): ImageRequest.Builder = apply {
	extras[mangaKey] = manga
	mangaSourceExtra(manga?.source)
}

/**
 * Pins a cover's disk-cache entry to a stable key derived from the manga id instead of the
 * (possibly rotating) source cover URL. Many sources serve covers from signed/CDN URLs that
 * change between sessions; without a stable key Coil would treat each new URL as a cache miss
 * and re-download every cover on launch, showing the loading placeholder ("greyed out" covers).
 *
 * With a stable key the cover is fetched once and then served from disk on every subsequent
 * launch — instantly, with no network round-trip — exactly like a local/custom cover.
 *
 * Only applied to remote (http) covers; local/custom `file://` covers are already stable.
 * A fresh copy is pulled from the source only when the manga is opened (see the details screen),
 * which overwrites this entry.
 *
 * Skipped for user-set cover overrides (a URL that differs from the source cover): those are already
 * stable and must keep their own cache key, otherwise they'd collide with the source cover cached
 * under `cover:$mangaId` and the override would never show in lists.
 */
fun ImageRequest.Builder.stableMangaCoverKey(manga: Manga?, coverUrl: String?): ImageRequest.Builder = apply {
	if (manga != null && isRemoteCoverUrl(coverUrl) &&
		(coverUrl == manga.coverUrl || coverUrl == manga.largeCoverUrl)
	) {
		diskCacheKey(mangaCoverDiskCacheKey(manga.id))
	}
}

fun mangaCoverDiskCacheKey(mangaId: Long): String = "cover:$mangaId"

/**
 * Downloads [manga]'s cover into the disk cache under the key lists load it with, so it later shows
 * without a download. A saved cover is downloaded again only on [refresh], which also drops the old
 * image from memory so views load the new one. False if it could not be saved.
 */
suspend fun ImageLoader.saveCover(context: Context, settings: AppSettings, manga: Manga, refresh: Boolean = false): Boolean {
	val url = manga.coverUrl
	// Proxied links depend on the size each view asks for, so a copy saved here would never be read.
	if (url == null || !isRemoteCoverUrl(url) || settings.imagesProxy >= 0) return false
	val request = ImageRequest.Builder(context)
		.data(url)
		.mangaExtra(manga)
		.stableMangaCoverKey(manga, url)
		.memoryCachePolicy(CachePolicy.DISABLED)
		.diskCachePolicy(if (refresh) CachePolicy.WRITE_ONLY else CachePolicy.ENABLED)
		.size(64) // only the downloaded file matters; a tiny decode keeps this cheap
		.build()
	if (execute(request) !is SuccessResult) return false
	if (refresh) {
		memoryCache?.run { keys.filter { it.key == url }.forEach(::remove) }
	}
	return true
}

fun isRemoteCoverUrl(url: String?): Boolean =
	url != null && (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))

fun ImageRequest.Builder.bookmarkExtra(bookmark: Bookmark): ImageRequest.Builder = apply {
	extras[bookmarkKey] = bookmark
	mangaSourceExtra(bookmark.manga.source)
}

suspend fun ImageLoader.fetch(data: Any, options: Options): FetchResult? {
	val mappedData = components.map(data, options)
	val fetcher = components.newFetcher(mappedData, options, this)?.first
	return fetcher?.fetch()
}

val mangaKey = Extras.Key<Manga?>(null)
val bookmarkKey = Extras.Key<Bookmark?>(null)
val mangaSourceKey = Extras.Key<MangaSource?>(null)

@CheckResult
fun SourceFetchResult.copyWithNewSource(): SourceFetchResult = SourceFetchResult(
	source = ImageSource(
		source = source.fileSystem.source(source.file()).buffer(),
		fileSystem = source.fileSystem,
		metadata = source.metadata,
	),
	mimeType = mimeType,
	dataSource = dataSource,
)
