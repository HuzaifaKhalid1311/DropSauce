package org.koitharu.kotatsu.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import coil3.Extras
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.getExtra
import coil3.request.Options
import coil3.request.allowRgb565
import coil3.request.bitmapConfig
import coil3.request.colorSpace
import coil3.request.premultipliedAlpha
import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import coil3.size.isOriginal
import coil3.size.pxOrElse
import okio.Buffer
import org.koitharu.kotatsu.core.util.ext.copyWithNewSource
import kotlin.math.roundToInt

class RegionBitmapDecoder(
	private val fetchResult: SourceFetchResult,
	private val options: Options,
	private val imageLoader: ImageLoader,
) : Decoder {

	override suspend fun decode(): DecodeResult? {
		val regionDecoder = BitmapDecoderCompat.createRegionDecoder(fetchResult.source.source().inputStream())
		if (regionDecoder == null) {
			val revivedFetchResult = fetchResult.copyWithNewSource()
			return try {
				decodeBundledRegion(revivedFetchResult) ?: run {
					val fallbackDecoder = imageLoader.components.newDecoder(
						result = revivedFetchResult,
						options = options,
						imageLoader = imageLoader,
						startIndex = 0,
					)?.first
					if (fallbackDecoder == null || fallbackDecoder is RegionBitmapDecoder) {
						null
					} else {
						fallbackDecoder.decode()
					}
				}
			} finally {
				revivedFetchResult.source.close()
			}
		}
		val bitmapOptions = BitmapFactory.Options()
		return try {
			val rect = bitmapOptions.configureScale(regionDecoder.width, regionDecoder.height)
			bitmapOptions.configureConfig()
			val bitmap = regionDecoder.decodeRegion(rect, bitmapOptions)
			bitmap.density = options.context.resources.displayMetrics.densityDpi
			DecodeResult(
				image = bitmap.asImage(),
				isSampled = true,
			)
		} finally {
			regionDecoder.recycle()
		}
	}

	/**
	 * JPEG XL (and AVIF where the platform cannot read it) has no region decoder: decode the whole
	 * image with the bundled decoder and cut the same region out of it, like every other format,
	 * instead of squashing a full webtoon page into the thumbnail.
	 */
	private fun decodeBundledRegion(result: SourceFetchResult): DecodeResult? {
		val source = result.source.source()
		val head = Buffer().also { source.peek().read(it, 64L) }.readByteArray()
		val format = sniffBundledFormat(head) ?: return null
		val full = BitmapDecoderCompat.decode(source.inputStream(), format)
		val dstWidth = options.size.widthPx(options.scale) { full.width }
		val dstHeight = options.size.heightPx(options.scale) { full.height }
		val rect = regionRect(full.width, full.height, dstWidth, dstHeight)
		rect.intersect(0, 0, full.width, full.height)
		var scale = DecodeUtils.computeSizeMultiplier(rect.width(), rect.height(), dstWidth, dstHeight, options.scale, Size.ORIGINAL)
		if (options.precision == Precision.INEXACT) {
			scale = scale.coerceAtMost(1.0)
		}
		val matrix = Matrix().apply { setScale(scale.toFloat(), scale.toFloat()) }
		val bitmap = Bitmap.createBitmap(full, rect.left, rect.top, rect.width(), rect.height(), matrix, true)
		if (bitmap !== full) full.recycle()
		bitmap.density = options.context.resources.displayMetrics.densityDpi
		return DecodeResult(image = bitmap.asImage(), isSampled = true)
	}

	/** Compute and set the scaling properties for [BitmapFactory.Options]. */
	private fun BitmapFactory.Options.configureScale(srcWidth: Int, srcHeight: Int): Rect {
		val dstWidth = options.size.widthPx(options.scale) { srcWidth }
		val dstHeight = options.size.heightPx(options.scale) { srcHeight }
		val rect = regionRect(srcWidth, srcHeight, dstWidth, dstHeight)

		// Calculate the image's sample size.
		inSampleSize = DecodeUtils.calculateInSampleSize(
			srcWidth = rect.width(),
			srcHeight = rect.height(),
			dstWidth = dstWidth,
			dstHeight = dstHeight,
			scale = options.scale,
		)

		// Calculate the image's density scaling multiple.
		var scale = DecodeUtils.computeSizeMultiplier(
			srcWidth = rect.width() / inSampleSize.toDouble(),
			srcHeight = rect.height() / inSampleSize.toDouble(),
			dstWidth = dstWidth.toDouble(),
			dstHeight = dstHeight.toDouble(),
			scale = options.scale,
			maxSize = Size.ORIGINAL,
		)

		// Only upscale the image if the options require an exact size.
		if (options.precision == Precision.INEXACT) {
			scale = scale.coerceAtMost(1.0)
		}

		inScaled = scale != 1.0
		if (inScaled) {
			if (scale > 1) {
				// Upscale
				inDensity = (Int.MAX_VALUE / scale).roundToInt()
				inTargetDensity = Int.MAX_VALUE
			} else {
				// Downscale
				inDensity = Int.MAX_VALUE
				inTargetDensity = (Int.MAX_VALUE * scale).roundToInt()
			}
		}
		return rect
	}

	/** The part of the image that fills the requested box: centred, or from the saved scroll position. */
	private fun regionRect(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): Rect {
		val srcRatio = srcWidth / srcHeight.toDouble()
		val dstRatio = dstWidth / dstHeight.toDouble()
		val rect = if (srcRatio < dstRatio) {
			// probably manga
			Rect(0, 0, srcWidth, (srcWidth / dstRatio).toInt().coerceAtLeast(1))
		} else {
			Rect(0, 0, (srcHeight / dstRatio).toInt().coerceAtLeast(1), srcHeight)
		}
		val scroll = options.getExtra(regionScrollKey)
		if (scroll == SCROLL_UNDEFINED) {
			rect.offsetTo(
				(srcWidth - rect.width()) / 2,
				(srcHeight - rect.height()) / 2,
			)
		} else {
			rect.offsetTo(
				(srcWidth - rect.width()) / 2,
				(scroll * dstRatio).toInt().coerceAtMost(srcHeight - rect.height()),
			)
		}
		return rect
	}

	private fun BitmapFactory.Options.configureConfig() {
		var config = options.bitmapConfig

		inMutable = false

		if (Build.VERSION.SDK_INT >= 26 && options.colorSpace != null) {
			inPreferredColorSpace = options.colorSpace
		}
		inPremultiplied = options.premultipliedAlpha

		// Decode the image as RGB_565 as an optimization if allowed.
		if (options.allowRgb565 && config == Bitmap.Config.ARGB_8888 && outMimeType == "image/jpeg") {
			config = Bitmap.Config.RGB_565
		}

		// High color depth images must be decoded as either RGBA_F16 or HARDWARE.
		if (Build.VERSION.SDK_INT >= 26 && outConfig == Bitmap.Config.RGBA_F16 && config != Bitmap.Config.HARDWARE) {
			config = Bitmap.Config.RGBA_F16
		}

		inPreferredConfig = config
	}

	object Factory : Decoder.Factory {

		override fun create(
			result: SourceFetchResult,
			options: Options,
			imageLoader: ImageLoader
		): Decoder = RegionBitmapDecoder(result, options, imageLoader)

		override fun equals(other: Any?) = other is Factory

		override fun hashCode() = javaClass.hashCode()
	}

	companion object {

		const val SCROLL_UNDEFINED = -1
		val regionScrollKey = Extras.Key(SCROLL_UNDEFINED)

		private inline fun Size.widthPx(scale: Scale, original: () -> Int): Int {
			return if (isOriginal) original() else width.toPx(scale)
		}

		private inline fun Size.heightPx(scale: Scale, original: () -> Int): Int {
			return if (isOriginal) original() else height.toPx(scale)
		}

		private fun Dimension.toPx(scale: Scale) = pxOrElse {
			when (scale) {
				Scale.FILL -> Int.MIN_VALUE
				Scale.FIT -> Int.MAX_VALUE
			}
		}
	}
}
