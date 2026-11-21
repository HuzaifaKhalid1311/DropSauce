package org.koitharu.kotatsu.local.data.input

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Point
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.content.PdfPageGotoLinkContent
import android.graphics.pdf.content.PdfPageLinkContent
import android.graphics.pdf.content.PdfPageTextContent
import android.graphics.pdf.models.selection.PageSelection
import android.graphics.pdf.models.selection.SelectionBoundary
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import android.util.Size
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresExtension
import androidx.core.net.toUri
import org.jetbrains.annotations.Blocking
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * A local PDF is read in place: each page is drawn by the platform renderer only when it is shown,
 * so nothing is converted on import and no PDF library ships with the app.
 */
object PdfPages {

	/** Long side of a page drawn as a picture, for saving and sharing it. */
	const val IMAGE_LONG_SIDE = 2560

	// The page viewer sees a page at about the size imports used to convert to, which keeps its whole-page
	// layer cheap; zoomed in past that, PdfPageLayer redraws the part on screen at full sharpness.
	private const val VIRTUAL_LONG_SIDE = IMAGE_LONG_SIDE
	private const val VIRTUAL_SHORT_SIDE = 1280
	private const val VIRTUAL_MAX_SIDE = 6144

	/** Links and text selection need Android 15, or Android 12-14 with the PDF update from Google Play. */
	val isTextSupported: Boolean by lazy {
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM ||
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13
	}

	// pdfium is not safe to drive from several threads; everything touching a renderer runs under this lock
	private val lock = Any()
	private var renderer: OpenDocument<PdfRenderer, PdfRenderer.Page>? = null
	private var textRenderer: OpenDocument<*, *>? = null

	@Blocking
	fun count(file: File): Int = synchronized(lock) { document(file).renderer.pageCount }

	@Blocking
	fun pageSize(file: File, index: Int): Size = synchronized(lock) {
		document(file).page(index).let { Size(it.width, it.height) }
	}

	/** The size the reader shows page [index] at, see [VIRTUAL_LONG_SIDE]. */
	@Blocking
	fun virtualSize(file: File, index: Int): Point = virtualSize(pageSize(file, index))

	fun virtualSize(pageSize: Size): Point {
		val scale = virtualScale(pageSize)
		return Point((pageSize.width * scale).roundToInt().coerceAtLeast(1), (pageSize.height * scale).roundToInt().coerceAtLeast(1))
	}

	/** Pixels of [virtualSize] per page point. */
	fun virtualScale(pageSize: Size): Float {
		val long = maxOf(pageSize.width, pageSize.height).coerceAtLeast(1)
		val short = minOf(pageSize.width, pageSize.height).coerceAtLeast(1)
		// long strips get a readable width, but not an unbounded height
		return minOf(
			maxOf(VIRTUAL_LONG_SIDE / long.toFloat(), VIRTUAL_SHORT_SIDE / short.toFloat()),
			VIRTUAL_MAX_SIDE / long.toFloat(),
		)
	}

	/** Draws page [index] scaled to fit within [maxWidth]x[maxHeight], or to cover it when [fill]. */
	@Blocking
	fun render(file: File, index: Int, maxWidth: Int, maxHeight: Int, fill: Boolean = false): Bitmap = synchronized(lock) {
		val page = document(file).page(index)
		val scaleX = maxWidth / page.width.coerceAtLeast(1).toFloat()
		val scaleY = maxHeight / page.height.coerceAtLeast(1).toFloat()
		val scale = if (fill) maxOf(scaleX, scaleY) else minOf(scaleX, scaleY)
		draw(page, (page.width * scale).roundToInt(), (page.height * scale).roundToInt(), scale, 0f, 0f)
	}

	/** Draws a [width]x[height] piece of page [index] at [scale], starting at [left],[top] of the scaled page. */
	@Blocking
	fun renderRegion(file: File, index: Int, scale: Float, left: Float, top: Float, width: Int, height: Int): Bitmap =
		synchronized(lock) { draw(document(file).page(index), width, height, scale, left, top) }

