package org.koitharu.kotatsu.core.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.ForwardingSource
import okio.buffer
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.util.ext.mangaSourceKey
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import javax.inject.Provider
import coil3.Uri as CoilUri

/**
 * Fetches cover/thumbnail images for Mihon (extension) sources through the extension's own
 * [HttpSource.client] using [HttpSource.headers] — exactly like Mihon's own MangaCoverFetcher.
 *
 * Going through the app's shared OkHttp client instead (the default Coil network fetcher) makes
 * some sources reject the request (e.g. Comick returns HTTP 403 / a Cloudflare block) even though
 * reading works, because the extension client is what the source expects to serve images. This
 * fetcher restores parity: same client, same headers as the read path.
 */
class MihonImageFetcher(
	private val data: CoilUri,
	private val options: Options,
	private val imageLoader: ImageLoader,
	private val diskCacheKeyLazy: Lazy<String?>,
	/** Null when the extension turned out not to be installed. */
	private val httpSource: suspend () -> HttpSource?,
) : Fetcher {

	override suspend fun fetch(): FetchResult? {
		val diskCacheKey = diskCacheKeyLazy.value
		readFromDiskCache(diskCacheKey)?.let { snapshot ->
			return SourceFetchResult(
				source = snapshot.toImageSource(diskCacheKey!!),
				mimeType = null,
				dataSource = DataSource.DISK,
			)
		}
		val httpSource = httpSource() ?: return fetchWithNextFetcher()
		val request = Request.Builder()
			.url(data.toString())
			.headers(httpSource.headers)
			// Like Mihon: the image is kept in Coil's disk cache, so don't store it in OkHttp's too.
			.cacheControl(CACHE_CONTROL_NO_STORE)
			.build()
		val response = withContext(Dispatchers.IO) {
			httpSource.client.newCall(request).awaitSuccess()
		}
		try {
			writeToDiskCache(response, diskCacheKey)?.let { snapshot ->
				val mimeType = response.body.contentType()?.toString()
				// The snapshot owns the cached bytes, not the network response. Closing here
				// releases the extension client's socket back to Mihon's shared connection pool.
				response.close()
				return SourceFetchResult(
					source = snapshot.toImageSource(diskCacheKey!!),
					mimeType = mimeType,
					dataSource = DataSource.NETWORK,
				)
			}
			// No disk cache available — stream the response directly to the decoder.
			val forwardingSource = object : ForwardingSource(response.body.source()) {
				override fun close() {
					try {
						super.close()
					} finally {
						response.closeQuietly()
					}
				}
			}.buffer()
			return SourceFetchResult(
				source = ImageSource(forwardingSource, options.fileSystem),
				mimeType = response.body.contentType()?.toString(),
				dataSource = DataSource.NETWORK,
			)
		} catch (e: Throwable) {
			response.closeQuietly()
			throw e
		}
	}

	/** What would have run without this fetcher: the default network fetcher with the app client. */
	private suspend fun fetchWithNextFetcher(): FetchResult? {
		val components = imageLoader.components
		val index = components.fetcherFactories.indexOfFirst { it.first is Factory }
		return components.newFetcher(data, options, imageLoader, index + 1)?.first?.fetch()
	}

	private fun readFromDiskCache(key: String?): DiskCache.Snapshot? {
		if (key == null || !options.diskCachePolicy.readEnabled) return null
		return imageLoader.diskCache?.openSnapshot(key)
	}

	private fun writeToDiskCache(response: Response, key: String?): DiskCache.Snapshot? {
		if (key == null || !options.diskCachePolicy.writeEnabled) return null
		// Sources sometimes answer HTTP 200 with an error page or a JSON body. Caching that under the
		// cover key would keep the cover broken for good, since the url (and so the key) never changes.
		response.body.contentType()?.let { type ->
			if (type.type.equals("text", true) || type.subtype.equals("json", true)) return null
		}
		val diskCache = imageLoader.diskCache ?: return null
		val editor = diskCache.openEditor(key) ?: return null
		return try {
			diskCache.fileSystem.write(editor.data) {
				response.body.source().readAll(this)
			}
			editor.commitAndOpenSnapshot()
		} catch (e: Exception) {
			runCatching { editor.abort() }
			throw e
		}
	}

	private fun DiskCache.Snapshot.toImageSource(key: String): ImageSource = ImageSource(
		file = data,
		fileSystem = checkNotNull(imageLoader.diskCache).fileSystem,
		diskCacheKey = key,
		closeable = this,
	)

	class Factory(
		private val extensionManager: Provider<MihonExtensionManager>,
	) : Fetcher.Factory<CoilUri> {

		override fun create(data: CoilUri, options: Options, imageLoader: ImageLoader): Fetcher? {
			val scheme = data.scheme
			if (scheme != "http" && scheme != "https") {
				return null
			}
			val httpSource: suspend () -> HttpSource? = when (val source = options.extras[mangaSourceKey]?.unwrap()) {
				is MihonMangaSource -> {
					val resolved = source.catalogueSource as? HttpSource ?: return null
					{ resolved }
				}
				// Covers requested at app start, before extensions finished loading (the library
				// lists). Like Mihon's cover fetcher, serve the disk cache straight away and wait for
				// the extension only on a miss - instead of fetching through the app's client, which
				// some sources reject, under a key the extension path never reads back.
				is MissingMangaSource -> {
					if (!source.name.startsWith("MIHON_")) return null
					val manager = extensionManager.get()
					if (manager.isReady.value) return null
					{
						manager.ensureReady()
						(MangaSource(source.name) as? MihonMangaSource)?.catalogueSource as? HttpSource
					}
				}
				else -> return null
			}
			return MihonImageFetcher(
				data = data,
				options = options,
				imageLoader = imageLoader,
				diskCacheKeyLazy = lazy { imageLoader.components.key(data, options) },
				httpSource = httpSource,
			)
		}
	}

	private companion object {

		val CACHE_CONTROL_NO_STORE: CacheControl = CacheControl.Builder().noStore().build()
	}
}
