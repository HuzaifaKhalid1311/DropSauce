package org.koitharu.kotatsu.reader.ui.epub

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.Layout
import android.text.NoCopySpan
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannedString
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.LineBackgroundSpan
import android.text.style.LineHeightSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UpdateAppearance
import android.util.TypedValue
import android.view.ActionMode
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil3.ImageLoader
import coil3.request.ImageRequest
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import org.koitharu.kotatsu.bookmarks.domain.epubHighlight
import org.koitharu.kotatsu.bookmarks.domain.epubHighlightUrl
import eu.kanade.tachiyomi.source.online.HttpSource
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.lnreader.model.absoluteUrl
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.util.ext.getDrawableOrThrow
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.isNightMode
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.FragmentReaderEpubBinding
import org.koitharu.kotatsu.databinding.SheetEpubDictionaryBinding
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.pager.BaseReaderAdapter
import org.koitharu.kotatsu.reader.ui.pager.BaseReaderFragment
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import java.io.Closeable
import java.io.File
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile
import javax.inject.Inject
import kotlin.math.ceil
import com.google.android.material.R as materialR

/**
 * Native EPUB flow. Chapters are virtualized by RecyclerView in vertical mode and converted to a
 * flat, chapter-spanning ViewPager2 page list in paged mode. Both modes navigate with the same
 * chapter/character locator, so changing modes never reloads a chapter or loses the position.
 */
@AndroidEntryPoint
class EpubReaderFragment : BaseReaderFragment<FragmentReaderEpubBinding>() {

	@Inject
	lateinit var settings: AppSettings
	@Inject
	lateinit var bookmarksRepository: BookmarksRepository
	@Inject
	@BaseHttpClient
	lateinit var httpClient: OkHttpClient
	@Inject
	lateinit var imageLoader: ImageLoader

	@Inject
	lateinit var mangaRepositoryFactory: MangaRepository.Factory

