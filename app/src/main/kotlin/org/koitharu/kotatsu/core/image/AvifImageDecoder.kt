package org.koitharu.kotatsu.core.image

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.maxBitmapSize
import coil3.util.component1
import coil3.util.component2
import com.davemorrissey.labs.subscaleview.decoder.ImageDecodeException
import kotlinx.coroutines.runInterruptible
import okio.Buffer
import org.aomedia.avif.android.AvifDecoder
import org.koitharu.kotatsu.core.util.ext.readByteBuffer

class AvifImageDecoder(
	private val source: ImageSource,
	private val options: Options,
) : Decoder {

	override suspend fun decode(): DecodeResult = runInterruptible {
		val bytes = source.source().readByteBuffer()
		val decoder = AvifDecoder.create(bytes) ?: throw ImageDecodeException(
			uri = source.fileOrNull()?.toString(),
			format = "avif",
			message = "Requested to decode byte buffer which cannot be handled by AvifDecoder",
		)
		try {
			val config = if (decoder.depth == 8 || decoder.alphaPresent) {
				Bitmap.Config.ARGB_8888
			} else {
				Bitmap.Config.RGB_565
			}
			val bitmap = createBitmap(decoder.width, decoder.height, config)
			val result = decoder.nextFrame(bitmap)
			if (result != 0) {
				bitmap.recycle()
				throw ImageDecodeException(
					uri = source.fileOrNull()?.toString(),
					format = "avif",
					message = AvifDecoder.resultToString(result),
				)
			}
			// downscaling
			val (dstWidth, dstHeight) = DecodeUtils.computeDstSize(
				srcWidth = bitmap.width,
				srcHeight = bitmap.height,
				targetSize = options.size,
				scale = options.scale,
				maxSize = options.maxBitmapSize,
			)
			if (dstWidth < bitmap.width || dstHeight < bitmap.height) {
				val scaled = bitmap.scale(dstWidth, dstHeight)
				bitmap.recycle()
				DecodeResult(
					image = scaled.asImage(),
					isSampled = true,
				)
			} else {
				DecodeResult(
					image = bitmap.asImage(),
					isSampled = false,
				)
			}
		} finally {
			decoder.release()
		}
	}

	class Factory : Decoder.Factory {

		override fun create(
			result: SourceFetchResult,
			options: Options,
			imageLoader: ImageLoader
		): Decoder? = if (isApplicable(result)) {
			AvifImageDecoder(result.source, options)
		} else {
			null
		}

		override fun equals(other: Any?) = other is Factory

		override fun hashCode() = javaClass.hashCode()

		/**
		 * The mime type is unreliable: images served from the disk cache carry none at all, and some
		 * sources label AVIF as `image/jpeg`. In both cases the bytes would fall through to the
		 * platform decoder, which fails on many devices ("getPixels failed with error invalid input")
		 * even though the bundled software decoder handles them. So sniff the header as well.
		 */
		private fun isApplicable(result: SourceFetchResult): Boolean {
			if (result.mimeType == "image/avif") {
				return true
			}
			return runCatching {
				result.source.source().peek().use { peek ->
					val head = Buffer()
					peek.read(head, 64L)
					isAvifHeader(head.readByteArray())
				}
			}.getOrDefault(false)
		}
	}
}
