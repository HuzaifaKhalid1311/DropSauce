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
private val AVIF_MIME = MimeType("image/avif")

// ponytail: header sniff (ISO-BMFF `ftyp` + avif/avis brand) instead of full parse; enough since
// we only need to divert AVIF away from the crashing platform path.
fun isAvifHeader(head: ByteArray, length: Int = head.size): Boolean {
	if (length < 12) return false
	val s = String(head, 0, length, Charsets.ISO_8859_1)
	return s.contains("ftyp") && (s.contains("avif") || s.contains("avis"))
}

private fun Uri.sniffAvif(context: Context): Boolean = runCatching {
	context.contentResolver.openInputStream(this)?.use { stream ->
		val head = ByteArray(64)
		val n = stream.read(head)
		isAvifHeader(head, n)
	} ?: false
}.getOrDefault(false)

private fun Uri.decodeAvif(context: Context): Bitmap =
	context.contentResolver.openInputStream(this).use { stream ->
		requireNotNull(stream) { "Cannot open image stream for $this" }
		BitmapDecoderCompat.decode(stream, AVIF_MIME)
	}

class AvifCapableImageDecoder(private val bitmapConfig: Bitmap.Config) : ImageDecoder {

	private val delegate = SkiaImageDecoder(bitmapConfig)

	override fun decode(context: Context, uri: Uri, sampleSize: Int): Bitmap =
		if (uri.sniffAvif(context)) {
			val bmp = uri.decodeAvif(context)
			if (sampleSize > 1) {
				bmp.scale(bmp.width / sampleSize, bmp.height / sampleSize).also { if (it !== bmp) bmp.recycle() }
			} else {
				bmp
			}
		} else {
			delegate.decode(context, uri, sampleSize)
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
		if (uri.sniffAvif(context)) {
			val bmp = uri.decodeAvif(context)
			full = bmp
			return Point(bmp.width, bmp.height)
		}
		return newDelegate().also { delegate = it }.init(context, uri)
	}

	override fun init(context: Context, source: ImageSource): Point {
		val uri = (source as? ImageSource.Uri)?.uri
		if (uri != null && uri.sniffAvif(context)) {
			val bmp = uri.decodeAvif(context)
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
