package org.koitharu.kotatsu.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
import org.koitharu.kotatsu.core.util.ext.isZipUri
import java.io.InputStream
import java.util.zip.ZipFile

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
fun sniffBundledFormat(head: ByteArray, length: Int = head.size): MimeType? = when {
	isJxlHeader(head, length) -> JXL_MIME
	isAvifHeader(head, length) -> AVIF_MIME
	else -> null
}

private inline fun <T> Uri.withImageStream(context: Context, block: (InputStream?) -> T): T =
	if (isZipUri()) {
		ZipFile(schemeSpecificPart).use { zip ->
			val entry = fragment?.let(zip::getEntry)
			if (entry != null) {
				zip.getInputStream(entry).use(block)
			} else {
				block(null)
			}
		}
	} else {
		context.contentResolver.openInputStream(this).use(block)
	}

private fun Uri.sniffBundledFormat(context: Context): MimeType? = runCatching {
	withImageStream(context) { stream ->
		stream ?: return@withImageStream null
		val head = ByteArray(64)
		var total = 0
		while (total < head.size) {
			val n = stream.read(head, total, head.size - total)
			if (n <= 0) break
			total += n
		}
		if (total > 0) sniffBundledFormat(head, total) else null
	}
}.getOrNull()

private object BundledBitmapStash {
	private const val MAX_ENTRIES = 2
	private val entries = LinkedHashMap<Uri, Bitmap>(4, 0.75f, true)

	@Synchronized
	fun take(uri: Uri): Bitmap? = entries.remove(uri)?.takeUnless { it.isRecycled }

	@Synchronized
	fun put(uri: Uri, bitmap: Bitmap) {
		if (bitmap.isRecycled) return
		val prev = entries.put(uri, bitmap)
		if (prev != null && prev !== bitmap && !prev.isRecycled) {
			prev.recycle()
		}
		while (entries.size > MAX_ENTRIES) {
			val eldest = entries.entries.iterator().next()
			entries.remove(eldest.key)
			if (!eldest.value.isRecycled) {
				eldest.value.recycle()
			}
		}
	}
}

private fun Bitmap.convertConfigIfNeeded(targetConfig: Bitmap.Config): Bitmap {
	if (targetConfig != Bitmap.Config.RGB_565 || config == Bitmap.Config.RGB_565) {
		return this
	}
	val converted = copy(Bitmap.Config.RGB_565, false) ?: return this
	if (converted !== this) {
		recycle()
	}
	return converted
}

private fun Uri.decodeBundled(context: Context, format: MimeType): Bitmap =
	withImageStream(context) { stream ->
		requireNotNull(stream) { "Cannot open image stream for $this" }
		BitmapDecoderCompat.decode(stream, format)
	}

class AvifCapableImageDecoder(private val bitmapConfig: Bitmap.Config) : ImageDecoder {

	private val delegate = SkiaImageDecoder(bitmapConfig)

	override fun decode(context: Context, uri: Uri, sampleSize: Int): Bitmap {
		val format = uri.sniffBundledFormat(context) ?: return delegate.decode(context, uri, sampleSize)
		val bmp = BundledBitmapStash.take(uri) ?: uri.decodeBundled(context, format)
		return if (sampleSize > 1) {
			val w = (bmp.width / sampleSize).coerceAtLeast(1)
			val h = (bmp.height / sampleSize).coerceAtLeast(1)
			val scaled = bmp.scale(w, h)
			if (scaled !== bmp) {
				BundledBitmapStash.put(uri, bmp)
			}
			scaled.convertConfigIfNeeded(bitmapConfig)
		} else {
			bmp.convertConfigIfNeeded(bitmapConfig)
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
	private var appContext: Context? = null
	private var sourceUri: Uri? = null
	@Volatile
	private var bundledFormat: MimeType? = null
	@Volatile
	private var isInitialized = false
	@Volatile
	private var recycled = false
	private var servedFullResTile = false
	private var full: Bitmap? = null

	private fun newDelegate(): ImageRegionDecoder = if (lowRam) {
		SkiaImageRegionDecoder(bitmapConfig)
	} else {
		SkiaPooledImageRegionDecoder(bitmapConfig)
	}

	private fun initBundled(context: Context, uri: Uri, format: MimeType): Point {
		val bounds = runCatching {
			uri.withImageStream(context) { it?.let(BitmapDecoderCompat::decodeBounds) }
		}.getOrNull()
		synchronized(this) {
			appContext = context.applicationContext
			sourceUri = uri
			bundledFormat = format
			isInitialized = true
			recycled = false
			servedFullResTile = false
			if (bounds != null) {
				return Point(bounds.width, bounds.height)
			}
			val bmp = BundledBitmapStash.take(uri) ?: uri.decodeBundled(context, format)
			full = bmp
			return Point(bmp.width, bmp.height)
		}
	}

	override fun init(context: Context, uri: Uri): Point {
		val format = uri.sniffBundledFormat(context)
			?: return newDelegate().also { delegate = it }.init(context, uri)
		return initBundled(context, uri, format)
	}

	override fun init(context: Context, source: ImageSource): Point {
		val uri = (source as? ImageSource.Uri)?.uri
		val format = uri?.sniffBundledFormat(context)
		if (uri != null && format != null) {
			return initBundled(context, uri, format)
		}
		// Delegate with the original ImageSource so any region/crop it carries is preserved.
		return newDelegate().also { delegate = it }.init(context, source)
	}

	override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
		if (bundledFormat != null) {
			val src = synchronized(this) {
				check(!recycled) { "AvifCapableRegionDecoder has already been recycled" }
				if (sampleSize == 1 && bitmapConfig != Bitmap.Config.RGB_565) {
					servedFullResTile = true
				}
				full?.takeIf { !it.isRecycled } ?: run {
					val u = requireNotNull(sourceUri)
					(BundledBitmapStash.take(u) ?: u.decodeBundled(requireNotNull(appContext), requireNotNull(bundledFormat)))
						.also { full = it }
				}
			}
			val w = (sRect.width() / sampleSize).coerceAtLeast(1)
			val h = (sRect.height() / sampleSize).coerceAtLeast(1)
			val out = Bitmap.createBitmap(w, h, src.config ?: bitmapConfig)
			Canvas(out).drawBitmap(src, sRect, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
			return out
		}
		return requireNotNull(delegate).decodeRegion(sRect, sampleSize)
	}

	override val isReady: Boolean
		get() = (!recycled && isInitialized) || delegate?.isReady == true

	override fun recycle() {
		synchronized(this) {
			recycled = true
			isInitialized = false
			val bmp = full
			val uri = sourceUri
			full = null
			sourceUri = null
			appContext = null
			bundledFormat = null
			if (bmp != null && !bmp.isRecycled) {
				if (!servedFullResTile && uri != null) {
					BundledBitmapStash.put(uri, bmp)
				} else {
					bmp.recycle()
				}
			}
		}
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
