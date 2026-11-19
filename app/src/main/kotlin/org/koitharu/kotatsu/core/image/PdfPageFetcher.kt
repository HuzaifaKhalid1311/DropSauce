package org.koitharu.kotatsu.core.image

import android.net.Uri
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.Scale
import coil3.size.pxOrElse
import coil3.toAndroidUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.koitharu.kotatsu.core.util.ext.isPdfUri
import org.koitharu.kotatsu.local.data.input.PdfPages
import coil3.Uri as CoilUri

/** Covers and page thumbnails of a PDF kept as-is, drawn at the size they are shown. */
class PdfPageFetcher(
	private val uri: Uri,
	private val options: Options,
) : Fetcher {

	override suspend fun fetch() = runInterruptible(Dispatchers.IO) {
		val (file, index) = PdfPages.parseUri(uri)
		val bitmap = PdfPages.render(
			file = file,
			index = index,
			maxWidth = options.size.width.pxOrElse { DEFAULT_SIZE }.coerceAtMost(MAX_SIZE),
			maxHeight = options.size.height.pxOrElse { DEFAULT_SIZE }.coerceAtMost(MAX_SIZE),
			fill = options.scale == Scale.FILL,
		)
		ImageFetchResult(
			image = bitmap.asImage(),
			isSampled = true,
			dataSource = DataSource.DISK,
		)
	}

	class Factory : Fetcher.Factory<CoilUri> {

		override fun create(data: CoilUri, options: Options, imageLoader: ImageLoader): Fetcher? {
			val androidUri = data.toAndroidUri()
			return if (androidUri.isPdfUri()) PdfPageFetcher(androidUri, options) else null
		}
	}

	private companion object {
		const val DEFAULT_SIZE = 1024
		const val MAX_SIZE = 2048
	}
}
