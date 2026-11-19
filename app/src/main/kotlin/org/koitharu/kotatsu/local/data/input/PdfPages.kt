package org.koitharu.kotatsu.local.data.input

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Size
import org.jetbrains.annotations.Blocking
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * A local PDF is read in place: each page is drawn by the platform renderer only when it is shown,
 * so nothing is converted on import and no PDF library ships with the app.
 */
object PdfPages {

	/** Long side of a page drawn for the reader, the size imports used to convert to. */
	const val READER_PAGE_LONG_SIDE = 2560

	// pdfium is not safe to drive from several threads on older Android versions
	private val lock = Any()

	@Blocking
	fun count(file: File): Int = open(file) { it.pageCount }

	@Blocking
	fun pageSize(file: File, index: Int): Size = open(file) { renderer ->
		renderer.openPage(index).use { Size(it.width, it.height) }
	}

	/** Draws page [index] scaled to fit within [maxWidth]x[maxHeight], or to cover it when [fill]. */
	@Blocking
	fun render(file: File, index: Int, maxWidth: Int, maxHeight: Int, fill: Boolean = false): Bitmap = open(file) { renderer ->
		renderer.openPage(index).use { page ->
			val scaleX = maxWidth / page.width.coerceAtLeast(1).toFloat()
			val scaleY = maxHeight / page.height.coerceAtLeast(1).toFloat()
			val scale = if (fill) maxOf(scaleX, scaleY) else minOf(scaleX, scaleY)
			val bitmap = Bitmap.createBitmap(
				(page.width * scale).roundToInt().coerceAtLeast(1),
				(page.height * scale).roundToInt().coerceAtLeast(1),
				Bitmap.Config.ARGB_8888,
			)
			bitmap.eraseColor(Color.WHITE) // pages are transparent where nothing is printed
			page.render(bitmap, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
			bitmap
		}
	}

	/** The PDF file and the page index of a `file+pdf` uri. */
	fun parseUri(uri: Uri): Pair<File, Int> = File(requireNotNull(uri.path) { "Invalid pdf uri: $uri" }) to
		(uri.fragment?.toIntOrNull() ?: throw IOException("Invalid pdf page: $uri"))

	// shortcut: reopens the file for every page; keep one renderer open if big PDFs feel slow to page through
	private inline fun <T> open(file: File, block: (PdfRenderer) -> T): T = synchronized(lock) {
		PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use(block)
	}
}
