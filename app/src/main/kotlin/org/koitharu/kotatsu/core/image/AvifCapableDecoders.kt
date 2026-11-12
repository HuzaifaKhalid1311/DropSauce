package org.koitharu.kotatsu.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import androidx.core.graphics.scale
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.ImageDecoder
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import com.davemorrissey.labs.subscaleview.decoder.SkiaImageDecoder
import com.davemorrissey.labs.subscaleview.decoder.SkiaImageRegionDecoder
import com.davemorrissey.labs.subscaleview.decoder.SkiaPooledImageRegionDecoder
import org.koitharu.kotatsu.core.util.ext.MimeType

// Some sources (e.g. Mangago) serve AVIF images mislabelled as image/jpeg. The subsampling
// library's stock Skia decoders hand them to the platform decoder, whose hardware AV1 path
// rejects tall webtoon frames — surfacing as "Cannot decode image - format image/jpeg may not
// be supported". These wrappers sniff the real format and decode AVIF with the app's bundled
// software decoder (BitmapDecoderCompat/AvifDecoder — already present, so no size increase),
// delegating every other format to the untouched Skia decoders.
// JPEG XL goes the same way: Android has no decoder for it at all.
private val AVIF_MIME = MimeType("image/avif")
private val JXL_MIME = MimeType("image/jxl")

const val JXL_HEADER_SIZE = 12
private val JXL_CONTAINER_HEADER = byteArrayOf(0, 0, 0, 0x0C, 'J'.code.toByte(), 'X'.code.toByte(), 'L'.code.toByte(), ' '.code.toByte(), 0x0D, 0x0A, 0x87.toByte(), 0x0A)

/** Bare codestream (`FF 0A`) or the ISO-BMFF container signature. */
fun isJxlHeader(head: ByteArray, length: Int = head.size): Boolean = when {
	length >= 2 && head[0] == 0xFF.toByte() && head[1] == 0x0A.toByte() -> true
	length >= JXL_HEADER_SIZE -> JXL_CONTAINER_HEADER.indices.all { head[it] == JXL_CONTAINER_HEADER[it] }
	else -> false
}

// ponytail: header sniff (ISO-BMFF `ftyp` + avif/avis brand) instead of full parse; enough since
// we only need to divert AVIF away from the crashing platform path.
fun isAvifHeader(head: ByteArray, length: Int = head.size): Boolean {
	if (length < 12) return false
	val s = String(head, 0, length, Charsets.ISO_8859_1)
	return s.contains("ftyp") && (s.contains("avif") || s.contains("avis"))
}

/** The format to decode with a bundled decoder, or null to leave the image to the platform. */
private fun Uri.sniffBundledFormat(context: Context): MimeType? = runCatching {
	context.contentResolver.openInputStream(this)?.use { stream ->
		val head = ByteArray(64)
		val n = stream.read(head)
		when {
			isJxlHeader(head, n) -> JXL_MIME
			isAvifHeader(head, n) -> AVIF_MIME
			else -> null
		}
	}
}.getOrNull()

private fun Uri.decodeBundled(context: Context, format: MimeType): Bitmap =
	context.contentResolver.openInputStream(this).use { stream ->
		requireNotNull(stream) { "Cannot open image stream for $this" }
		BitmapDecoderCompat.decode(stream, format)
	}

class AvifCapableImageDecoder(private val bitmapConfig: Bitmap.Config) : ImageDecoder {

	private val delegate = SkiaImageDecoder(bitmapConfig)

	override fun decode(context: Context, uri: Uri, sampleSize: Int): Bitmap {
		val format = uri.sniffBundledFormat(context) ?: return delegate.decode(context, uri, sampleSize)
		val bmp = uri.decodeBundled(context, format)
		return if (sampleSize > 1) {
			bmp.scale(bmp.width / sampleSize, bmp.height / sampleSize).also { if (it !== bmp) bmp.recycle() }
		} else {
			bmp
		}
	}

	override fun decode(context: Context, source: ImageSource, sampleSize: Int): Bitmap {
		val uri = (source as? ImageSource.Uri)?.uri
		return if (uri != null) decode(context, uri, sampleSize) else delegate.decode(context, source, sampleSize)
	}

	class Factory(override val bitmapConfig: Bitmap.Config) : DecoderFactory<ImageDecoder> {
		override fun make(): ImageDecoder = AvifCapableImageDecoder(bitmapConfig)
	}
}

class AvifCapableRegionDecoder(
	private val bitmapConfig: Bitmap.Config,
	private val lowRam: Boolean,
) : ImageRegionDecoder {

	private var delegate: ImageRegionDecoder? = null
	private var full: Bitmap? = null

	private fun newDelegate(): ImageRegionDecoder = if (lowRam) {
		SkiaImageRegionDecoder(bitmapConfig)
	} else {
		SkiaPooledImageRegionDecoder(bitmapConfig)
	}

	override fun init(context: Context, uri: Uri): Point {
		val format = uri.sniffBundledFormat(context)
			?: return newDelegate().also { delegate = it }.init(context, uri)
		val bmp = uri.decodeBundled(context, format)
		full = bmp
		return Point(bmp.width, bmp.height)
	}

	override fun init(context: Context, source: ImageSource): Point {
		val uri = (source as? ImageSource.Uri)?.uri
		val format = uri?.sniffBundledFormat(context)
		if (uri != null && format != null) {
			val bmp = uri.decodeBundled(context, format)
			full = bmp
			return Point(bmp.width, bmp.height)
		}
		// Delegate with the original ImageSource so any region/crop it carries is preserved.
		return newDelegate().also { delegate = it }.init(context, source)
	}

	override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
		full?.let { src ->
			val w = (sRect.width() / sampleSize).coerceAtLeast(1)
			val h = (sRect.height() / sampleSize).coerceAtLeast(1)
			val out = Bitmap.createBitmap(w, h, src.config ?: bitmapConfig)
			Canvas(out).drawBitmap(src, sRect, Rect(0, 0, w, h), null)
			return out
		}
		return requireNotNull(delegate).decodeRegion(sRect, sampleSize)
	}

	override val isReady: Boolean
		get() = full != null || delegate?.isReady == true

	override fun recycle() {
		full?.recycle()
		full = null
		delegate?.recycle()
		delegate = null
	}

	class Factory(
		override val bitmapConfig: Bitmap.Config,
		private val lowRam: Boolean,
	) : DecoderFactory<ImageRegionDecoder> {
		override fun make(): ImageRegionDecoder = AvifCapableRegionDecoder(bitmapConfig, lowRam)
	}
}