	private fun draw(page: PdfRenderer.Page, width: Int, height: Int, scale: Float, left: Float, top: Float): Bitmap {
		val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
		bitmap.eraseColor(Color.WHITE) // pages are transparent where nothing is printed
		val matrix = Matrix().apply {
			setScale(scale, scale)
			postTranslate(-left, -top)
		}
		page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
		return bitmap
	}

	/** Links and where the text is on page [index], in page points; null when the phone can't read them. */
	@Blocking
	@SuppressLint("NewApi") // the platform text classes are only touched inside withTextPage
	fun content(file: File, index: Int): Content? = withTextPage(file, index) { page ->
		val links = page.links().map { link ->
			Link(link.bounds, link.uri, NO_PAGE)
		} + page.gotoLinks().map { link ->
			Link(link.bounds, null, link.destination.pageNumber)
		}
		// the page's text comes without positions; selecting all of it gives them
		Content(links, page.selectAll()?.selectedTextContents?.flatMap { it.bounds }.orEmpty())
	}

	/** The text from [start] to [stop]; both at the same point picks the word there. */
	@Blocking
	@SuppressLint("NewApi") // the platform text classes are only touched inside withTextPage
	fun select(file: File, index: Int, start: Boundary, stop: Boundary): Selection? =
		withTextPage(file, index) { page ->
			val selection = page.select(start.toPlatform(), stop.toPlatform()) ?: return@withTextPage null
			val contents = selection.selectedTextContents
			selection.toSelection()
		}

	/** Everything on page [index]. */
	@Blocking
	@SuppressLint("NewApi") // the platform text classes are only touched inside withTextPage
	fun selectAll(file: File, index: Int): Selection? = withTextPage(file, index) { it.selectAll()?.toSelection() }

	@SuppressLint("NewApi")
	private fun PageSelection.toSelection(): Selection? {
		val text = selectedTextContents.joinToString("") { it.text }.trim()
		val bounds = selectedTextContents.flatMap { it.bounds }
		return if (text.isEmpty() || bounds.isEmpty()) null else Selection(text, bounds)
	}

	@SuppressLint("NewApi")
	private fun TextPage.selectAll(): PageSelection? {
		val length = text().sumOf { it.text.length }
		return if (length == 0) null else select(Boundary.index(0).toPlatform(), Boundary.index(length).toPlatform())
	}

	/**
	 * The chapters a PDF's own contents split it into, as titles and page ranges; empty when it has none
	 * to split by. Pages before the first entry (cover, credits) open the first chapter.
	 */
	@Blocking
	fun chapters(file: File): List<Pair<String, IntRange>> {
		val count = count(file)
		val starts = PdfOutline.read(file)
			.filter { it.pageIndex in 0 until count }
			.sortedBy { it.pageIndex }
			.distinctBy { it.pageIndex }
		if (starts.size < 2) return emptyList()
		return starts.mapIndexed { i, entry ->
			val start = if (i == 0) 0 else entry.pageIndex
			entry.title to (start until (starts.getOrNull(i + 1)?.pageIndex ?: count))
		}
	}

	/** The url of a chapter holding [pages] of [file]: the file's own url with the range after `#pages=`. */
	fun chapterUrl(file: File, pages: IntRange): String = "${file.toUri()}#$PAGES_PREFIX${pages.first}-${pages.last + 1}"

	/** The pages of a chapter made by [chapterUrl], null for any other url. */
	fun chapterPages(url: String): IntRange? {
		val range = url.substringAfter('#', "").takeIf { it.startsWith(PAGES_PREFIX) }?.removePrefix(PAGES_PREFIX)
			?: return null
		val start = range.substringBefore('-').toIntOrNull() ?: return null
		val end = range.substringAfter('-').toIntOrNull() ?: return null
		return start until end
	}

	private const val PAGES_PREFIX = "pages="

	/** Lets go of the open file, so a deleted PDF frees its space right away. */
	fun release() = synchronized(lock) {
		renderer?.close()
		renderer = null
		textRenderer?.close()
		textRenderer = null
	}

	/** The PDF file and the page index of a `file+pdf` uri. */
	fun parseUri(uri: Uri): Pair<File, Int> = File(requireNotNull(uri.path) { "Invalid pdf uri: $uri" }) to
		(uri.fragment?.toIntOrNull() ?: throw IOException("Invalid pdf page: $uri"))

