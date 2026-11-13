package org.koitharu.kotatsu.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Size
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import com.davemorrissey.labs.subscaleview.decoder.ImageDecodeException
import okio.IOException
import okio.buffer
import okio.source
import org.aomedia.avif.android.AvifDecoder
import org.aomedia.avif.android.AvifDecoder.Info
import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.readByteBuffer
import org.koitharu.kotatsu.core.util.ext.toByteBuffer
import org.koitharu.kotatsu.core.util.ext.toMimeTypeOrNull
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer

object BitmapDecoderCompat {

	private const val FORMAT_AVIF = "avif"
	private const val FORMAT_JXL = "jxl"
	private const val JXL_BOUNDS_PREFIX_BYTES = 4096
	private val JXL_MIME = MimeType("image/jxl")

	@Blocking
	fun decode(file: File): Bitmap = when (val format = probeMimeType(file)?.subtype) {
		FORMAT_AVIF -> file.source().buffer().use { decodeAvif(it.readByteBuffer()) }
		FORMAT_JXL -> decodeJxl(file.readBytes())
		else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			ImageDecoder.decodeBitmap(ImageDecoder.createSource(file))
		} else {
			checkBitmapNotNull(BitmapFactory.decodeFile(file.absolutePath), format)
		}
	}

	@Blocking
	fun decode(stream: InputStream, type: MimeType?, isMutable: Boolean = false): Bitmap {
		val (buffered, detectedType) = if (type != null) null to type else sniffStream(stream)
		val sourceStream = buffered ?: stream
		val format = detectedType?.subtype
		if (format == FORMAT_AVIF) {
			return decodeAvif(sourceStream.toByteBuffer())
		}
		if (format == FORMAT_JXL) {
			return decodeJxl(sourceStream.readBytes())
		}
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
			val opts = BitmapFactory.Options()
			opts.inMutable = isMutable
			return checkBitmapNotNull(BitmapFactory.decodeStream(sourceStream, null, opts), format)
		}
		val byteBuffer = sourceStream.toByteBuffer()
		return if (AvifDecoder.isAvifImage(byteBuffer)) {
			decodeAvif(byteBuffer)
		} else {
			ImageDecoder.decodeBitmap(ImageDecoder.createSource(byteBuffer), DecoderConfigListener(isMutable))
		}
	}

	/** Image dimensions without full pixel decode when supported (platform codecs, JXL, AVIF). */
	@Blocking
	fun decodeBounds(stream: InputStream): Size? {
		val (buffered, format) = sniffStream(stream)
		return when (format?.subtype) {
			FORMAT_JXL -> {
				val headSlice = readPrefix(buffered, JXL_BOUNDS_PREFIX_BYTES)
				val dims = JxlDecoder.decodeBounds(headSlice) ?: run {
					val rest = buffered.readBytes()
					if (rest.isEmpty()) null else JxlDecoder.decodeBounds(headSlice + rest)
				} ?: return null
				Size(dims[0], dims[1])
			}
			FORMAT_AVIF -> {
				val bytes = buffered.toByteBuffer()
				val info = Info()
				if (AvifDecoder.getInfo(bytes, bytes.remaining(), info) && info.width > 0 && info.height > 0) {
					Size(info.width, info.height)
				} else {
					null
				}
			}
			else -> {
				val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
				BitmapFactory.decodeStream(buffered, null, opts)?.recycle()
				if (opts.outWidth > 0 && opts.outHeight > 0) {
					Size(opts.outWidth, opts.outHeight)
				} else {
					null
				}
			}
		}
	}

	private fun sniffStream(stream: InputStream): Pair<BufferedInputStream, MimeType?> {
		val buffered = if (stream is BufferedInputStream) stream else BufferedInputStream(stream)
		buffered.mark(JXL_HEADER_SIZE.coerceAtLeast(64))
		val head = ByteArray(64)
		var total = 0
		while (total < head.size) {
			val n = buffered.read(head, total, head.size - total)
			if (n <= 0) break
			total += n
		}
		buffered.reset()
		val format = if (total > 0) sniffBundledFormat(head, total) else null
		return buffered to format
	}

	private fun readPrefix(stream: InputStream, maxBytes: Int): ByteArray {
		val buf = ByteArray(maxBytes)
		var total = 0
		while (total < maxBytes) {
			val n = stream.read(buf, total, maxBytes - total)
			if (n <= 0) break
			total += n
		}
		return if (total == maxBytes) buf else buf.copyOf(total)
	}

	/** Android has no JPEG XL decoder of its own, so these go through the bundled one. */
	@Blocking
	fun decodeJxl(bytes: ByteArray): Bitmap = checkBitmapNotNull(JxlDecoder.decode(bytes), FORMAT_JXL)

	@Blocking
	fun createRegionDecoder(inoutStream: InputStream): BitmapRegionDecoder? = try {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			BitmapRegionDecoder.newInstance(inoutStream)
		} else {
			@Suppress("DEPRECATION")
			BitmapRegionDecoder.newInstance(inoutStream, false)
		}
	} catch (e: IOException) {
		e.printStackTraceDebug()
		null
	}

	@Blocking
	fun probeMimeType(file: File): MimeType? {
		// Sniffed first: neither the file name (cached pages have none) nor the platform knows JPEG XL
		if (isJxl(file)) return JXL_MIME
		return MimeTypes.probeMimeType(file) ?: detectBitmapType(file)
	}

	private fun isJxl(file: File): Boolean = runCatching {
		file.inputStream().use { stream ->
			val head = ByteArray(JXL_HEADER_SIZE)
			isJxlHeader(head, stream.read(head))
		}
	}.getOrDefault(false)

	@Blocking
	private fun detectBitmapType(file: File): MimeType? = runCatchingCancellable {
		val options = BitmapFactory.Options().apply {
			inJustDecodeBounds = true
		}
		BitmapFactory.decodeFile(file.path, options)?.recycle()
		options.outMimeType?.toMimeTypeOrNull()
	}.getOrNull()

	private fun checkBitmapNotNull(bitmap: Bitmap?, format: String?): Bitmap =
		bitmap ?: throw ImageDecodeException(null, format)

	private fun decodeAvif(bytes: ByteBuffer): Bitmap {
		val info = Info()
		if (!AvifDecoder.getInfo(bytes, bytes.remaining(), info)) {
			throw ImageDecodeException(
				null,
				FORMAT_AVIF,
				"Requested to decode byte buffer which cannot be handled by AvifDecoder",
			)
		}
		val config = if (info.depth == 8 || info.alphaPresent) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
		val bitmap = createBitmap(info.width, info.height, config)
		if (!AvifDecoder.decode(bytes, bytes.remaining(), bitmap)) {
			bitmap.recycle()
			throw ImageDecodeException(null, FORMAT_AVIF)
		}
		return bitmap
	}

	@RequiresApi(Build.VERSION_CODES.P)
	private class DecoderConfigListener(
		private val isMutable: Boolean,
	) : ImageDecoder.OnHeaderDecodedListener {

		override fun onHeaderDecoded(
			decoder: ImageDecoder,
			info: ImageDecoder.ImageInfo,
			source: ImageDecoder.Source
		) {
			decoder.isMutableRequired = isMutable
		}
	}
}