	private var chapters: List<NativeChapter> = emptyList()
	private val chapterDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val chapterDividerDecoration = object : RecyclerView.ItemDecoration() {
		override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
			chapterDividerPaint.strokeWidth = resources.displayMetrics.density
			for (index in 0 until parent.childCount) {
				val child = parent.getChildAt(index)
				if (parent.getChildAdapterPosition(child) !in 0 until chapters.lastIndex) continue
				val y = child.bottom + child.translationY
				canvas.drawLine(0f, y, parent.width.toFloat(), y, chapterDividerPaint)
			}
		}
	}
	private var chapterContent: ChapterContent? = null
	private val loadingChapters = HashSet<Int>()
	private var verticalView: RecyclerView? = null
	private var pagerView: ViewPager2? = null
	private var pages: List<NativePage> = emptyList()
	private var paginationKey: String? = null
	private var pageRange: IntRange? = null
	private var extendingPages = false
	private var lastLocator = Locator(0, 0)
	private var loading = false
	private var restoring = false
	private var progressScheduled = false
	private var renderGeneration = 0
	private var reflowLocator: Locator? = null
	private var colorAnimator: ValueAnimator? = null
	private var scrollTopClipPx = 0
	private var pagedTopBarClearancePx = 0
	private var highlights: List<Bookmark> = emptyList()
	private var highlightsJob: Job? = null
	private var highlightMangaId = 0L
	private var cachedCustomTypeface: Typeface? = null
	private var cachedCustomTypefaceStamp = Long.MIN_VALUE

	private val rebuildRunnable = Runnable {
		val locator = reflowLocator
		reflowLocator = null
		if (chapters.isNotEmpty()) refreshReader(locator ?: currentLocator())
	}

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?) =
		FragmentReaderEpubBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentReaderEpubBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.root.post(::updateTopClearances)
		settings.observeAsFlow(AppSettings.KEY_EPUB_THEME) { epubTheme }
			.observe(viewLifecycleOwner) {
				animateColors()
				refreshHighlightColors()
			}
		settings.observeAsFlow(AppSettings.KEY_EPUB_CUSTOM_BACKGROUND_COLOR) { epubCustomBackgroundColor }
			.observe(viewLifecycleOwner) { if (settings.epubTheme == EPUB_THEME_CUSTOM) animateColors() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_CUSTOM_TEXT_COLOR) { epubCustomTextColor }
			.observe(viewLifecycleOwner) { if (settings.epubTheme == EPUB_THEME_CUSTOM) animateColors() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_CUSTOM_HIGHLIGHT_COLOR) { epubCustomHighlightColor }
			.observe(viewLifecycleOwner) { if (settings.epubTheme == EPUB_THEME_CUSTOM) refreshHighlightColors() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_FONT_SIZE) { epubFontSize }
			.observe(viewLifecycleOwner) { scheduleReflow() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_FONT_FAMILY) { epubFontFamily }
			.observe(viewLifecycleOwner) { scheduleReflow() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_CUSTOM_FONT_REVISION) { epubCustomFontRevision }
			.observe(viewLifecycleOwner) {
				cachedCustomTypeface = null
				cachedCustomTypefaceStamp = Long.MIN_VALUE
				if (settings.epubFontFamily == EPUB_FONT_CUSTOM) scheduleReflow()
			}
		settings.observeAsFlow(AppSettings.KEY_EPUB_LINE_HEIGHT) { epubLineHeight }
			.observe(viewLifecycleOwner) { scheduleReflow() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_PARAGRAPH_SPACING) { epubParagraphSpacing }
			.observe(viewLifecycleOwner) { scheduleReflow() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_HORIZONTAL_PADDING) { epubHorizontalPadding }
			.observe(viewLifecycleOwner) { scheduleReflow() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_VERTICAL_PADDING) { epubVerticalPadding }
			.observe(viewLifecycleOwner) { scheduleReflow() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_TEXT_ALIGN) { epubTextAlign }
			.observe(viewLifecycleOwner) { refreshAlignment() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_PUBLISHER_STYLE) { isEpubPublisherStyleEnabled }
			.observe(viewLifecycleOwner) { scheduleReflow() }
		settings.observeAsFlow(AppSettings.KEY_EPUB_READING_MODE) { epubReadingMode }
			.observe(viewLifecycleOwner) {
				binding.root.requestApplyInsets()
				switchReadingMode()
			}
	}

	override fun onDestroyView() {
		viewBinding?.root?.removeCallbacks(rebuildRunnable)
		highlightsJob?.cancel()
		highlightsJob = null
		highlights = emptyList()
		highlightMangaId = 0L
		colorAnimator?.cancel()
		colorAnimator = null
		cachedCustomTypeface = null
		cachedCustomTypefaceStamp = Long.MIN_VALUE
		verticalView = null
		pagerView = null
		pages = emptyList()
		paginationKey = null
		pageRange = null
		reflowLocator = null
		loadingChapters.clear()
		runCatching { chapterContent?.close() }
		chapterContent = null
		super.onDestroyView()
	}

	override fun onCreateAdapter(): BaseReaderAdapter<*>? = null

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val density = resources.displayMetrics.density
		val scrollClearance = activity?.findViewById<View>(R.id.infoBar)?.height
			?.takeIf { it > 0 }
			?: (SCROLL_INFO_BAR_HEIGHT_DP * density).toInt()
		updateScrollTopClearance(scrollClearance)
		v.updatePadding(0, 0, 0, 0)
		v.post(::updateTopClearances)
		return insets
	}

	private fun updateTopClearances() {
		val density = resources.displayMetrics.density
		activity?.findViewById<View>(R.id.infoBar)?.height?.takeIf { it > 0 }?.let { height ->
			updateScrollTopClearance(height)
		}
		val topBarBottom = activity?.findViewById<View>(R.id.appbar_top)?.bottom ?: 0
		val clearance = topBarBottom.takeIf { it > 0 }
			?: (PAGED_TOP_BAR_FALLBACK_DP * density).toInt()
		if (clearance != pagedTopBarClearancePx) {
			pagedTopBarClearancePx = clearance
			if (isPagedMode && pagerView != null) scheduleReflow()
		}
	}

	private fun updateScrollTopClearance(height: Int) {
		if (height == scrollTopClipPx) return
		val recycler = verticalView
		val manager = recycler?.layoutManager as? LinearLayoutManager
		val first = manager?.findFirstVisibleItemPosition()?.takeIf { it >= 0 }
		val childTop = first?.let(manager::findViewByPosition)?.top
		scrollTopClipPx = height
		recycler?.setPadding(0, height, 0, 0)
		if (first != null && childTop != null) {
			manager.scrollToPositionWithOffset(first, childTop - height)
		}
	}

	override suspend fun onPagesChanged(pages: List<ReaderPage>, pendingState: ReaderState?) {
		val manga = viewModel.getMangaOrNull() ?: return
		val state = pendingState ?: viewModel.getCurrentState() ?: return
		observeHighlights(manga)
		val mangaChapters = manga.chapters.orEmpty()
		if (mangaChapters.isEmpty()) return
		// A local book opens straight from disk, but a novel chapter is a network fetch — the indicator
		// covers the gap. `finally` also clears it on the early returns below.
		setChapterLoading(true)
		try {
			if (chapters.isEmpty() && !loading) {
				loading = true
				try {
					val prepared = withContext(Dispatchers.IO) { prepareBook(manga, mangaChapters) }
					chapters = prepared.chapters
					chapterContent = prepared.content
				} finally {
					loading = false
				}
			}
			if (chapters.isEmpty()) return
			val chapter = chapters.indexOfFirst { it.id == state.chapterId }
				.takeIf { it >= 0 } ?: return
			withContext(Dispatchers.IO) { ensureChaptersLoaded(chapter.preloadRange()) }
			val offset = ReaderState.decodeEpubOffset(state.scroll)
				?: (chapters[chapter].text.length.toLong() * state.scroll.coerceIn(0, 1000) / 1000).toInt()
			renderMode(Locator(chapter, offset), state.page.takeIf { isPagedMode })
		} finally {
			setChapterLoading(false)
		}
	}

	private fun setChapterLoading(value: Boolean) {
		viewBinding?.loadingIndicator?.isVisible = value
	}

	private fun observeHighlights(manga: Manga) {
		if (highlightMangaId == manga.id) return
		highlightMangaId = manga.id
		highlightsJob?.cancel()
		highlightsJob = viewLifecycleOwner.lifecycleScope.launch {
			bookmarksRepository.observeBookmarks(manga).collect { bookmarks ->
				highlights = bookmarks.filter { it.epubHighlight != null }
				verticalView?.adapter?.notifyDataSetChanged()
				pagerView?.adapter?.notifyDataSetChanged()
			}
		}
	}

	private fun prepareBook(manga: Manga, source: List<MangaChapter>): PreparedBook {
		val items = source.map { chapter ->
			NativeChapter(
				id = chapter.id,
				title = chapter.title.orEmpty(),
				url = chapter.url,
			)
		}
		// A novel source has no archive on disk: its chapters are fetched from the source itself.
		if (manga.source.isNovelSource) {
			return PreparedBook(items, RemoteContentSource(mangaRepositoryFactory.create(manga.source), source))
		}
		val archives = HashMap<File, ZipFile>()
		try {
			items.map { File(it.url.toUri().schemeSpecificPart) }
				.distinct()
				.forEach { archives[it] = ZipFile(it) }
		} catch (error: Throwable) {
			archives.values.forEach(ZipFile::close)
			throw error
		}
		return PreparedBook(items, ZipContentSource(archives))
	}

	private fun ensureChaptersLoaded(range: IntRange) {
		range.forEach(::ensureChapterLoaded)
	}

	private fun ensureChapterLoaded(index: Int) {
		val chapter = chapters.getOrNull(index) ?: return
		if (chapter.content != null) return
		synchronized(chapter) {
			if (chapter.content != null) return
			// Leave content null on failure so the next bind retries. Caching a blank chapter would
			// permanently blank it for remote sources, where one flaky request is expected.
			val raw = runCatching { chapterContent?.loadHtml(chapter.url) }.getOrNull() ?: return
			chapter.content = parseChapter(chapter, raw)
		}
	}

	private fun parseChapter(chapter: NativeChapter, raw: String): Spanned {
		val document = Jsoup.parse(raw)
		document.select("script,style,noscript").remove()
		document.select("svg").forEach { svg ->
			val image = svg.selectFirst("image") ?: return@forEach
			val source = image.attr("href").ifBlank { image.attr("xlink:href") }
			if (source.isNotBlank()) svg.replaceWith(image.clone().tagName("img").attr("src", source))
		}
		val parsed = SpannableString(
			HtmlCompat.fromHtml(
				document.body().html(),
				HtmlCompat.FROM_HTML_MODE_LEGACY,
				Html.ImageGetter { source -> loadEpubImage(chapter, source) },
				null,
			).trimmed(),
		)
		return SpannedString(parsed).takeIf { it.isNotEmpty() } ?: EMPTY_CHAPTER_TEXT
	}

	private fun loadEpubImage(chapter: NativeChapter, source: String): Drawable? = runCatching {
		// ByteArray for an embedded epub resource, absolute url for a remote one - coil takes either.
		val data = chapterContent?.imageData(chapter.url, source) ?: return null
		val drawable = (data as? ByteArray)
			?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
			?.let { BitmapDrawable(resources, it) }
			?: runBlocking {
				// Tag the request with the source so it goes out with that source's headers/client:
				// illustrations on Referer-checking hosts 403 on a bare request.
				imageLoader.execute(
					ImageRequest.Builder(requireContext())
						.data(data)
						.mangaSourceExtra(viewModel.getMangaOrNull()?.source)
						.build(),
				).getDrawableOrThrow()
			}
		val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: return null
		val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: return null
		val metrics = resources.displayMetrics
		val maxWidth = (metrics.widthPixels - 2 * effectiveHorizontalPadding * metrics.density).toInt().coerceAtLeast(1)
		val maxHeight = (metrics.heightPixels * MAX_IMAGE_HEIGHT_FRACTION).toInt().coerceAtLeast(1)
		val scale = minOf(1f, maxWidth / width.toFloat(), maxHeight / height.toFloat())
		drawable.setBounds(0, 0, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
		drawable
	}.getOrNull()

	private fun Spanned.trimmed(): CharSequence {
		var start = 0
		var end = length
		while (start < end && this[start].isWhitespace()) start++
		while (end > start && this[end - 1].isWhitespace()) end--
		return subSequence(start, end)
	}

	private fun Int.preloadRange(): IntRange =
		(this - PRELOAD_RADIUS).coerceAtLeast(0)..(this + PRELOAD_RADIUS).coerceAtMost(chapters.lastIndex)

	private fun preloadAround(center: Int) {
		center.preloadRange().forEach { index ->
			if (chapters[index].content != null || !loadingChapters.add(index)) return@forEach
			viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
				try {
					ensureChapterLoaded(index)
				} finally {
					withContext(Dispatchers.Main) {
						loadingChapters.remove(index)
						verticalView?.adapter?.notifyItemChanged(index)
					}
				}
			}
		}
	}

	private fun scheduleReflow() {
		// settings observers fire once on subscribe, before the book is loaded; a reflow queued
		// then would later render from an empty view and jump to the first page
		if (chapters.isEmpty()) return
		val root = viewBinding?.root ?: return
		if (reflowLocator == null) reflowLocator = currentLocator()
		root.removeCallbacks(rebuildRunnable)
		refreshVisibleStyles()
		root.postDelayed(rebuildRunnable, REPAGINATE_DELAY_MS)
	}

	private fun refreshVisibleStyles() {
		viewBinding?.readerContainer?.setBackgroundColor(backgroundColor)
		verticalView?.apply {
			setBackgroundColor(backgroundColor)
			adapter?.notifyDataSetChanged()
		}
		pagerView?.adapter?.notifyDataSetChanged()
	}

	private fun animateColors() {
		val container = viewBinding?.readerContainer ?: return
		val targetBackground = backgroundColor
		val targetForeground = foregroundColor
		val startBackground = (container.background as? ColorDrawable)?.color ?: targetBackground
		val startForeground = firstVisibleTextView()?.currentTextColor ?: targetForeground
		colorAnimator?.cancel()
		colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
			duration = COLOR_ANIMATION_MS
			addUpdateListener { animator ->
				val fraction = animator.animatedFraction
				applyVisibleColors(
					ColorUtils.blendARGB(startBackground, targetBackground, fraction),
					ColorUtils.blendARGB(startForeground, targetForeground, fraction),
				)
			}
			start()
		}
	}

	private fun applyVisibleColors(background: Int, foreground: Int) {
		chapterDividerPaint.color = foreground
		viewBinding?.root?.setBackgroundColor(background)
		viewBinding?.readerContainer?.setBackgroundColor(background)
		fun recolor(recycler: RecyclerView?) {
			recycler?.setBackgroundColor(background)
			if (recycler != null) for (index in 0 until recycler.childCount) {
				(recycler.getChildAt(index) as? TextView)?.apply {
					setBackgroundColor(background)
					setTextColor(foreground)
				}
			}
		}
		recolor(verticalView)
		verticalView?.invalidateItemDecorations()
		recolor(pagerView?.getChildAt(0) as? RecyclerView)
	}

	private fun firstVisibleTextView(): TextView? =
		(verticalView?.getChildAt(0) as? TextView)
			?: ((pagerView?.getChildAt(0) as? RecyclerView)?.getChildAt(0) as? TextView)

	private fun refreshHighlightColors() {
		fun recolor(recycler: RecyclerView?) {
			if (recycler != null) for (index in 0 until recycler.childCount) {
				val textView = recycler.getChildAt(index) as? TextView ?: continue
				val text = textView.text as? Spanned ?: continue
				text.getSpans(0, text.length, HighlightColorSpan::class.java).forEach { it.color = highlightColor }
				textView.invalidate()
			}
		}
		recolor(verticalView)
		recolor(pagerView?.getChildAt(0) as? RecyclerView)
	}

	private fun refreshAlignment() {
		fun update(recycler: RecyclerView?) {
			if (recycler != null) for (index in 0 until recycler.childCount) {
				(recycler.getChildAt(index) as? TextView)?.let(::applyTextAlignment)
			}
		}
		update(verticalView)
		update(pagerView?.getChildAt(0) as? RecyclerView)
	}

	private fun switchReadingMode() {
		if (chapters.isEmpty()) return
		val locator = currentLocator()
		viewBinding?.root?.removeCallbacks(rebuildRunnable)
		reflowLocator = null
		paginationKey = null
		refreshReader(locator)
	}

	private fun refreshReader(locator: Locator) {
		if (isPagedMode) {
			paginationKey = null
			renderPaged(viewBinding?.readerContainer ?: return, locator)
		} else if (verticalView != null) {
			verticalView?.setBackgroundColor(backgroundColor)
			verticalView?.adapter?.notifyDataSetChanged()
			goTo(locator)
		} else {
			renderMode(locator)
		}
	}

	private fun renderMode(locator: Locator, pageInChapter: Int? = null) {
		val container = viewBinding?.readerContainer ?: return
		if (container.width == 0 || container.height == 0) {
			container.post { renderMode(locator, pageInChapter) }
			return
		}
		val alreadyThere = currentLocator() == locator.clamped()
		lastLocator = locator.clamped()
		if (!isPagedMode && verticalView != null) {
			// re-emissions of the same state must not re-snap the scroll position
			if (!alreadyThere) goTo(lastLocator)
			return
		}
		if (isPagedMode && pagerView != null && pages.any {
				it.chapter == lastLocator.chapter && lastLocator.offset in it.start until it.end
			}
		) {
			goTo(lastLocator)
			return
		}
		restoring = true
		renderGeneration++
		if (isPagedMode) {
			renderPaged(container, lastLocator, pageInChapter)
		} else {
			container.removeAllViews()
			verticalView = null
			pagerView = null
			renderVertical(container, lastLocator)
		}
	}

	private fun renderVertical(container: FrameLayout, locator: Locator) {
		viewBinding?.readerContainer?.isVerticalReadingMode = true
		chapterDividerPaint.color = foregroundColor
		val recycler = RecyclerView(requireContext()).apply {
			layoutParams = FrameLayout.LayoutParams(-1, -1)
			layoutManager = object : LinearLayoutManager(context) {
				override fun onRequestChildFocus(
					parent: RecyclerView,
					state: RecyclerView.State,
					child: View,
					focused: View?,
				): Boolean = true
			}
			adapter = ChapterAdapter()
			itemAnimator = null
			overScrollMode = View.OVER_SCROLL_NEVER
			isClickable = false
			isFocusable = false
			descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
			setBackgroundColor(backgroundColor)
			clipToPadding = true
			setPadding(0, scrollTopClipPx, 0, 0)
			addItemDecoration(chapterDividerDecoration)
			addOnScrollListener(object : RecyclerView.OnScrollListener() {
				override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = scheduleProgress()
			})
		}
		verticalView = recycler
		container.addView(recycler)
		positionVertical(locator)
	}

	private fun renderPaged(container: FrameLayout, locator: Locator, pageInChapter: Int? = null) {
		val generation = ++renderGeneration
		val key = "${container.width}:${container.height}:$effectiveFontSize:${readerTypeface.hashCode()}:" +
			"${settings.epubCustomFontRevision}:" +
			"$effectiveLineHeight:$effectiveParagraphSpacing:$effectiveHorizontalPadding:$effectiveVerticalPadding:" +
			"$effectiveTextAlign:${settings.epubReadingMode}:${settings.isEpubPublisherStyleEnabled}"
		container.setBackgroundColor(backgroundColor)
		if (pages.isNotEmpty() && paginationKey == key && pageRange?.contains(locator.chapter) == true) {
			renderPagedReady(container, locator, pageInChapter)
			return
		}
		val range = (locator.chapter - PAGE_LOOKAHEAD).coerceAtLeast(0)..
			(locator.chapter + PAGE_LOOKAHEAD).coerceAtMost(chapters.lastIndex)
		viewLifecycleOwner.lifecycleScope.launch {
			// Load on IO before laying out on Default: remote text sources fetch here, and a network
			// call must never run on a CPU dispatcher.
			withContext(Dispatchers.IO) { ensureChaptersLoaded(range) }
			val newPages = withContext(Dispatchers.Default) { paginate(container.width, container.height, range) }
			if (generation != renderGeneration || !isPagedMode || viewBinding?.readerContainer !== container) return@launch
			pages = newPages
			paginationKey = key
			pageRange = range
			renderPagedReady(container, locator, pageInChapter)
		}
	}

	private fun renderPagedReady(container: FrameLayout, locator: Locator, pageInChapter: Int? = null) {
		viewBinding?.readerContainer?.isVerticalReadingMode = false
		restoring = true
		container.removeAllViews()
		verticalView = null
		pagerView = null
		val pager = ViewPager2(requireContext()).apply {
			layoutParams = FrameLayout.LayoutParams(-1, -1)
			orientation = ViewPager2.ORIENTATION_HORIZONTAL
			layoutDirection = if (isRtlPagedMode) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
			adapter = PageAdapter()
			overScrollMode = View.OVER_SCROLL_NEVER
			isClickable = false
			isFocusable = false
			registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
				override fun onPageSelected(position: Int) {
				if (!restoring) {
					notifyProgress()
					extendPageWindow(position)
				}
			}
			})
		}
		(pager.getChildAt(0) as? RecyclerView)?.apply {
			isClickable = false
			isFocusable = false
			descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
		}
		pagerView = pager
		container.addView(pager)
		val locatorTarget = pages.indexOfFirst {
			it.chapter == locator.chapter && locator.offset >= it.start && locator.offset < it.end
		}.takeIf { it >= 0 } ?: pages.indexOfLast { it.chapter <= locator.chapter }.coerceAtLeast(0)
		val firstChapterPage = pages.indexOfFirst { it.chapter == locator.chapter }
		val chapterPageCount = pages.count { it.chapter == locator.chapter }
		val savedTarget = pageInChapter
			?.takeIf { firstChapterPage >= 0 && it in 0 until chapterPageCount }
			?.let { firstChapterPage + it }
			?.takeIf { index ->
				val savedPage = pages[index]
				val tolerance = chapters[locator.chapter].text.length / 1000 + 1
				locator.offset in savedPage.start until savedPage.end ||
					kotlin.math.abs(locator.offset - savedPage.start) <= tolerance
			}
		val target = savedTarget ?: locatorTarget
		pager.setCurrentItem(target, false)
		pager.post {
			restoring = false
			notifyProgress()
			extendPageWindow(pager.currentItem)
		}
	}

	private fun extendPageWindow(position: Int) {
		if (extendingPages) return
		val range = pageRange ?: return
		val chapter = pages.getOrNull(position)?.chapter ?: return
		val prepend = chapter == range.first && range.first > 0
		val append = chapter == range.last && range.last < chapters.lastIndex
		if (!prepend && !append) return
		val target = if (prepend) range.first - 1 else range.last + 1
		val pager = pagerView ?: return
		val generation = renderGeneration
		extendingPages = true
		viewLifecycleOwner.lifecycleScope.launch {
			withContext(Dispatchers.IO) { ensureChaptersLoaded(target..target) }
			val added = withContext(Dispatchers.Default) { paginate(pager.width, pager.height, target..target) }
			if (generation == renderGeneration && pagerView === pager) {
				val adapter = pager.adapter ?: run { extendingPages = false; return@launch }
				if (prepend) {
					restoring = true
					pages = added + pages
					pageRange = target..range.last
					adapter.notifyItemRangeInserted(0, added.size)
					pager.setCurrentItem(position + added.size, false)
					pager.post { restoring = false; notifyProgress() }
				} else {
					val start = pages.size
					pages = pages + added
					pageRange = range.first..target
					adapter.notifyItemRangeInserted(start, added.size)
				}
			}
			extendingPages = false
		}
	}

	private fun paginate(viewWidth: Int, viewHeight: Int, range: IntRange): List<NativePage> {
		// Callers load [range] on IO first - see renderPaged/extendPageWindow.
		val density = resources.displayMetrics.density
		val horizontal = (effectiveHorizontalPadding * density).toInt().coerceAtLeast(1)
		val verticalTop = verticalTopPaddingPx
		val verticalBottom = verticalBottomPaddingPx
		val width = (viewWidth - horizontal * 2).coerceAtLeast(1)
		val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
			color = foregroundColor
			textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics) *
				effectiveFontSize / 100f
			typeface = readerTypeface
		}
		// A page fragment gets its own font padding when rendered. Reserving one line prevents the
		// final baseline from being clipped when the vertical margins leave a tight viewport.
		val bottomSafety = ceil(paint.fontSpacing * effectiveLineHeight / 100f).toInt()
		val height = (viewHeight - verticalTop - verticalBottom - bottomSafety).coerceAtLeast(1)
		val result = ArrayList<NativePage>()
		range.forEach { chapterIndex ->
			val chapterText = styledChapterText(chapterIndex)
			val layout = StaticLayout.Builder.obtain(chapterText, 0, chapterText.length, paint, width)
				.setAlignment(textAlignment)
				.setTextDirection(if (isRtlPagedMode) TextDirectionHeuristics.RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
				.setIncludePad(true)
				.setLineSpacing(0f, effectiveLineHeight / 100f)
				.apply {
					if (effectiveTextAlign == "justify") {
						enableInterWordJustificationCompat()
					}
				}.build()
			var start = 0
			while (start < chapterText.length) {
				val firstLine = layout.getLineForOffset(start)
				val bottom = (layout.getLineTop(firstLine) + height).coerceAtMost(layout.height)
				val lastLine = layout.getLineForVertical((bottom - 1).coerceAtLeast(0))
				val end = layout.getLineEnd(lastLine).coerceAtLeast(start + 1).coerceAtMost(chapterText.length)
				result += NativePage(chapterIndex, start, end)
				start = end
			}
		}
		return result.ifEmpty {
			val chapter = range.first.coerceIn(chapters.indices)
			listOf(NativePage(chapter, 0, chapters[chapter].text.length))
		}
	}

	@SuppressLint("WrongConstant")
	private fun StaticLayout.Builder.enableInterWordJustificationCompat() {
		// StaticLayout supports this value from API 26, but its current annotation only names API 29 constants.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setJustificationMode(1)
	}

	private fun createTextView(parent: ViewGroup, paged: Boolean): TextView = EpubSelectableTextView(parent.context).apply {
		layoutParams = if (paged) ViewGroup.LayoutParams(-1, -1) else ViewGroup.LayoutParams(-1, -2)
		applyTextStyle(this, paged)
		setTextIsSelectable(true)
		setTag(R.id.tag_epub_selectable_text, true)
		customSelectionActionModeCallback = TextSelectionCallback(this)
		installHighlightTapHandler(this)
	}

	private fun styledChapterText(chapterIndex: Int): Spanned {
		val chapter = chapters[chapterIndex]
		val text = SpannableString(chapter.text)
		text.getSpans(0, text.length, ForegroundColorSpan::class.java).forEach(text::removeSpan)
		text.getSpans(0, text.length, BackgroundColorSpan::class.java).forEach(text::removeSpan)
		if (!settings.isEpubPublisherStyleEnabled) {
			text.getSpans(0, text.length, AlignmentSpan::class.java).forEach(text::removeSpan)
			text.getSpans(0, text.length, AbsoluteSizeSpan::class.java).forEach(text::removeSpan)
			text.getSpans(0, text.length, RelativeSizeSpan::class.java).forEach(text::removeSpan)
			text.getSpans(0, text.length, TypefaceSpan::class.java).forEach(text::removeSpan)
		}
		applyParagraphSpacing(text)
		highlights.forEach { bookmark ->
			if (bookmark.chapterId != chapter.id) return@forEach
			val highlight = bookmark.epubHighlight ?: return@forEach
			val start = highlight.start.coerceIn(0, text.length)
			val end = highlight.end.coerceIn(start, text.length)
			if (start == end) return@forEach
			text.setSpan(HighlightColorSpan(highlightColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
			text.setSpan(HighlightMarker(bookmark.pageId), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
		}
		return text
	}

	private fun applyParagraphSpacing(text: Spannable) {
		val extra = (effectiveParagraphSpacing * resources.displayMetrics.density).toInt()
		if (extra == 0) return
		var index = text.indexOf('\n')
		while (index >= 0) {
			var end = index + 1
			while (end < text.length && text[end] == '\n') end++
			if (end - index > 1) {
				text.setSpan(ParagraphSpacingSpan(extra), end - 1, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
			}
			index = text.indexOf('\n', end)
		}
	}

	private fun applyTextStyle(textView: TextView, paged: Boolean) = with(textView) {
		setTextColor(foregroundColor)
		setBackgroundColor(backgroundColor)
		textSize = 16f * effectiveFontSize / 100f
		typeface = readerTypeface
		setLineSpacing(0f, effectiveLineHeight / 100f)
		includeFontPadding = true
		applyTextAlignment(this)
		val density = resources.displayMetrics.density
		val h = (effectiveHorizontalPadding * density).toInt()
		val top = if (paged) verticalTopPaddingPx else (20 * density).toInt()
		val bottom = if (paged) verticalBottomPaddingPx else (36 * density).toInt()
		setPadding(h, top, h, bottom)
	}

	private fun applyTextAlignment(textView: TextView) = with(textView) {
		layoutDirection = View.LAYOUT_DIRECTION_LTR
		textDirection = if (isRtlPagedMode) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_FIRST_STRONG
		gravity = Gravity.TOP or when (effectiveTextAlign) {
			"center" -> Gravity.CENTER_HORIZONTAL
			"right", "end" -> Gravity.END
			"justify" -> if (isRtlPagedMode) Gravity.END else Gravity.START
			else -> Gravity.START
		}
		textAlignment = when (effectiveTextAlign) {
			"center" -> View.TEXT_ALIGNMENT_CENTER
			"right", "end" -> View.TEXT_ALIGNMENT_GRAVITY
			"justify" -> if (isRtlPagedMode) View.TEXT_ALIGNMENT_TEXT_START else View.TEXT_ALIGNMENT_VIEW_START
			else -> View.TEXT_ALIGNMENT_VIEW_START
		}
		if (android.os.Build.VERSION.SDK_INT >= 26) {
			justificationMode = if (effectiveTextAlign == "justify") {
				Layout.JUSTIFICATION_MODE_INTER_WORD
			} else {
				Layout.JUSTIFICATION_MODE_NONE
			}
		}
	}

	private inner class TextSelectionCallback(
		private val textView: TextView,
	) : ActionMode.Callback {

		override fun onCreateActionMode(mode: ActionMode?, menu: Menu): Boolean {
			textView.parent?.requestDisallowInterceptTouchEvent(true)
			pagerView?.isUserInputEnabled = false
			menu.add(Menu.NONE, ACTION_DICTIONARY, Menu.NONE, R.string.dictionary)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
			menu.add(Menu.NONE, ACTION_HIGHLIGHT, Menu.NONE, R.string.highlight)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
			menu.add(Menu.NONE, ACTION_REMOVE_HIGHLIGHT, Menu.NONE, R.string.remove_highlight_action)
				.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
			return updateSelectionActions(menu)
		}

		override fun onPrepareActionMode(mode: ActionMode?, menu: Menu): Boolean = updateSelectionActions(menu)

		override fun onActionItemClicked(mode: ActionMode?, item: MenuItem): Boolean {
			val selection = selectedText(textView) ?: return false
			return when (item.itemId) {
				ACTION_DICTIONARY -> {
					showDictionary(selection.text)
					mode?.finish()
					true
				}

				ACTION_HIGHLIGHT -> {
					addHighlight(selection)
					mode?.finish()
					true
				}

				ACTION_REMOVE_HIGHLIGHT -> {
					selectedHighlight(selection)?.let { removeHighlight(it.pageId) } ?: return false
					mode?.finish()
					true
				}

				else -> false
			}
		}

		override fun onDestroyActionMode(mode: ActionMode?) {
			textView.parent?.requestDisallowInterceptTouchEvent(false)
			pagerView?.isUserInputEnabled = true
		}

		private fun updateSelectionActions(menu: Menu): Boolean {
			val selection = selectedText(textView)
			val highlight = selection?.let(::selectedHighlight)
			menu.findItem(ACTION_DICTIONARY)?.isVisible = selection?.text?.matches(WORD_PATTERN) == true
			menu.findItem(ACTION_HIGHLIGHT)?.isVisible = selection != null && highlight == null
			menu.findItem(ACTION_REMOVE_HIGHLIGHT)?.isVisible = highlight != null
			return true
		}
	}

	private fun selectedText(textView: TextView): SelectedText? {
		val location = textView.tag as? TextLocation ?: return null
		val value = textView.text
		var start = minOf(textView.selectionStart, textView.selectionEnd).coerceAtLeast(0)
		var end = maxOf(textView.selectionStart, textView.selectionEnd).coerceAtMost(value.length)
		while (start < end && value[start].isWhitespace()) start++
		while (end > start && value[end - 1].isWhitespace()) end--
		if (start == end) return null
		return SelectedText(
			chapter = location.chapter,
			start = location.baseOffset + start,
			end = location.baseOffset + end,
			text = value.subSequence(start, end).toString(),
		)
	}

	private fun addHighlight(selection: SelectedText) {
		val manga = viewModel.getMangaOrNull() ?: return
		val chapter = chapters.getOrNull(selection.chapter) ?: return
		if (highlights.any {
				it.chapterId == chapter.id && it.epubHighlight?.let { h ->
					h.start == selection.start && h.end == selection.end
				} == true
			}) return
		val bookmark = Bookmark(
			manga = manga,
			pageId = UUID.randomUUID().leastSignificantBits and Long.MAX_VALUE,
			chapterId = chapter.id,
			page = selection.start,
			scroll = (selection.start.toLong() * 1000 / chapter.text.length.coerceAtLeast(1)).toInt(),
			imageUrl = epubHighlightUrl(selection.end, selection.text),
			createdAt = Instant.now(),
			percent = selection.start / chapter.text.length.coerceAtLeast(1).toFloat(),
		)
		viewLifecycleOwner.lifecycleScope.launch {
			withContext(Dispatchers.IO) { bookmarksRepository.addBookmark(bookmark) }
			Toast.makeText(requireContext(), R.string.highlight_added, Toast.LENGTH_SHORT).show()
		}
	}

	private fun selectedHighlight(selection: SelectedText): Bookmark? {
		val chapterId = chapters.getOrNull(selection.chapter)?.id ?: return null
		return highlights.firstOrNull { bookmark ->
			bookmark.chapterId == chapterId && bookmark.epubHighlight?.let { highlight ->
				selection.start < highlight.end && selection.end > highlight.start
			} == true
		}
	}

	private fun removeHighlight(bookmarkId: Long) {
		viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
			bookmarksRepository.removeBookmarks(setOf(bookmarkId))
		}
	}

	private fun installHighlightTapHandler(textView: TextView) {
		val detector = GestureDetector(textView.context, object : GestureDetector.SimpleOnGestureListener() {
			override fun onDown(e: MotionEvent): Boolean = true

			override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
				val marker = findHighlightAt(textView, e) ?: return false
				showRemoveHighlight(marker.bookmarkId)
				return true
			}
		})
		textView.setOnTouchListener { _, event ->
			detector.onTouchEvent(event)
			false
		}
	}

	private fun findHighlightAt(textView: TextView, event: MotionEvent): HighlightMarker? {
		val text = textView.text as? Spanned ?: return null
		if (text.isEmpty()) return null
		val layout = textView.layout ?: return null
		val x = (event.x - textView.totalPaddingLeft + textView.scrollX).toInt()
		val y = (event.y - textView.totalPaddingTop + textView.scrollY).toInt()
		if (x !in 0..layout.width || y !in 0..layout.height) return null
		val line = layout.getLineForVertical(y)
		val offset = layout.getOffsetForHorizontal(line, x.toFloat()).coerceIn(0, text.length - 1)
		return text.getSpans(offset, offset + 1, HighlightMarker::class.java).firstOrNull()
	}

	private fun showRemoveHighlight(bookmarkId: Long) {
		val bookmark = highlights.firstOrNull { it.pageId == bookmarkId } ?: return
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.remove_highlight)
			.setMessage(bookmark.epubHighlight?.text)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.remove) { _, _ ->
				removeHighlight(bookmarkId)
			}
			.show()
	}

	private fun showDictionary(word: String) {
		val binding = SheetEpubDictionaryBinding.inflate(layoutInflater)
		val dialog = BottomSheetDialog(requireContext())
		binding.textViewWord.text = word
		binding.buttonClose.setOnClickListener { dialog.dismiss() }
		dialog.setContentView(binding.root)
		dialog.show()
		viewLifecycleOwner.lifecycleScope.launch {
			val result = runCatching {
				withContext(Dispatchers.IO) { loadDictionary(word) }
			}
			if (!dialog.isShowing) return@launch
			binding.progressBar.isVisible = false
			binding.scrollViewContent.isVisible = true
			val entry = result.getOrNull()
			binding.textViewContent.text = when {
				result.isFailure -> getString(R.string.dictionary_error)
				entry == null -> getString(R.string.dictionary_not_found)
				else -> formatDictionaryEntry(entry)
			}
			binding.textViewPhonetic.text = entry?.phonetic.orEmpty()
			binding.textViewPhonetic.isVisible = !entry?.phonetic.isNullOrBlank()
			binding.textViewSource.isVisible = entry != null
		}
	}

	private fun loadDictionary(word: String): DictionaryEntry? {
		val url = DICTIONARY_URL.toHttpUrl().newBuilder().addPathSegment(word).build()
		val request = Request.Builder().url(url).build()
		val body = httpClient.newCall(request).execute().use { response ->
			if (response.code == 404) return null
			check(response.isSuccessful) { "Dictionary request failed: ${response.code}" }
			response.body.string()
		}
		return parseDictionaryEntry(body)
	}

	private fun parseDictionaryEntry(body: String): DictionaryEntry? {
		val entry = Json.parseToJsonElement(body).jsonArray.firstOrNull()?.jsonObject ?: return null
		val meanings = entry["meanings"]?.jsonArray.orEmpty().mapNotNull { meaningElement ->
			val meaning = meaningElement.jsonObject
			val definitions = meaning["definitions"]?.jsonArray.orEmpty().mapNotNull { definitionElement ->
				val definition = definitionElement.jsonObject
				val text = definition["definition"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
				DictionaryDefinition(text, definition["example"]?.jsonPrimitive?.contentOrNull)
			}.take(MAX_DEFINITIONS_PER_MEANING)
			if (definitions.isEmpty()) return@mapNotNull null
			DictionaryMeaning(
				partOfSpeech = meaning["partOfSpeech"]?.jsonPrimitive?.contentOrNull.orEmpty(),
				definitions = definitions,
				synonyms = meaning["synonyms"]?.jsonArray.orEmpty()
					.mapNotNull { it.jsonPrimitive.contentOrNull }.take(MAX_SYNONYMS),
			)
		}.take(MAX_MEANINGS)
		if (meanings.isEmpty()) return null
		return DictionaryEntry(
			phonetic = entry["phonetic"]?.jsonPrimitive?.contentOrNull.orEmpty(),
			meanings = meanings,
		)
	}

	private fun formatDictionaryEntry(entry: DictionaryEntry): CharSequence {
		val text = SpannableStringBuilder()
		entry.meanings.forEachIndexed { meaningIndex, meaning ->
			if (meaningIndex > 0) text.append("\n\n")
			val headingStart = text.length
			text.append(meaning.partOfSpeech.ifBlank { getString(R.string.dictionary) })
			text.setSpan(StyleSpan(Typeface.BOLD), headingStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
			meaning.definitions.forEachIndexed { index, definition ->
				text.append("\n${index + 1}. ${definition.text}")
				definition.example?.takeIf(String::isNotBlank)?.let {
					text.append("\n   ${getString(R.string.dictionary_example)}: “$it”")
				}
			}
			if (meaning.synonyms.isNotEmpty()) {
				text.append("\n${getString(R.string.dictionary_synonyms)}: ${meaning.synonyms.joinToString()}")
			}
		}
		return text
	}

	private fun currentLocator(): Locator {
		// mid-restore the views are not at the target position yet; trust the target instead
		// so idle/pause saves and scheduled reflows never capture a transient page 0
		if (restoring) return lastLocator.clamped()
		pagerView?.let { pager ->
			return pages.getOrNull(pager.currentItem)?.let { Locator(it.chapter, it.start) } ?: lastLocator
		}
		verticalView?.let { recycler ->
			val manager = recycler.layoutManager as? LinearLayoutManager ?: return lastLocator
			val index = manager.findFirstVisibleItemPosition().takeIf { it >= 0 } ?: return lastLocator
			val child = manager.findViewByPosition(index) ?: return Locator(index, 0)
			val layout = (child as? TextView)?.layout ?: return lastLocator
			val visibleOffset = (recycler.paddingTop - child.top).coerceIn(0, layout.height)
			val charOffset = layout.getLineStart(layout.getLineForVertical(visibleOffset))
			return Locator(index, charOffset).clamped()
		}
		return lastLocator.clamped()
	}

	private fun scheduleProgress() {
		if (restoring || progressScheduled) return
		progressScheduled = true
		viewBinding?.root?.postDelayed({
			progressScheduled = false
			notifyProgress()
		}, PROGRESS_INTERVAL_MS)
	}

	private fun notifyProgress() {
		if (chapters.isEmpty()) return
		val locator = currentLocator().clamped()
		lastLocator = locator
		val chapter = chapters[locator.chapter]
		val chapterPm = (locator.offset.toLong() * 1000 / chapter.text.length.coerceAtLeast(1)).toInt()
		val globalPage = pagerView?.currentItem ?: 0
		val firstChapterPage = if (pagerView != null) pages.indexOfFirst { it.chapter == locator.chapter }.coerceAtLeast(0) else 0
		val page = globalPage - firstChapterPage
		val pageCount = if (pagerView != null) pages.count { it.chapter == locator.chapter } else 0
		viewModel.onEpubProgressChanged(chapter.id, locator.offset, chapterPm, page, pageCount)
	}

	override fun getCurrentState(): ReaderState? {
		if (chapters.isEmpty()) return null
		val locator = currentLocator().clamped()
		val chapter = chapters[locator.chapter]
		val firstChapterPage = pagerView?.let { pages.indexOfFirst { page -> page.chapter == locator.chapter } } ?: 0
		val page = pagerView?.currentItem?.minus(firstChapterPage)?.coerceAtLeast(0) ?: 0
		return ReaderState(
			chapter.id,
			page,
			ReaderState.encodeEpubOffset(locator.offset),
		)
	}

	override fun switchPageBy(delta: Int) {
		pagerView?.let {
			it.setCurrentItem((it.currentItem + delta).coerceIn(0, pages.lastIndex), isAnimationEnabled())
			return
		}
		verticalView?.smoothScrollBy(0, (verticalView?.height ?: 0) * 9 / 10 * delta)
	}

	override fun switchPageTo(position: Int, smooth: Boolean) {
		pagerView?.let {
			val chapter = pages.getOrNull(it.currentItem)?.chapter ?: return
			val first = pages.indexOfFirst { page -> page.chapter == chapter }.coerceAtLeast(0)
			val count = pages.count { page -> page.chapter == chapter }
			it.setCurrentItem(first + position.coerceIn(0, count - 1), smooth && isAnimationEnabled())
			return
		}
		val chapter = currentLocator().chapter
		val offset = (chapters[chapter].text.length.toLong() * position.coerceIn(0, 1000) / 1000).toInt()
		goTo(Locator(chapter, offset), smooth)
	}

	override fun scrollBy(delta: Int, smooth: Boolean): Boolean {
		val recycler = verticalView ?: return false
		if (!recycler.canScrollVertically(delta)) return false
		if (smooth) recycler.smoothScrollBy(0, delta) else recycler.scrollBy(0, delta)
		return true
	}

	private fun goTo(locator: Locator, smooth: Boolean = false) {
		lastLocator = locator.clamped()
		if (pagerView != null) {
			val page = pages.indexOfFirst { it.chapter == lastLocator.chapter && lastLocator.offset in it.start until it.end }
			if (page >= 0) pagerView?.setCurrentItem(page, smooth && isAnimationEnabled())
		} else {
			positionVertical(lastLocator)
		}
	}

	private fun positionVertical(locator: Locator) {
		val target = locator.clamped()
		val recycler = verticalView ?: return
		val manager = recycler.layoutManager as LinearLayoutManager
		lastLocator = target
		restoring = true
		recycler.doOnNextLayout {
			val textView = manager.findViewByPosition(target.chapter) as? TextView
			val layout = textView?.layout
			if (layout != null) {
				val offset = target.offset.coerceIn(0, textView.text.length)
				recycler.scrollBy(0, layout.getLineTop(layout.getLineForOffset(offset)))
			}
			restoring = false
			notifyProgress()
		}
		manager.scrollToPositionWithOffset(target.chapter, 0)
	}

	override fun onZoomIn() {
		viewBinding?.readerContainer?.zoomIn()
	}

	override fun onZoomOut() {
		viewBinding?.readerContainer?.zoomOut()
	}

	fun showBookSearch() {
		val input = TextInputEditText(requireContext()).apply { setSingleLine() }
		val field = TextInputLayout(requireContext()).apply {
			hint = getString(R.string.epub_search_hint)
			boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
			setStartIconDrawable(R.drawable.ic_search)
			addView(input)
		}
		val container = FrameLayout(requireContext()).apply {
			val margin = (24 * resources.displayMetrics.density).toInt()
			setPadding(margin, 8, margin, 0)
			addView(field, FrameLayout.LayoutParams(-1, -2))
		}
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.epub_search_book).setView(container)
			.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(R.string.search) { _, _ -> searchBook(input.text.toString().trim()) }.show()
	}

	/**
	 * Remote sources search only what is already loaded: fetching every chapter of a 2000-chapter web
	 * novel to grep it would hammer the site and take minutes.
	 */
	private fun searchBook(query: String) {
		if (query.isEmpty() || chapters.isEmpty()) return
		val isRemote = chapterContent is RemoteContentSource
		Toast.makeText(requireContext(), R.string.loading_, Toast.LENGTH_SHORT).show()
		viewLifecycleOwner.lifecycleScope.launch {
			val results = withContext(Dispatchers.IO) {
				if (!isRemote) ensureChaptersLoaded(chapters.indices)
				chapters.mapIndexedNotNull { index, chapter ->
					val match = chapter.text.indexOf(query, ignoreCase = true).takeIf { it >= 0 } ?: return@mapIndexedNotNull null
					val start = (match - 45).coerceAtLeast(0)
					val end = (match + query.length + 70).coerceAtMost(chapter.text.length)
					SearchResult(index, chapter.title, chapter.text.substring(start, end).replace(Regex("\\s+"), " ").trim(), match)
				}.take(MAX_SEARCH_RESULTS)
			}
			if (results.isEmpty()) {
				Toast.makeText(requireContext(), R.string.epub_no_search_results, Toast.LENGTH_SHORT).show()
				return@launch
			}
			val adapter = object : ArrayAdapter<SearchResult>(requireContext(), android.R.layout.simple_list_item_2, android.R.id.text1, results) {
				override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
					val row = super.getView(position, convertView, parent)
					val result = getItem(position) ?: return row
					row.findViewById<TextView>(android.R.id.text1).text = result.title.ifEmpty { getString(R.string.epub_untitled_chapter) }
					row.findViewById<TextView>(android.R.id.text2).apply { text = result.snippet; maxLines = 2 }
					return row
				}
			}
			val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.search_results)
				.setAdapter(adapter) { _, index -> goTo(Locator(results[index].chapter, results[index].offset)) }
				.setNegativeButton(android.R.string.cancel, null).show()
			dialog.listView.apply {
				divider = ColorDrawable(requireContext().getThemeColor(materialR.attr.colorOutlineVariant, Color.GRAY))
				dividerHeight = resources.displayMetrics.density.toInt().coerceAtLeast(1)
			}
		}
	}

	private val isPagedMode get() = settings.epubReadingMode != EPUB_MODE_SCROLL
	private val isRtlPagedMode get() = settings.epubReadingMode == EPUB_MODE_PAGED_RTL
	private val effectiveTextAlign get() = when {
		settings.isEpubPublisherStyleEnabled -> if (isRtlPagedMode) "right" else "left"
		isRtlPagedMode && settings.epubTextAlign == "left" -> "right"
		else -> settings.epubTextAlign
	}
	private val effectiveFontSize get() = if (settings.isEpubPublisherStyleEnabled) 100 else settings.epubFontSize
	private val effectiveLineHeight get() = if (settings.isEpubPublisherStyleEnabled) 120 else settings.epubLineHeight
	private val effectiveParagraphSpacing get() =
		if (settings.isEpubPublisherStyleEnabled) 0 else settings.epubParagraphSpacing
	private val effectiveHorizontalPadding get() =
		if (settings.isEpubPublisherStyleEnabled) PUBLISHER_HORIZONTAL_PADDING_DP else settings.epubHorizontalPadding
	private val effectiveVerticalPadding get() =
		if (settings.isEpubPublisherStyleEnabled) VERTICAL_MARGIN_MAX else settings.epubVerticalPadding
	private val verticalMarginFraction get() = (effectiveVerticalPadding / VERTICAL_MARGIN_MAX.toFloat()).coerceIn(0f, 1f)
	private val verticalTopPaddingPx get(): Int {
		val maximum = pagedTopBarClearancePx +
			(PAGED_TOP_EXTRA_MARGIN_DP * resources.displayMetrics.density).toInt()
		return scrollTopClipPx + ((maximum - scrollTopClipPx) * verticalMarginFraction).toInt()
	}
	private val verticalBottomPaddingPx get() =
		(MAX_BOTTOM_MARGIN_DP * verticalMarginFraction * resources.displayMetrics.density).toInt()
	private val backgroundColor: Int get() {
		if (settings.epubTheme == EPUB_THEME_CUSTOM) {
			return ColorUtils.setAlphaComponent(settings.epubCustomBackgroundColor, 255)
		}
		val dark = when (settings.epubTheme) {
			"white", "light" -> false
			"gray", "dark" -> true
			"black" -> return Color.BLACK
			else -> resources.isNightMode
		}
		return ContextThemeWrapper(requireContext(), if (dark) materialR.style.ThemeOverlay_Material3_Dark else materialR.style.ThemeOverlay_Material3_Light)
			.getThemeColor(android.R.attr.colorBackground, if (dark) Color.BLACK else Color.WHITE)
	}
	private val foregroundColor get() = if (settings.epubTheme == EPUB_THEME_CUSTOM) {
		ColorUtils.setAlphaComponent(settings.epubCustomTextColor, 255)
	} else if (ColorUtils.calculateLuminance(backgroundColor) > .5) {
		0xFF1B1B1F.toInt()
	} else {
		0xFFE4E4E8.toInt()
	}
	private val highlightColor get() = if (settings.epubTheme == EPUB_THEME_CUSTOM) {
		ColorUtils.setAlphaComponent(settings.epubCustomHighlightColor, HIGHLIGHT_ALPHA)
	} else {
		DEFAULT_HIGHLIGHT_COLOR
	}
	private val readerTypeface get() = when {
		settings.isEpubPublisherStyleEnabled -> Typeface.SERIF
		settings.epubFontFamily == EPUB_FONT_CUSTOM -> customReaderTypeface()
		else -> Typeface.create(settings.epubFontFamily.substringBefore(',').trim().trim('\'', '"'), Typeface.NORMAL)
	}

	private fun customReaderTypeface(): Typeface {
		val file = File(requireContext().filesDir, AppSettings.EPUB_CUSTOM_FONT_FILE)
		val stamp = if (file.isFile) file.lastModified() xor file.length() else Long.MIN_VALUE
		if (stamp != cachedCustomTypefaceStamp) {
			cachedCustomTypefaceStamp = stamp
			cachedCustomTypeface = file.takeIf(File::isFile)?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
		}
		return cachedCustomTypeface ?: Typeface.SERIF
	}
	private val textAlignment get() = when (effectiveTextAlign) {
		"center" -> Layout.Alignment.ALIGN_CENTER
		"right", "end" -> if (isRtlPagedMode) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_OPPOSITE
		"justify" -> Layout.Alignment.ALIGN_NORMAL
		else -> Layout.Alignment.ALIGN_NORMAL
	}

	private inner class ChapterAdapter : RecyclerView.Adapter<TextHolder>() {
		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = TextHolder(createTextView(parent, false))
		override fun getItemCount() = chapters.size
		override fun onBindViewHolder(holder: TextHolder, position: Int) {
			applyTextStyle(holder.text, false)
			holder.text.tag = TextLocation(position, 0)
			holder.text.text = styledChapterText(position)
			preloadAround(position)
		}
	}

	private inner class PageAdapter : RecyclerView.Adapter<TextHolder>() {
		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = TextHolder(createTextView(parent, true))
		override fun getItemCount() = pages.size
		override fun onBindViewHolder(holder: TextHolder, position: Int) {
			val page = pages[position]
			val chapterText = styledChapterText(page.chapter)
			val visibleStart = (page.start until page.end).firstOrNull { !chapterText[it].isWhitespace() } ?: page.end
			applyTextStyle(holder.text, true)
			holder.text.tag = TextLocation(page.chapter, visibleStart)
			holder.text.text = chapterText.subSequence(visibleStart, page.end)
		}
	}

	private class TextHolder(val text: TextView) : RecyclerView.ViewHolder(text)
	private class HighlightColorSpan(var color: Int) : CharacterStyle(), UpdateAppearance {
		override fun updateDrawState(tp: TextPaint) {
			tp.bgColor = color
		}
	}

	private class EpubSelectableTextView(context: Context) : AppCompatTextView(
		ContextThemeWrapper(context, R.style.ThemeOverlay_Kotatsu_EpubSelectableText),
	) {
		private val selectionBackgroundColor = highlightColor
		private var selectionBackgroundSpan: SelectionBackgroundSpan? = null
		private var suppressDoubleTap = false
		private val doubleTapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
			override fun onDown(e: MotionEvent): Boolean = true

			override fun onDoubleTap(e: MotionEvent): Boolean {
				suppressDoubleTap = true
				return true
			}
		})

		init {
			setHighlightColor(Color.TRANSPARENT)
		}

		override fun onAttachedToWindow() {
			super.onAttachedToWindow()
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				setTextSelectHandleLeft(createSelectionHandle())
				setTextSelectHandleRight(createSelectionHandle())
				setTextSelectHandle(createSelectionHandle())
			}
		}

		override fun onTouchEvent(event: MotionEvent): Boolean {
			doubleTapDetector.onTouchEvent(event)
			if (suppressDoubleTap) {
				if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
					suppressDoubleTap = false
				}
				return true
			}
			return super.onTouchEvent(event)
		}

		private fun createSelectionHandle(): Drawable {
			val size = (14 * resources.displayMetrics.density).toInt()
			return GradientDrawable().apply {
				shape = GradientDrawable.OVAL
				setColor(ColorUtils.setAlphaComponent(selectionBackgroundColor, 255))
				setSize(size, size)
			}
		}

		override fun onSelectionChanged(selStart: Int, selEnd: Int) {
			super.onSelectionChanged(selStart, selEnd)
			val spannable = text as? Spannable ?: return
			selectionBackgroundSpan?.let(spannable::removeSpan)
			selectionBackgroundSpan = null
			if (selStart < 0 || selEnd < 0 || selStart == selEnd) return
			val span = SelectionBackgroundSpan(this, selectionBackgroundColor, selStart, selEnd)
			selectionBackgroundSpan = span
			spannable.setSpan(
				span,
				minOf(selStart, selEnd),
				maxOf(selStart, selEnd),
				Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
			)
		}

		fun correctedHorizontal(offset: Int, line: Int): Float {
			val textLayout = layout ?: return 0f
			val horizontal = textLayout.getPrimaryHorizontal(offset)
			if (Build.VERSION.SDK_INT < 26 || justificationMode != Layout.JUSTIFICATION_MODE_INTER_WORD) return horizontal
			val lineStart = textLayout.getLineStart(line)
			val lineEnd = textLayout.getLineEnd(line)
			if (lineEnd >= text.length || text[lineEnd - 1] == '\n') return horizontal
			var visibleEnd = lineEnd
			while (visibleEnd > lineStart && text[visibleEnd - 1].isWhitespace()) visibleEnd--
			val spaces = (lineStart until visibleEnd).count { text[it] == ' ' }
			if (spaces == 0) return horizontal
			val extraPerSpace = (textLayout.width - paint.measureText(text, lineStart, visibleEnd)) / spaces
			val spacesBefore = (lineStart until offset.coerceAtMost(visibleEnd)).count { text[it] == ' ' }
			return horizontal + textLayout.getParagraphDirection(line) * extraPerSpace * spacesBefore
		}

		fun selectionEndHorizontal(offset: Int, line: Int): Float {
			val textLayout = layout ?: return 0f
			if (offset < textLayout.getLineEnd(line)) return correctedHorizontal(offset, line)
			return if (textLayout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT) {
				textLayout.getLineLeft(line)
			} else {
				textLayout.getLineRight(line)
			}
		}
	}

	private class SelectionBackgroundSpan(
		private val textView: EpubSelectableTextView,
		color: Int,
		start: Int,
		end: Int,
	) : LineBackgroundSpan, NoCopySpan {
		private val selectionStart = minOf(start, end)
		private val selectionEnd = maxOf(start, end)
		private val backgroundPaint = Paint().apply { this.color = color }

		override fun drawBackground(
			canvas: Canvas,
			paint: Paint,
			left: Int,
			right: Int,
			top: Int,
			baseline: Int,
			bottom: Int,
			text: CharSequence,
			start: Int,
			end: Int,
			lineNumber: Int,
		) {
			val rangeStart = maxOf(start, selectionStart)
			val rangeEnd = minOf(end, selectionEnd)
			if (rangeStart >= rangeEnd) return
			val x1 = textView.correctedHorizontal(rangeStart, lineNumber)
			val x2 = textView.selectionEndHorizontal(rangeEnd, lineNumber)
			val metrics = paint.fontMetrics
			canvas.drawRect(
				minOf(x1, x2),
				baseline + metrics.ascent,
				maxOf(x1, x2),
				baseline + metrics.descent,
				backgroundPaint,
			)
		}
	}

	private class HighlightMarker(val bookmarkId: Long) : CharacterStyle() {
		override fun updateDrawState(tp: TextPaint) = Unit
	}
	private class ParagraphSpacingSpan(private val extra: Int) : LineHeightSpan {
		override fun chooseHeight(
			text: CharSequence,
			start: Int,
			end: Int,
			spanstartv: Int,
			v: Int,
			fm: Paint.FontMetricsInt,
		) {
			fm.descent += extra
			fm.bottom += extra
		}
	}
	private class NativeChapter(
		val id: Long,
		val title: String,
		/** Local EPUB: `file:/path/book.epub#OEBPS/ch01.xhtml`. Remote text source: the chapter path. */
		val url: String,
	) {
		@Volatile
		var content: Spanned? = null
		val text: Spanned get() = content ?: EMPTY_CHAPTER_TEXT
	}

	/**
	 * Where a chapter's raw HTML and its inline images come from. Local EPUBs read them out of a zip;
	 * remote text sources fetch them. Everything else in this reader — pagination, locators, highlights,
	 * search, progress — only ever touches [NativeChapter.text], so it stays content-agnostic.
	 */
	private interface ChapterContent : Closeable {
		/** Blocking. Throws on failure; a failed load must not be cached. */
		fun loadHtml(url: String): String

		/** [ByteArray] for an embedded resource, absolute-url [String] for a remote one, or null. */
		fun imageData(chapterUrl: String, source: String): Any?
	}

	private class ZipContentSource(private val archives: Map<File, ZipFile>) : ChapterContent {

		private val lock = Any()

		override fun loadHtml(url: String): String {
			val uri = url.toUri()
			val entryName = uri.fragment.orEmpty()
			return synchronized(lock) {
				val zip = archives[File(uri.schemeSpecificPart)] ?: return ""
				val entry = zip.getEntry(entryName) ?: zip.getEntry(entryName.removePrefix("/"))
				entry?.let { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }.orEmpty()
			}
		}

		override fun imageData(chapterUrl: String, source: String): Any? {
			// A downloaded novel keeps its illustrations as absolute urls; let coil fetch those.
			if (source.startsWith("http://", true) || source.startsWith("https://", true)) return source
			val uri = chapterUrl.toUri()
			val entryName = resolveEpubEntry(uri.fragment.orEmpty(), source)
			return synchronized(lock) {
				val archive = archives[File(uri.schemeSpecificPart)] ?: return null
				val entry = archive.getEntry(entryName)
					?: archive.getEntry(entryName.removePrefix("/"))
					?: return null
				archive.getInputStream(entry).use { it.readBytes() }
			}
		}

		override fun close() {
			synchronized(lock) { archives.values.forEach(ZipFile::close) }
		}

		private fun resolveEpubEntry(chapterEntry: String, source: String): String {
			val cleanSource = source.substringBefore('#').substringBefore('?').replace(" ", "%20")
			return URI("/${chapterEntry.replace('\\', '/')}").resolve(cleanSource).normalize().path.removePrefix("/")
		}
	}

	/**
	 * Chapters fetched from a novel plugin. Images are never downloaded here — the src is handed back
	 * as an absolute url so coil loads it through the source's own headers, exactly like a cover.
	 */
	private class RemoteContentSource(
		private val repository: MangaRepository,
		chapters: List<MangaChapter>,
	) : ChapterContent {

		private val byUrl = chapters.associateBy { it.url }

		// runBlocking is safe: ChapterContent.loadHtml is documented blocking and every caller
		// already hops to Dispatchers.IO first.
		override fun loadHtml(url: String): String = runBlocking {
			val chapter = byUrl[url] ?: return@runBlocking ""
			repository.getChapterHtml(chapter).orEmpty()
		}

		// Inline images in prose are often site-relative, so they get resolved against the source's
		// own site — the plugin's for an LNReader novel, the extension's base url for a Tsundoku one.
		override fun imageData(chapterUrl: String, source: String): Any? =
			when (val novelSource = repository.source.unwrap()) {
				is LnMangaSource -> novelSource.absoluteUrl(source)
				is MihonMangaSource -> (novelSource.catalogueSource as? HttpSource)
					?.let { resolveAgainst(it.baseUrl, source) }

				else -> null
			}

		private fun resolveAgainst(baseUrl: String, value: String): String = when {
			value.startsWith("http://") || value.startsWith("https://") -> value
			value.startsWith("//") -> "https:$value"
			else -> baseUrl.trimEnd('/') + "/" + value.trimStart('/')
		}

		override fun close() = Unit
	}

	private data class PreparedBook(val chapters: List<NativeChapter>, val content: ChapterContent)
	private data class NativePage(val chapter: Int, val start: Int, val end: Int)
	private data class SearchResult(val chapter: Int, val title: String, val snippet: String, val offset: Int)
	private data class Locator(val chapter: Int, val offset: Int)
	private data class TextLocation(val chapter: Int, val baseOffset: Int)
	private data class SelectedText(val chapter: Int, val start: Int, val end: Int, val text: String)
	private data class DictionaryEntry(val phonetic: String, val meanings: List<DictionaryMeaning>)
	private data class DictionaryMeaning(
		val partOfSpeech: String,
		val definitions: List<DictionaryDefinition>,
		val synonyms: List<String>,
	)
	private data class DictionaryDefinition(val text: String, val example: String?)
	private fun Locator.clamped(): Locator {
		if (chapters.isEmpty()) return Locator(0, 0)
		val c = chapter.coerceIn(chapters.indices)
		return Locator(c, offset.coerceIn(0, chapters[c].text.length.coerceAtLeast(1) - 1))
	}

	companion object {
		private const val EPUB_MODE_SCROLL = "scroll"
		private const val EPUB_MODE_PAGED_RTL = "paged_rtl"
		private const val EPUB_THEME_CUSTOM = "custom"
		private const val EPUB_FONT_CUSTOM = "custom"
		private const val MAX_SEARCH_RESULTS = 100
		private const val PROGRESS_INTERVAL_MS = 50L
		private const val PAGE_LOOKAHEAD = 1
		private const val PRELOAD_RADIUS = 2
		private const val SCROLL_INFO_BAR_HEIGHT_DP = 24
		private const val PAGED_TOP_BAR_FALLBACK_DP = 80
		private const val PAGED_TOP_EXTRA_MARGIN_DP = 16
		private const val REPAGINATE_DELAY_MS = 180L
		private const val COLOR_ANIMATION_MS = 180L
		private const val VERTICAL_MARGIN_MAX = 112
		private const val PUBLISHER_HORIZONTAL_PADDING_DP = 20
		private const val MAX_BOTTOM_MARGIN_DP = 96
		private const val MAX_IMAGE_HEIGHT_FRACTION = 0.75f
		private const val ACTION_DICTIONARY = 0x455001
		private const val ACTION_HIGHLIGHT = 0x455002
		private const val ACTION_REMOVE_HIGHLIGHT = 0x455003
		private const val HIGHLIGHT_ALPHA = 0x66
		private const val DEFAULT_HIGHLIGHT_COLOR = 0x66FFD54F
		private const val DICTIONARY_URL = "https://api.dictionaryapi.dev/api/v2/entries/en"
		private const val MAX_MEANINGS = 4
		private const val MAX_DEFINITIONS_PER_MEANING = 3
		private const val MAX_SYNONYMS = 8
		private val WORD_PATTERN = Regex("[\\p{L}\\p{M}]+(?:['’\\-][\\p{L}\\p{M}]+)*")
		private val EMPTY_CHAPTER_TEXT = SpannedString("\u2014")
	}
}