	class Content(val links: List<Link>, val textBounds: List<RectF>)

	/** A web link when [uri] is set, otherwise a jump to [pageIndex] of the same file. */
	class Link(val bounds: List<RectF>, val uri: Uri?, val pageIndex: Int)

	class Selection(val text: String, val bounds: List<RectF>)

	/** One end of a selection: a text index, or a point on the page (in page points). */
	class Boundary private constructor(private val index: Int, private val x: Int, private val y: Int) {

		@SuppressLint("NewApi") // only called inside withTextPage, which checks isTextSupported
		internal fun toPlatform() = if (index >= 0) SelectionBoundary(index) else SelectionBoundary(Point(x, y))

		companion object {
			fun index(index: Int) = Boundary(index.coerceAtLeast(0), 0, 0)
			fun at(x: Float, y: Float) = Boundary(-1, x.roundToInt(), y.roundToInt())
		}
	}

	const val NO_PAGE = -1

	private fun document(file: File): OpenDocument<PdfRenderer, PdfRenderer.Page> {
		renderer?.takeIf { it.isOf(file) }?.let { return it }
		renderer?.close()
		val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
		return OpenDocument(file, PdfRenderer(fd), PdfRenderer::openPage).also { renderer = it }
	}

	@SuppressLint("NewApi") // isTextSupported checks the version
	private fun <T> withTextPage(file: File, index: Int, block: (TextPage) -> T): T? {
		if (!isTextSupported) return null
		return synchronized(lock) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
				block(PageV(document(file).page(index)))
			} else {
				block(PagePreV(textDocument(file).page(index)))
			}
		}
	}

	@RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
	private fun textDocument(file: File): OpenDocument<PdfRendererPreV, PdfRendererPreV.Page> {
		@Suppress("UNCHECKED_CAST")
		(textRenderer as? OpenDocument<PdfRendererPreV, PdfRendererPreV.Page>)?.takeIf { it.isOf(file) }?.let { return it }
		textRenderer?.close()
		val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
		return OpenDocument(file, PdfRendererPreV(fd), PdfRendererPreV::openPage).also { textRenderer = it }
	}

	/** One renderer kept open with its last page, as only one page may be open at a time. */
	private class OpenDocument<R : AutoCloseable, P : AutoCloseable>(
		private val file: File,
		val renderer: R,
		private val openPage: R.(Int) -> P,
	) : AutoCloseable {

		private val stamp = file.lastModified() to file.length()
		private var pageIndex = -1
		private var page: P? = null

		fun isOf(file: File) = file == this.file && stamp == (file.lastModified() to file.length())

		fun page(index: Int): P {
			page?.takeIf { pageIndex == index }?.let { return it }
			page?.close()
			page = null
			return renderer.openPage(index).also {
				page = it
				pageIndex = index
			}
		}

		override fun close() {
			page?.close()
			page = null
			renderer.close()
		}
	}

	/** The text calls both renderers have, under different classes. */
	private interface TextPage {
		fun links(): List<PdfPageLinkContent>
		fun gotoLinks(): List<PdfPageGotoLinkContent>
		fun text(): List<PdfPageTextContent>
		fun select(start: SelectionBoundary, stop: SelectionBoundary): PageSelection?
	}

	@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
	private class PageV(private val page: PdfRenderer.Page) : TextPage {
		override fun links(): List<PdfPageLinkContent> = page.linkContents
		override fun gotoLinks(): List<PdfPageGotoLinkContent> = page.gotoLinks
		override fun text(): List<PdfPageTextContent> = page.textContents
		override fun select(start: SelectionBoundary, stop: SelectionBoundary) = page.selectContent(start, stop)
	}

	@RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
	private class PagePreV(private val page: PdfRendererPreV.Page) : TextPage {
		override fun links(): List<PdfPageLinkContent> = page.linkContents
		override fun gotoLinks(): List<PdfPageGotoLinkContent> = page.gotoLinks
		override fun text(): List<PdfPageTextContent> = page.textContents
		override fun select(start: SelectionBoundary, stop: SelectionBoundary) = page.selectContent(start, stop)
	}
}
