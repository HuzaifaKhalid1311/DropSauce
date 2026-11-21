package org.koitharu.kotatsu.reader.ui.pager

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.appcompat.R as appcompatR
import com.davemorrissey.labs.subscaleview.DefaultOnStateChangedListener
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.bookmarks.domain.PdfHighlight
import org.koitharu.kotatsu.bookmarks.domain.pdfHighlight
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.local.data.input.PdfPages
import org.koitharu.kotatsu.reader.ui.DictionarySheet
import org.koitharu.kotatsu.reader.ui.epub.EpubReaderFragment
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * What a PDF page gets on top of the page viewer [ssiv], following its zoom: the part on screen redrawn
 * sharp once zoomed past the viewer's own detail, links, text selection and highlights.
 * Links and selection need [PdfPages.isTextSupported]; sharp zoom and saved highlights work on any phone.
 */
class PdfPageLayer(
	private val ssiv: SubsamplingScaleImageView,
	private val scope: CoroutineScope,
	private val host: Host,
) : Drawable(), DefaultOnStateChangedListener {

	interface Host {
		/** Every PDF highlight of the open title. */
		val pdfHighlights: Flow<List<Bookmark>>
		fun onPdfSelectionChanged(layer: PdfPageLayer, isActive: Boolean)
		fun onPdfLink(chapterId: Long, file: File, link: PdfPages.Link)
		fun onPdfDictionary(word: String)
		fun onPdfAddHighlight(chapterId: Long, pageIndex: Int, highlight: PdfHighlight)
		fun onPdfRemoveHighlight(bookmarkId: Long)
	}

	private class Target(
		val file: File,
		val index: Int,
		val chapterId: Long,
		val pageIndex: Int,
		val scale: Float,
		val left: Int,
		val top: Int,
	)

	private val density = ssiv.resources.displayMetrics.density
	private val handleRadius = HANDLE_RADIUS_DP * density
	private val touchSlop = ViewConfiguration.get(ssiv.context).scaledTouchSlop
	private val highlightPaint = Paint().apply { color = EpubReaderFragment.DEFAULT_HIGHLIGHT_COLOR }
	private val patchPaint = Paint(Paint.FILTER_BITMAP_FLAG)
	private val selectionPaint = Paint().apply {
		color = ssiv.context.getThemeColor(appcompatR.attr.colorPrimary, 0.3f)
	}
	private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = ssiv.context.getThemeColor(appcompatR.attr.colorPrimary)
	}
	private val tmpRect = RectF()
	private val tmpArea = RectF()
	private val location = IntArray(2)

	private var target: Target? = null
	private var content: PdfPages.Content? = null
	private var allHighlights: List<Bookmark> = emptyList()
	private var highlights: List<Pair<Bookmark, PdfHighlight>> = emptyList()
	private var selection: PdfPages.Selection? = null
	private var bindJob: Job? = null
	private var selectJob: Job? = null
	private var pendingSelect: Pair<PdfPages.Boundary, PdfPages.Boundary>? = null
	private var actionMode: ActionMode? = null
	private var isRestartingActions = false

	// dragging a handle: the other end of the selection stays here
	private var dragAnchor: PdfPages.Boundary? = null
	private var longPressOrigin: PointF? = null

	// sharp zoom: the part of the page on screen, drawn at screen resolution
	private var patch: Bitmap? = null
	private val patchArea = RectF() // in page points
	private var patchScale = 0f // screen pixels per page point
	private var patchJob: Job? = null
	private val patchRunnable = Runnable(::renderPatch)
	private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
		checkPatch()
		true
	}

	init {
		// every frame, as the page can also move with its list (webtoon) without being redrawn itself
		ssiv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
			override fun onViewAttachedToWindow(v: View) = v.viewTreeObserver.addOnPreDrawListener(preDrawListener)
			override fun onViewDetachedFromWindow(v: View) = v.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
		})
		if (ssiv.isAttachedToWindow) ssiv.viewTreeObserver.addOnPreDrawListener(preDrawListener)
		ssiv.overlay.add(this)
		ssiv.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> setBounds(0, 0, v.width, v.height) }
		setBounds(0, 0, ssiv.width, ssiv.height)
		ssiv.onStateChangedListener = this
		ssiv.setTag(R.id.tag_pdf_layer, this)
	}

	/** Shows the marks of the PDF page at [uri], shown cropped to [region] (in the reader's page size). */
	fun bind(uri: Uri, region: Rect?, chapterId: Long, pageIndex: Int) {
		unbind()
		bindJob = scope.launch {
			val (file, index) = PdfPages.parseUri(uri)
			val (scale, pageContent) = withContext(Dispatchers.IO) {
				runCatching {
					PdfPages.virtualScale(PdfPages.pageSize(file, index)) to PdfPages.content(file, index)
				}.onFailure { it.printStackTraceDebug() }.getOrNull()
			} ?: return@launch
			target = Target(file, index, chapterId, pageIndex, scale, region?.left ?: 0, region?.top ?: 0)
			content = pageContent
			filterHighlights()
			invalidateSelf()
		}
	}

	fun unbind() {
		clearSelection()
		dropPatch()
		bindJob?.cancel()
		target = null
		content = null
		highlights = emptyList()
		invalidateSelf()
	}

	fun setHighlights(bookmarks: List<Bookmark>) {
		allHighlights = bookmarks
		filterHighlights()
		invalidateSelf()
		actionMode?.invalidate()
	}

	/** A link under the finger opens instead of the tap gesture. */
	fun onTap(rawX: Float, rawY: Float): Boolean {
		val t = target ?: return false
		val point = toPage(rawX, rawY) ?: return false
		val slop = slopInPage()
		val link = content?.links?.firstOrNull { link -> link.bounds.any { it.containsWithSlop(point, slop) } }
			?: return false
		host.onPdfLink(t.chapterId, t.file, link)
		return true
	}

	/** A long-press on text selects the word there; elsewhere it stays the reader's own gesture. */
	fun onLongPress(rawX: Float, rawY: Float): Boolean {
		val point = toPage(rawX, rawY) ?: return false
		val slop = slopInPage()
		if (content?.textBounds?.any { it.containsWithSlop(point, slop) } != true) return false
		val at = PdfPages.Boundary.at(point.x, point.y)
		longPressOrigin = PointF(rawX, rawY)
		dragAnchor = null
		select(at, at)
		return true
	}

	/** Which handle is under the finger: [HANDLE_START], [HANDLE_END] or [HANDLE_NONE]. */
	fun handleAt(rawX: Float, rawY: Float): Int {
		val sel = selection ?: return HANDLE_NONE
		val point = toViewPoint(rawX, rawY)
		val reach = handleRadius * 2f
		handleCenter(sel, isStart = true)?.let { if (hypot(it.x - point.x, it.y - point.y) <= reach) return HANDLE_START }
		handleCenter(sel, isStart = false)?.let { if (hypot(it.x - point.x, it.y - point.y) <= reach) return HANDLE_END }
		return HANDLE_NONE
	}

	/** The page is on screen under this point (screen coordinates, so split screen and webtoon zoom hold). */
	fun containsScreenPoint(rawX: Float, rawY: Float): Boolean {
		if (!ssiv.isShown) return false
		val point = toViewPoint(rawX, rawY)
		return point.x >= 0f && point.y >= 0f && point.x < ssiv.width && point.y < ssiv.height
	}

	/** Moves [handle] to the finger; right after a long-press the end handle follows once the finger moves. */
	fun dragHandle(handle: Int, rawX: Float, rawY: Float) {
		val sel = selection ?: return
		longPressOrigin?.let { origin ->
			if (hypot(origin.x - rawX, origin.y - rawY) < touchSlop) return
			longPressOrigin = null
		}
		val anchor = dragAnchor ?: anchorOf(sel, handle).also {
			dragAnchor = it
			hideActions()
		}
		val point = toPage(rawX, rawY) ?: return
		val finger = PdfPages.Boundary.at(point.x, point.y)
		if (handle == HANDLE_START) select(finger, anchor) else select(anchor, finger)
	}

	fun onDragEnd() {
		longPressOrigin = null
		dragAnchor = null
		if (selection != null && selectJob?.isActive != true) showActions()
	}

	fun clearSelection() {
		selectJob?.cancel()
		pendingSelect = null
		longPressOrigin = null
		dragAnchor = null
		if (selection == null) return
		selection = null
		actionMode?.finish()
		invalidateSelf()
		host.onPdfSelectionChanged(this, false)
	}

	private fun select(start: PdfPages.Boundary, stop: PdfPages.Boundary) {
		if (dragAnchor != null && selectJob?.isActive == true) {
			pendingSelect = start to stop // a drag: only the newest finger position waits its turn
			return
		}
		selectJob?.cancel()
		runSelect(start, stop)
	}

	private fun runSelect(start: PdfPages.Boundary, stop: PdfPages.Boundary) {
		val t = target ?: return
		selectJob = scope.launch {
			val result = withContext(Dispatchers.IO) {
				runCatching { PdfPages.select(t.file, t.index, start, stop) }
					.onFailure { it.printStackTraceDebug() }.getOrNull()
			}
			if (target === t && result != null) setSelection(result)
			val next = pendingSelect ?: return@launch
			pendingSelect = null
			runSelect(next.first, next.second)
		}
	}

	private fun selectAll() {
		val t = target ?: return
		selectJob?.cancel()
		selectJob = scope.launch {
			val result = withContext(Dispatchers.IO) { runCatching { PdfPages.selectAll(t.file, t.index) }.getOrNull() }
			if (target !== t || result == null) return@launch
			setSelection(result)
		}
	}

	private fun setSelection(value: PdfPages.Selection) {
		val wasActive = selection != null
		selection = value
		invalidateSelf()
		if (!wasActive) host.onPdfSelectionChanged(this, true)
		if (dragAnchor == null && longPressOrigin == null) showActions() else actionMode?.invalidate()
	}

	private fun showActions() {
		val mode = actionMode
		if (mode != null) {
			mode.invalidate()
			mode.invalidateContentRect()
		} else {
			actionMode = ssiv.startActionMode(actionCallback, ActionMode.TYPE_FLOATING)
		}
	}

	private fun hideActions() {
		isRestartingActions = true
		actionMode?.finish()
		isRestartingActions = false
	}

	private fun filterHighlights() {
		val t = target
		highlights = if (t == null) emptyList() else allHighlights.mapNotNull { bookmark ->
			if (bookmark.chapterId != t.chapterId || bookmark.page != t.pageIndex) return@mapNotNull null
			bookmark.pdfHighlight?.let { bookmark to it }
		}
	}

	private fun selectedHighlight(): Bookmark? {
		val sel = selection ?: return null
		return highlights.firstOrNull { (_, h) ->
			h.bounds.any { a -> sel.bounds.any { b -> RectF.intersects(a, b) } }
		}?.first
	}

	/** The end of [sel] that stays put while [handle] is dragged, just inside its first or last character. */
	private fun anchorOf(sel: PdfPages.Selection, handle: Int): PdfPages.Boundary {
		val rect = if (handle == HANDLE_START) sel.bounds.last() else sel.bounds.first()
		val x = if (handle == HANDLE_START) rect.right - 1f else rect.left + 1f
		return PdfPages.Boundary.at(x, rect.centerY())
	}

	override fun draw(canvas: Canvas) {
		if (target == null || !isMappable()) return
		patch?.let { bitmap ->
			if (toView(patchArea, tmpRect)) {
				patchPaint.colorFilter = ssiv.colorFilter // the reader's colour filter, like the page under it
				canvas.drawBitmap(bitmap, null, tmpRect, patchPaint)
			}
		}
		for ((_, highlight) in highlights) {
			for (rect in highlight.bounds) {
				if (toView(rect, tmpRect)) canvas.drawRect(tmpRect, highlightPaint)
			}
		}
		val sel = selection ?: return
		for (rect in sel.bounds) {
			if (toView(rect, tmpRect)) canvas.drawRect(tmpRect, selectionPaint)
		}
		handleCenter(sel, isStart = true)?.let { drawHandle(canvas, it, isStart = true) }
		handleCenter(sel, isStart = false)?.let { drawHandle(canvas, it, isStart = false) }
	}

	// the usual teardrop: a circle hanging below the text with its corner pointing at the selection's edge
	private fun drawHandle(canvas: Canvas, center: PointF, isStart: Boolean) {
		canvas.drawCircle(center.x, center.y, handleRadius, handlePaint)
		val left = if (isStart) center.x else center.x - handleRadius
		canvas.drawRect(left, center.y - handleRadius, left + handleRadius, center.y, handlePaint)
	}

	private fun handleCenter(sel: PdfPages.Selection, isStart: Boolean): PointF? {
		val rect = (if (isStart) sel.bounds.firstOrNull() else sel.bounds.lastOrNull()) ?: return null
		if (!toView(rect, tmpRect)) return null
		return if (isStart) {
			PointF(tmpRect.left - handleRadius, tmpRect.bottom + handleRadius)
		} else {
			PointF(tmpRect.right + handleRadius, tmpRect.bottom + handleRadius)
		}
	}

	// shortcut: rotated pages ("rotate wide pages") get no marks; map through the rotation if anyone asks
	private fun isMappable() = ssiv.isReady && ssiv.orientation == SubsamplingScaleImageView.ORIENTATION_0

	private fun toView(rect: RectF, out: RectF): Boolean {
		val t = target ?: return false
		val a = ssiv.sourceToViewCoord(rect.left * t.scale - t.left, rect.top * t.scale - t.top) ?: return false
		val b = ssiv.sourceToViewCoord(rect.right * t.scale - t.left, rect.bottom * t.scale - t.top) ?: return false
		out.set(a.x, a.y, b.x, b.y)
		out.sort()
		return true
	}

	private fun toPage(rawX: Float, rawY: Float): PointF? {
		val t = target ?: return null
		if (!isMappable()) return null
		val point = toViewPoint(rawX, rawY)
		val source = ssiv.viewToSourceCoord(point.x, point.y) ?: return null
		return PointF((source.x + t.left) / t.scale, (source.y + t.top) / t.scale)
	}

	/** A point on the screen in the page view's own coordinates. */
	private fun toViewPoint(rawX: Float, rawY: Float): PointF {
		ssiv.getLocationOnScreen(location)
		val scale = ancestorScale()
		return PointF((rawX - location[0]) / scale, (rawY - location[1]) / scale)
	}

	/** How much the views around the page are zoomed: webtoon zoom scales the whole list. */
	private fun ancestorScale(): Float {
		var scale = 1f
		var parent = ssiv.parent
		while (parent is View) {
			scale *= parent.scaleX
			parent = parent.parent
		}
		return scale
	}

	private fun checkPatch() {
		val t = target
		val zoom = ssiv.scale * ancestorScale()
		if (t == null || !isMappable() || zoom <= PATCH_MIN_ZOOM) {
			if (patch != null || patchJob != null) dropPatch()
			return
		}
		val scale = t.scale * zoom
		val isCurrent = patch != null && abs(scale - patchScale) <= scale * PATCH_SCALE_TOLERANCE &&
			visibleArea(t, tmpArea) && RectF(patchArea).apply { inset(-1f, -1f) }.contains(tmpArea)
		if (!isCurrent) {
			// once the page settles: drawing while it still moves would be thrown away
			ssiv.removeCallbacks(patchRunnable)
			ssiv.postDelayed(patchRunnable, PATCH_DELAY_MS)
		}
	}

	private fun renderPatch() {
		val t = target ?: return
		if (!isMappable()) return
		val scale = t.scale * ssiv.scale * ancestorScale()
		val area = RectF()
		if (!visibleArea(t, area)) return
		val width = (area.width() * scale).roundToInt()
		val height = (area.height() * scale).roundToInt()
		if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PATCH_PIXELS) return
		patchJob?.cancel()
		patchJob = scope.launch {
			val bitmap = withContext(Dispatchers.IO) {
				runCatching { PdfPages.renderRegion(t.file, t.index, scale, area.left * scale, area.top * scale, width, height) }
					.onFailure { it.printStackTraceDebug() }.getOrNull()
			} ?: return@launch
			if (target !== t) return@launch
			patch = bitmap
			patchArea.set(area)
			patchScale = scale
			invalidateSelf()
		}
	}

	// no recycle(): the last frame's drawing may still use the bitmap; the collector frees it
	private fun dropPatch() {
		ssiv.removeCallbacks(patchRunnable)
		patchJob?.cancel()
		patchJob = null
		if (patch != null) {
			patch = null
			invalidateSelf()
		}
	}

	/** The part of the shown page that is on screen, in page points. */
	private fun visibleArea(t: Target, out: RectF): Boolean {
		val visible = Rect()
		if (!ssiv.getGlobalVisibleRect(visible)) return false
		ssiv.getLocationInWindow(location)
		val scale = ancestorScale()
		val a = ssiv.viewToSourceCoord((visible.left - location[0]) / scale, (visible.top - location[1]) / scale) ?: return false
		val b = ssiv.viewToSourceCoord((visible.right - location[0]) / scale, (visible.bottom - location[1]) / scale)
			?: return false
		out.set(
			(maxOf(a.x, 0f) + t.left) / t.scale,
			(maxOf(a.y, 0f) + t.top) / t.scale,
			(minOf(b.x, ssiv.sWidth.toFloat()) + t.left) / t.scale,
			(minOf(b.y, ssiv.sHeight.toFloat()) + t.top) / t.scale,
		)
		return !out.isEmpty
	}

	/** A finger's worth of tolerance, in page points at the current zoom. */
	private fun slopInPage(): Float {
		val t = target ?: return 0f
		return TOUCH_SLOP_DP * density / (ssiv.scale * t.scale).coerceAtLeast(0.01f)
	}

	private fun RectF.containsWithSlop(point: PointF, slop: Float) =
		point.x >= left - slop && point.x <= right + slop && point.y >= top - slop && point.y <= bottom + slop

	override fun onScaleChanged(view: SubsamplingScaleImageView, newScale: Float, origin: Int) {
		actionMode?.invalidateContentRect()
	}

	override fun onCenterChanged(view: SubsamplingScaleImageView, newCenter: PointF, origin: Int) {
		actionMode?.invalidateContentRect()
	}

	override fun setAlpha(alpha: Int) = Unit

	override fun setColorFilter(colorFilter: ColorFilter?) = Unit

	@Deprecated("Deprecated in Java")
	override fun getOpacity() = PixelFormat.TRANSLUCENT

	private val actionCallback = object : ActionMode.Callback2() {

		override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
			menu.add(Menu.NONE, android.R.id.copy, 0, android.R.string.copy)
			menu.add(Menu.NONE, R.id.action_share, 1, R.string.share)
			menu.add(Menu.NONE, android.R.id.selectAll, 2, android.R.string.selectAll)
			menu.add(Menu.NONE, ACTION_DICTIONARY, 3, R.string.dictionary)
			menu.add(Menu.NONE, ACTION_HIGHLIGHT, 4, R.string.highlight)
			menu.add(Menu.NONE, ACTION_REMOVE_HIGHLIGHT, 5, R.string.remove_highlight_action)
			return true
		}

		override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
			val sel = selection
			val highlight = selectedHighlight()
			menu.findItem(ACTION_DICTIONARY)?.isVisible = sel != null && DictionarySheet.isWord(sel.text)
			menu.findItem(ACTION_HIGHLIGHT)?.isVisible = sel != null && highlight == null
			menu.findItem(ACTION_REMOVE_HIGHLIGHT)?.isVisible = highlight != null
			return true
		}

		override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
			val sel = selection ?: return false
			val t = target ?: return false
			val context = ssiv.context
			when (item.itemId) {
				android.R.id.copy -> {
					context.copyToClipboard(context.getString(R.string.app_name), sel.text)
					Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
				}

				R.id.action_share -> context.startActivity(
					Intent.createChooser(
						Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, sel.text),
						null,
					),
				)

				android.R.id.selectAll -> {
					selectAll()
					return true // keeps the selection and its menu
				}

				ACTION_DICTIONARY -> host.onPdfDictionary(sel.text)
				ACTION_HIGHLIGHT -> host.onPdfAddHighlight(
					t.chapterId,
					t.pageIndex,
					PdfHighlight(sel.bounds, sel.text),
				)

				ACTION_REMOVE_HIGHLIGHT -> selectedHighlight()?.let { host.onPdfRemoveHighlight(it.pageId) }
				else -> return false
			}
			clearSelection()
			return true
		}

		override fun onDestroyActionMode(mode: ActionMode) {
			actionMode = null
			// dismissed by the system (back, another app): the selection goes with it
			if (!isRestartingActions) clearSelection()
		}

		override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
			val bounds = RectF()
			selection?.bounds?.forEach { rect ->
				if (toView(rect, tmpRect)) bounds.union(tmpRect)
			}
			bounds.roundOut(outRect)
		}
	}

	companion object {

		const val HANDLE_NONE = 0
		const val HANDLE_START = 1
		const val HANDLE_END = 2

		private const val HANDLE_RADIUS_DP = 11f
		private const val TOUCH_SLOP_DP = 8f
		// zoomed this much past the viewer's own detail, the part on screen gets redrawn
		private const val PATCH_MIN_ZOOM = 1.1f
		private const val PATCH_SCALE_TOLERANCE = 0.02f
		private const val PATCH_DELAY_MS = 120L
		private const val MAX_PATCH_PIXELS = 16_000_000L
		private const val ACTION_DICTIONARY = 0x456001
		private const val ACTION_HIGHLIGHT = 0x456002
		private const val ACTION_REMOVE_HIGHLIGHT = 0x456003
	}
}
