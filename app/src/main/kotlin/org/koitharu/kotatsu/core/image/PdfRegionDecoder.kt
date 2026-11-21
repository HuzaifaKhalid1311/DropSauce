package org.koitharu.kotatsu.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import org.koitharu.kotatsu.local.data.input.PdfPages
import java.io.File

/**
 * Draws the page viewer's tiles of a PDF page straight from the file, at [PdfPages.virtualSize].
 * Sharpness beyond that size comes from [org.koitharu.kotatsu.reader.ui.pager.PdfPageLayer].
 */
class PdfRegionDecoder : ImageRegionDecoder {

	@Volatile
	private var target: Pair<File, Int>? = null
	private var scale = 1f

	override fun init(context: Context, uri: Uri): Point {
		val (file, index) = PdfPages.parseUri(uri)
		val size = PdfPages.pageSize(file, index)
		scale = PdfPages.virtualScale(size)
		target = file to index
		return PdfPages.virtualSize(size)
	}

	override fun init(context: Context, source: ImageSource): Point =
		init(context, requireNotNull((source as? ImageSource.Uri)?.uri) { "Not a pdf page: $source" })

	override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
		val (file, index) = checkNotNull(target) { "PdfRegionDecoder has been recycled" }
		return PdfPages.renderRegion(
			file = file,
			index = index,
			scale = scale / sampleSize,
			left = sRect.left / sampleSize.toFloat(),
			top = sRect.top / sampleSize.toFloat(),
			width = sRect.width() / sampleSize,
			height = sRect.height() / sampleSize,
		)
	}

	override val isReady: Boolean
		get() = target != null

	override fun recycle() {
		target = null
	}

	companion object {

		/** The whole page, [sampleSize] times smaller than the size the reader shows it at. */
		fun decodeWhole(uri: Uri, sampleSize: Int): Bitmap {
			val (file, index) = PdfPages.parseUri(uri)
			val size = PdfPages.virtualSize(file, index)
			return PdfPages.render(file, index, size.x / sampleSize, size.y / sampleSize)
		}
	}
}
