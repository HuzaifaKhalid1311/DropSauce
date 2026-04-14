package org.koitharu.kotatsu.core.parser.favicon

import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.toAndroidUri
import coil3.toBitmap
import kotlinx.coroutines.runInterruptible
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.parser.EmptyMangaRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.fetch
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.toMimeTypeOrNull
import org.koitharu.kotatsu.local.data.FaviconCache
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.data.LocalStorageCache
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.MihonMangaRepository
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.File
import javax.inject.Inject
import coil3.Uri as CoilUri

class FaviconFetcher(
	private val uri: Uri,
	private val options: Options,
	private val imageLoader: ImageLoader,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val mihonExtensionManager: MihonExtensionManager,
	private val localStorageCache: LocalStorageCache,
) : Fetcher {

	override suspend fun fetch(): FetchResult? {
		val mangaSource = MangaSource(uri.schemeSpecificPart)
		resolveMihonSource(uri.schemeSpecificPart)?.let { return fetchMihonIcon(it) }

		return when (val repo = mangaRepositoryFactory.create(mangaSource)) {
			is MihonMangaRepository -> fetchMihonIcon(repo)
			is EmptyMangaRepository -> {
				val resolvedMihonSource = resolveMihonSource(mangaSource.name)
				if (resolvedMihonSource != null) {
					fetchMihonIcon(resolvedMihonSource)
				} else {
					imageLoader.fetch(R.drawable.ic_manga_source, options)
				}
			}

			is LocalMangaRepository -> imageLoader.fetch(R.drawable.ic_storage, options)

			else -> throw IllegalArgumentException("Unsupported repo ${repo.javaClass.simpleName}")
		}
	}

	private suspend fun fetchMihonIcon(repository: MihonMangaRepository): FetchResult {
		return fetchMihonIcon(repository.source)
	}

	private suspend fun fetchMihonIcon(source: MihonMangaSource): FetchResult {
		val icon = runCatching {
			runInterruptible {
			options.context.packageManager.getApplicationIcon(source.pkgName)
			}
		}.getOrNull() ?: return requireNotNull(imageLoader.fetch(R.drawable.ic_manga_source, options))

		return ImageFetchResult(
			image = icon.nonAdaptive().asImage(),
			isSampled = false,
			dataSource = DataSource.DISK,
		)
	}

	private suspend fun resolveMihonSource(name: String): MihonMangaSource? {
		if (!name.startsWith("MIHON_")) return null
		mihonExtensionManager.ensureReady()
		val sourceId = name.removePrefix("MIHON_").substringBefore(':').toLongOrNull()
		val existing = sourceId?.let { mihonExtensionManager.getMihonMangaSourceById(it) }
			?: mihonExtensionManager.getMihonMangaSourceByName(name)
		if (existing != null) return existing
		mihonExtensionManager.ensureReady(forceRefresh = true)
		return sourceId?.let { mihonExtensionManager.getMihonMangaSourceById(it) }
			?: mihonExtensionManager.getMihonMangaSourceByName(name)
		}

	private suspend fun writeToCache(key: String, result: FetchResult): FetchResult = runCatchingCancellable {
		when (result) {
			is ImageFetchResult -> {
				if (result.dataSource == DataSource.NETWORK) {
					localStorageCache.set(key, result.image.toBitmap()).asFetchResult()
				} else {
					result
				}
			}

			is SourceFetchResult -> {
				if (result.dataSource == DataSource.NETWORK) {
					result.source.source().use {
						localStorageCache.set(key, it, result.mimeType?.toMimeTypeOrNull()).asFetchResult()
					}
				} else {
					result
				}
			}
		}
	}.onFailure {
		it.printStackTraceDebug()
	}.getOrDefault(result)

	private fun File.asFetchResult() = SourceFetchResult(
		source = ImageSource(toOkioPath(), FileSystem.SYSTEM),
		mimeType = MimeTypes.probeMimeType(this)?.toString(),
		dataSource = DataSource.DISK,
	)

	class Factory @Inject constructor(
		private val mangaRepositoryFactory: MangaRepository.Factory,
		private val mihonExtensionManager: MihonExtensionManager,
		@FaviconCache private val faviconCache: LocalStorageCache,
	) : Fetcher.Factory<CoilUri> {

		override fun create(
			data: CoilUri,
			options: Options,
			imageLoader: ImageLoader
		): Fetcher? = if (data.scheme == URI_SCHEME_FAVICON) {
			FaviconFetcher(data.toAndroidUri(), options, imageLoader, mangaRepositoryFactory, mihonExtensionManager, faviconCache)
		} else {
			null
		}
	}

	private companion object {

		private fun Drawable.nonAdaptive() =
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && this is AdaptiveIconDrawable) {
				LayerDrawable(arrayOf(background, foreground))
			} else {
				this
			}

	}
}

