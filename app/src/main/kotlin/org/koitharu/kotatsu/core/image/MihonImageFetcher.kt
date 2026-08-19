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
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.closeQuietly
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.util.ext.mangaSourceKey
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
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
	private val httpSource: HttpSource,
	private val url: String,
	private val options: Options,
	private val imageLoader: ImageLoader,
	private val diskCacheKeyLazy: Lazy<String?>,
) : Fetcher {

	override suspend fun fetch(): FetchResult {
		val diskCacheKey = diskCacheKeyLazy.value
		readFromDiskCache(diskCacheKey)?.let { snapshot ->
			return SourceFetchResult(
				source = snapshot.toImageSource(diskCacheKey!!),
				mimeType = null,
				dataSource = DataSource.DISK,
			)
		}
		val request = Request.Builder()
			.url(url)
			.headers(httpSource.headers)
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
			return SourceFetchResult(
				source = ImageSource(response.body.source(), options.fileSystem),
				mimeType = response.body.contentType()?.toString(),
				dataSource = DataSource.NETWORK,
			)
		} catch (e: Throwable) {
			response.closeQuietly()
			throw e
		}
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

	class Factory : Fetcher.Factory<CoilUri> {

		override fun create(data: CoilUri, options: Options, imageLoader: ImageLoader): Fetcher? {
			val scheme = data.scheme
			if (scheme != "http" && scheme != "https") {
				return null
			}
			val source = options.extras[mangaSourceKey]?.unwrap() as? MihonMangaSource ?: return null
			val httpSource = source.catalogueSource as? HttpSource ?: return null
			return MihonImageFetcher(
				httpSource = httpSource,
				url = data.toString(),
				options = options,
				imageLoader = imageLoader,
				diskCacheKeyLazy = lazy { imageLoader.components.key(data, options) },
			)
		}
	}
}
