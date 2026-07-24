package org.koitharu.kotatsu.core.parser.favicon

import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.toAndroidUri
import kotlinx.coroutines.runInterruptible
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.parser.EmptyMangaRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.fetch
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.mihon.MihonExtensionLoader
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.MihonMangaRepository
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import javax.inject.Inject
import java.io.File
import coil3.Uri as CoilUri

class FaviconFetcher(
	private val uri: Uri,
	private val options: Options,
	private val imageLoader: ImageLoader,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val mihonExtensionManager: MihonExtensionManager,
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

			else -> {
				// LazyMihonMangaRepository (extension not yet loaded) or any other unknown type.
				// Try to resolve the extension icon; fall back to generic icon if unavailable.
				val resolvedMihonSource = resolveMihonSource(mangaSource.name)
				if (resolvedMihonSource != null) {
					fetchMihonIcon(resolvedMihonSource)
				} else {
					imageLoader.fetch(R.drawable.ic_manga_source, options)
				}
			}
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
		}.getOrNull() ?: runCatching {
			runInterruptible {
				val ctx = options.context
				val extFile = File(MihonExtensionLoader.getPrivateExtensionDir(ctx), "${source.pkgName}.ext")
				if (!extFile.isFile) return@runInterruptible null
				val pm = ctx.packageManager
				@Suppress("DEPRECATION")
				val pkgInfo = pm.getPackageArchiveInfo(extFile.absolutePath, PackageManager.GET_META_DATA) ?: return@runInterruptible null
				val appInfo = pkgInfo.applicationInfo ?: return@runInterruptible null
				if (appInfo.sourceDir == null) appInfo.sourceDir = extFile.absolutePath
				if (appInfo.publicSourceDir == null) appInfo.publicSourceDir = extFile.absolutePath
				appInfo.loadIcon(pm)
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

	class Factory @Inject constructor(
		private val mangaRepositoryFactory: MangaRepository.Factory,
		private val mihonExtensionManager: MihonExtensionManager,
	) : Fetcher.Factory<CoilUri> {

		override fun create(
			data: CoilUri,
			options: Options,
			imageLoader: ImageLoader
		): Fetcher? = if (data.scheme == URI_SCHEME_FAVICON) {
			FaviconFetcher(
				uri = data.toAndroidUri(),
				options = options,
				imageLoader = imageLoader,
				mangaRepositoryFactory = mangaRepositoryFactory,
				mihonExtensionManager = mihonExtensionManager,
			)
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

