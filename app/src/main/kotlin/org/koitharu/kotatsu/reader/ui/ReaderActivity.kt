package org.koitharu.kotatsu.reader.ui

import android.app.assist.AssistContent
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.transition.Fade
import androidx.transition.Slide
import androidx.transition.Transition
import androidx.transition.TransitionListenerAdapter
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.R as materialR
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.bookmarks.domain.epubHighlight
import org.koitharu.kotatsu.core.exceptions.resolve.DialogErrorObserver
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseFullscreenActivity
import org.koitharu.kotatsu.core.ui.dialog.setCheckbox
import org.koitharu.kotatsu.core.ui.util.MenuInvalidator
import org.koitharu.kotatsu.core.ui.widgets.ZoomControl
import org.koitharu.kotatsu.core.util.IdlingDetector
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.getThemeDimensionPixelOffset
import org.koitharu.kotatsu.core.util.ext.hasGlobalPoint
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.postDelayed
import org.koitharu.kotatsu.core.util.ext.toUriOrNull
import org.koitharu.kotatsu.core.util.ext.zipWithPrevious
import org.koitharu.kotatsu.databinding.ActivityReaderBinding
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesViewModel
import org.koitharu.kotatsu.details.ui.pager.pages.PagesSavedObserver
import org.koitharu.kotatsu.local.data.isEpub
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.reader.data.TapGridSettings
import org.koitharu.kotatsu.reader.domain.TapGridArea
import org.koitharu.kotatsu.reader.domain.UpscaleEffect
import org.koitharu.kotatsu.reader.ui.upscale.UpscalePreviewDialog
import org.koitharu.kotatsu.reader.ui.config.ReaderConfigSheet
import org.koitharu.kotatsu.reader.ui.epub.EpubReaderFragment
import org.koitharu.kotatsu.reader.ui.tts.ReaderTts
import org.koitharu.kotatsu.reader.ui.tts.ReaderTtsService
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import org.koitharu.kotatsu.reader.ui.pager.ReaderUiState
import org.koitharu.kotatsu.reader.ui.tapgrid.TapGridDispatcher
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import androidx.appcompat.R as appcompatR

@AndroidEntryPoint
class ReaderActivity :
    BaseFullscreenActivity<ActivityReaderBinding>(),
    TapGridDispatcher.OnGridTouchListener,
    ReaderConfigSheet.Callback,
    ReaderControlDelegate.OnInteractionListener,
    ReaderNavigationCallback,
    IdlingDetector.Callback,
    ZoomControl.ZoomControlListener,
    View.OnClickListener,
    ScrollTimerControlView.OnVisibilityChangeListener {

    @Inject
    lateinit var settings: AppSettings

    @Inject
    lateinit var tapGridSettings: TapGridSettings

    @Inject
    lateinit var pageSaveHelperFactory: PageSaveHelper.Factory

    @Inject
    lateinit var scrollTimerFactory: ScrollTimer.Factory

    @Inject
    lateinit var screenOrientationHelper: ScreenOrientationHelper

    private val idlingDetector = IdlingDetector(TimeUnit.SECONDS.toMillis(10), this)

    private val viewModel: ReaderViewModel by viewModels()

    override val readerMode: ReaderMode?
        get() = readerManager.currentMode

    private lateinit var scrollTimer: ScrollTimer

    @Inject
    lateinit var tts: ReaderTts
    private lateinit var pageSaveHelper: PageSaveHelper
    private lateinit var touchHelper: TapGridDispatcher
    private lateinit var controlDelegate: ReaderControlDelegate
    private var gestureInsets: Insets = Insets.NONE
    private var dockedToolbarHeight = 0
    private var isPanelOffsetAnimating = false
    // Set when a long-press action consumed the gesture; the remainder of the touch stream is
    // delivered to the content as ACTION_CANCEL so the pager doesn't scroll under the opened sheet.
    private var isTouchCancelled = false
    private lateinit var readerManager: ReaderManager
    private val hideUiRunnable = Runnable { setUiIsVisible(false) }

    // Tracks whether the foldable device is in an unfolded state (half-opened or flat)
    private var isFoldUnfolded: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityReaderBinding.inflate(layoutInflater))
        readerManager = ReaderManager(supportFragmentManager, viewBinding.container, settings)
        setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
        applyTranslucentReaderBars()
        touchHelper = TapGridDispatcher(viewBinding.root, this)
        scrollTimer = scrollTimerFactory.create(resources, this, this)
        pageSaveHelper = pageSaveHelperFactory.create(this)
        controlDelegate = ReaderControlDelegate(resources, settings, tapGridSettings, this)
        viewBinding.zoomControl.listener = this
        viewBinding.actionsView.listener = this
        viewBinding.buttonTimer?.setOnClickListener(this)
        idlingDetector.bindToLifecycle(this)
        screenOrientationHelper.applySettings()
        viewModel.isBookmarkAdded.observe(this) { viewBinding.actionsView.isBookmarkAdded = it }
        scrollTimer.isActive.observe(this) {
            updateScrollTimerButton()
            viewBinding.actionsView.setTimerActive(it)
        }
        viewBinding.timerControl.onVisibilityChangeListener = this
        viewBinding.timerControl.attach(scrollTimer, this)
        viewBinding.ttsControl.onVisibilityChangeListener = this
        viewBinding.ttsControl.attach(tts, this)
        tts.isPlaying.observe(this) {
            updateScrollTimerButton()
            if (it) {
                scrollTimer.setActive(false)
                ReaderTtsService.start(this, viewModel.getMangaOrNull()?.title.orEmpty())
            }
        }
        if (resources.getBoolean(R.bool.is_tablet)) {
            viewBinding.timerControl.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                topMargin = marginEnd + getThemeDimensionPixelOffset(appcompatR.attr.actionBarSize)
            }
        }

        viewModel.onLoadingError.observeEvent(
            this,
            DialogErrorObserver(
                host = viewBinding.container,
                fragment = null,
                resolver = exceptionResolver,
                onResolved = { isResolved ->
                    if (isResolved) {
                        viewModel.reload()
                    } else if (viewModel.content.value.pages.isEmpty()) {
                        dispatchNavigateUp()
                    }
                },
            ),
        )
        viewModel.onError.observeEvent(
            this,
            SnackbarErrorObserver(
                host = viewBinding.container,
                fragment = null,
                resolver = exceptionResolver,
                onResolved = null,
            ),
        )
        viewModel.readerMode.observe(this, Lifecycle.State.STARTED, this::onInitReader)
        settings.observeAsFlow(AppSettings.KEY_EPUB_READING_MODE) { epubReadingMode }.observe(this) {
            if (readerManager.isEpub) viewBinding.actionsView.setSliderReversed(it == EPUB_MODE_PAGED_RTL)
        }
        viewModel.onPageSaved.observeEvent(this, PagesSavedObserver(viewBinding.container))
        viewModel.uiState.zipWithPrevious().observe(this, this::onUiStateChanged)
        combine(
            viewModel.isLoading,
            viewModel.content.map { it.pages.isNotEmpty() }.distinctUntilChanged(),
            ::Pair,
        ).flowOn(Dispatchers.Default)
            .observe(this, this::onLoadingStateChanged)
        viewModel.isKeepScreenOnEnabled.observe(this, this::setKeepScreenOn)
        viewModel.isInfoBarEnabled.observe(this, ::onReaderBarChanged)
        viewModel.isBookmarkAdded.observe(this, MenuInvalidator(this))
        viewModel.onAskNsfwIncognito.observeEvent(this) { askForIncognitoMode() }
        viewModel.onShowToast.observeEvent(this) { msgId ->
            Snackbar.make(viewBinding.container, msgId, Snackbar.LENGTH_SHORT)
                .setAnchorView(viewBinding.toolbarDocked)
                .show()
        }
        viewModel.readerSettingsProducer.observe(this) {
            viewBinding.infoBar.applyColorScheme(isBlackOnWhite = it.background.isLight(this))
        }
        viewModel.isZoomControlsEnabled.observe(this) {
            viewBinding.zoomControl.isVisible = it
        }
        addMenuProvider(
            ReaderMenuProvider(
                onOpenMenu = ::openMenu,
                onSearchBook = ::openEpubSearch,
                onShowUpscalePreview = ::showUpscalePreview,
                isEpub = { readerManager.isEpub },
                isUpscaleActive = { UpscaleEffect.activePages.value.isNotEmpty() },
                isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            ),
        )
        UpscaleEffect.activePages.map { it.isNotEmpty() }.distinctUntilChanged()
            .observe(this, MenuInvalidator(this))
        // Long-pressing the top bar offers the chapter currently being read in the built-in
        // browser — the same action the chapters list already offers on a selected chapter.
        viewBinding.toolbar.setOnLongClickListener(::onToolbarLongClick)
        viewModel.onOpenChapterInBrowser.observeEvent(this) { url ->
            val manga = viewModel.getMangaOrNull()
            router.openBrowser(url = url, source = manga?.source, title = manga?.title)
        }

        observeWindowLayout()

        // Apply initial double-mode considering foldable setting
        applyDoubleModeAuto()
    }

    override fun getParentActivityIntent(): Intent? {
        val manga = viewModel.getMangaOrNull() ?: return null
        return AppRouter.detailsIntent(this, manga)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (!viewBinding.timerControl.isVisible) {
            scrollTimer.onUserInteraction()
        }
        idlingDetector.onUserInteraction()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onPause()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onStop()
    }

    override fun onDestroy() {
        // Backgrounding the reader keeps speaking; leaving it does not. A rotation is neither.
        if (isFinishing) {
            tts.stop()
            ReaderTtsService.stop(this)
        }
        super.onDestroy()
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        viewModel.getMangaOrNull()?.publicUrl?.toUriOrNull()?.let { outContent.webUri = it }
    }

    override fun isNsfwContent(): Flow<Boolean> = viewModel.isMangaNsfw

    override fun onIdle() {
        viewModel.saveCurrentState(readerManager.currentReader?.getCurrentState())
        viewModel.onIdle()
    }

    override fun onVisibilityChanged(v: View, visibility: Int) {
        // Set the offset before the panel's own slide captures its end value, so it slides in at
        // the right height instead of appearing at the bottom edge and jumping up afterwards.
        updateTimerPanelOffset(animate = false)
        updateScrollTimerButton()
        (readerManager.currentReader as? EpubReaderFragment)?.setTtsPickMode(viewBinding.ttsControl.isVisible)
    }

    override fun onZoomIn() {
        readerManager.currentReader?.onZoomIn()
    }

    override fun onZoomOut() {
        readerManager.currentReader?.onZoomOut()
    }

    override fun onClick(v: View) {
        when (v.id) {
            // One floating button for both: whichever of the two is running owns it.
            R.id.button_timer -> if (tts.isPlaying.value) {
                viewBinding.ttsControl.showOrHide()
            } else if (readerManager.isEpub && settings.isReaderTtsFabVisible) {
                onTextToSpeechClick()
            } else {
                onScrollTimerClick(isLongClick = false)
            }
        }
    }

    private fun onInitReader(mode: ReaderMode?) {
        if (mode == null) {
            return
        }
        readerManager.isEpub = viewModel.getMangaOrNull()?.isEpub == true
        viewBinding.timerControl.setEpubReader(readerManager.isEpub)
        updateScrollTimerButton()
        if (readerManager.currentMode != mode) {
            readerManager.replace(mode)
        }
        invalidateOptionsMenu()
        if (viewBinding.appbarTop.isVisible) {
            lifecycle.postDelayed(TimeUnit.SECONDS.toMillis(1), hideUiRunnable)
        }
        viewBinding.actionsView.setSliderReversed(
            if (readerManager.isEpub) settings.epubReadingMode == EPUB_MODE_PAGED_RTL else mode == ReaderMode.REVERSED,
        )
        viewBinding.timerControl.onReaderModeChanged(mode)
    }

    private fun onLoadingStateChanged(value: Pair<Boolean, Boolean>) {
        val (isLoading, hasPages) = value
        val showLoadingLayout = isLoading && !hasPages
        if (viewBinding.layoutLoading.isVisible != showLoadingLayout) {
            val transition = Fade().addTarget(viewBinding.layoutLoading)
            TransitionManager.beginDelayedTransition(viewBinding.root, transition)
            viewBinding.layoutLoading.isVisible = showLoadingLayout
        }
        if (isLoading && hasPages) {
            viewBinding.toastView.show(R.string.loading_)
        } else {
            viewBinding.toastView.hide()
        }
        invalidateOptionsMenu()
    }

    override fun onGridTouch(area: TapGridArea, horizontalFraction: Float): Boolean {
        if (!isReaderResumed()) return false
        return if (readerManager.isEpub) {
            if (settings.isEpubPagedTapGesturesEnabled && settings.epubReadingMode != EPUB_MODE_SCROLL) {
                when {
                    horizontalFraction < 1f / 3f -> switchPageBy(-1)
                    horizontalFraction > 2f / 3f -> switchPageBy(1)
                    else -> toggleUiVisibility()
                }
            } else {
                toggleUiVisibility()
            }
            true
        } else {
            controlDelegate.onGridTouch(area)
        }
    }

    override fun onGridLongTouch(area: TapGridArea): Boolean {
        val handled = !readerManager.isEpub && isReaderResumed() && controlDelegate.onGridLongTouch(area)
        // The finger is still down when the long-press action fires (e.g. the config sheet opens),
        // and the ongoing gesture keeps feeding the pager underneath — any drift after the
        // long-press scrolls the page abruptly. Cancel the rest of this gesture for the content.
        if (handled) {
            isTouchCancelled = true
        }
        return handled
    }

    override fun onProcessTouch(rawX: Int, rawY: Int): Boolean {
        return if (
            rawX <= gestureInsets.left ||
            rawY <= gestureInsets.top ||
            rawX >= viewBinding.root.width - gestureInsets.right ||
            rawY >= viewBinding.root.height - gestureInsets.bottom ||
            viewBinding.appbarTop.hasGlobalPoint(rawX, rawY) ||
            viewBinding.toolbarDocked?.hasGlobalPoint(rawX, rawY) == true
        ) {
            false
        } else {
            val touchables = window.peekDecorView()?.touchables
            touchables?.none {
                it.hasGlobalPoint(rawX, rawY) && it.getTag(R.id.tag_epub_selectable_text) != true
            } != false
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        touchHelper.dispatchTouchEvent(ev)
        if (!viewBinding.timerControl.hasGlobalPoint(ev.rawX.toInt(), ev.rawY.toInt())) {
            scrollTimer.onTouchEvent(ev)
        }
        if (isTouchCancelled) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                isTouchCancelled = false
            }
            val cancel = MotionEvent.obtain(ev)
            cancel.action = MotionEvent.ACTION_CANCEL
            val result = super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return result
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return controlDelegate.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return controlDelegate.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)
    }

    override fun onChapterSelected(chapter: MangaChapter): Boolean {
        jumpToChapter(chapter.id, page = 0, scroll = 0, isPeekPreferred = false)
        return true
    }

    override fun onBookmarkSelected(bookmark: Bookmark): Boolean {
        return if (bookmark.epubHighlight != null) {
            // epub highlights keep the exact character offset in `page`; scroll is only a coarse permille
            jumpToChapter(
                bookmark.chapterId,
                page = 0,
                scroll = ReaderState.encodeEpubOffset(bookmark.page),
                isPeekPreferred = true,
            )
            true
        } else {
            onPageSelectedImpl(ReaderPage(bookmark.toMangaPage(), bookmark.page, bookmark.chapterId), isPeekPreferred = true)
        }
    }

    override fun onPageSelected(page: ReaderPage): Boolean = onPageSelectedImpl(page, isPeekPreferred = false)

    private fun onPageSelectedImpl(page: ReaderPage, isPeekPreferred: Boolean): Boolean {
        lifecycleScope.launch(Dispatchers.Default) {
            val pages = viewModel.content.value.pages
            val index = pages.indexOfFirst { it.chapterId == page.chapterId && it.id == page.id }
            if (index != -1) {
                withContext(Dispatchers.Main) {
                    readerManager.currentReader?.switchPageTo(index, true)
                }
            } else withContext(Dispatchers.Main) {
                jumpToChapter(page.chapterId, page.index, scroll = 0, isPeekPreferred = isPeekPreferred)
            }
        }
        return true
    }

    /**
     * Routes an explicit chapter jump through the progress guard: normal continuation switches
     * as usual, jumps away from the saved progress either peek (keeping history untouched) or
     * ask the user. [isPeekPreferred] (bookmarks) resolves ambiguous jumps to a silent peek.
     */
    private fun jumpToChapter(chapterId: Long, page: Int, scroll: Int, isPeekPreferred: Boolean) {
        lifecycleScope.launch {
            when (viewModel.getChapterOpenMode(chapterId)) {
                ChaptersPagesViewModel.ChapterOpenMode.NORMAL -> {
                    viewModel.setPeekMode(false)
                    viewModel.switchChapter(chapterId, page, scroll)
                }

                ChaptersPagesViewModel.ChapterOpenMode.ASK -> if (isPeekPreferred) {
                    viewModel.setPeekMode(true)
                    viewModel.switchChapter(chapterId, page, scroll)
                } else {
                    showChapterJumpDialog(
                        activity = this@ReaderActivity,
                        onPeek = {
                            viewModel.setPeekMode(true)
                            viewModel.switchChapter(chapterId, page, scroll)
                        },
                        onMoveProgress = {
                            viewModel.setPeekMode(false)
                            viewModel.switchChapter(chapterId, page, scroll)
                        },
                        onDisable = { viewModel.disableChapterJumpDialog() },
                    )
                }
            }
        }
    }

    override fun onReaderModeChanged(mode: ReaderMode) {
        viewModel.saveCurrentState(readerManager.currentReader?.getCurrentState())
        viewModel.switchMode(mode)
        viewBinding.timerControl.onReaderModeChanged(mode)
    }

    override fun onDoubleModeChanged(isEnabled: Boolean) {
        // Combine manual toggle with foldable auto setting
        applyDoubleModeAuto(isEnabled)
    }

    private fun applyDoubleModeAuto(manualEnabled: Boolean? = null) {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // Auto double-page on foldable when device is unfolded (half-opened or flat)
        val autoFoldable = settings.isReaderDoubleOnFoldable && isFoldUnfolded
        val manualLandscape = (manualEnabled ?: settings.isReaderDoubleOnLandscape) && isLandscape
        val autoEnabled = autoFoldable || manualLandscape
        readerManager.setDoubleReaderMode(autoEnabled)
    }

    private fun setKeepScreenOn(isKeep: Boolean) {
        if (isKeep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Keep the top bar translucent while the bottom floating toolbar stays opaque.
    private fun applyTranslucentReaderBars() {
        viewBinding.appbarTop.setBackgroundColor(getThemeColor(materialR.attr.colorSurface, TOP_READER_BAR_ALPHA))
        viewBinding.toolbar.background = null
        viewBinding.toolbarDocked?.background = viewBinding.toolbarDocked?.background?.mutate()?.apply {
            alpha = 0xFF
        }
    }

    private fun setUiIsVisible(isUiVisible: Boolean) {
        if (viewBinding.appbarTop.isVisible != isUiVisible) {
            var isAnimated = false
            if (isAnimationsEnabled) {
                val transition = TransitionSet()
                    .setOrdering(TransitionSet.ORDERING_TOGETHER)
                    .addTransition(Slide(Gravity.TOP).addTarget(viewBinding.appbarTop))
                    .addTransition(Fade().addTarget(viewBinding.infoBar))
                viewBinding.toolbarDocked?.let {
                    transition.addTransition(Slide(Gravity.BOTTOM).addTarget(it))
                }
                // Re-dispatching insets changes the reader's own padding and the toolbar margins,
                // which forces a full re-layout of the page view *while* the bars are sliding —
                // that is the stutter. Hold it back until the slide is done.
                transition.addListener(object : TransitionListenerAdapter() {
                    override fun onTransitionEnd(transition: Transition) {
                        viewBinding.root.requestApplyInsets()
                    }
                })
                TransitionManager.beginDelayedTransition(viewBinding.root, transition)
                isAnimated = true
            }
            val isFullscreen = settings.isReaderFullscreenEnabled
            viewBinding.appbarTop.isVisible = isUiVisible
            viewBinding.toolbarDocked?.isVisible = isUiVisible
            viewBinding.infoBar.isGone = isUiVisible || (!viewModel.isInfoBarEnabled.value)
            viewBinding.infoBar.isTimeVisible = isFullscreen
            updateScrollTimerButton()
            updateTimerPanelOffset(animate = isAnimated)
            systemUiController.setSystemUiVisible(isUiVisible || !isFullscreen)
            if (!isAnimated) {
                viewBinding.root.requestApplyInsets()
            }
        }
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        gestureInsets = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val tappableInsets = insets.getInsets(WindowInsetsCompat.Type.tappableElement())
        viewBinding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = systemBars.top
            rightMargin = systemBars.right
            leftMargin = systemBars.left
        }
        viewBinding.toolbarDocked?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            val dockedMargin = resources.getDimensionPixelSize(R.dimen.screen_padding)
            bottomMargin = tappableInsets.bottom + dockedMargin
            rightMargin = systemBars.right + dockedMargin
            leftMargin = systemBars.left + dockedMargin
        } ?: viewBinding.actionsView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = tappableInsets.bottom
            rightMargin = systemBars.right
            leftMargin = systemBars.left
        }
        viewBinding.toolbarDocked?.let { docked ->
            dockedToolbarHeight = maxOf(dockedToolbarHeight, docked.height)
            val panelMargin = tappableInsets.bottom + resources.getDimensionPixelSize(R.dimen.screen_padding)
            viewBinding.timerControl.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                if (bottomMargin != panelMargin) {
                    bottomMargin = panelMargin
                }
            }
            updateTimerPanelOffset(animate = false)
        }
        // The info bar must hug the real top edge: BaseActivity inflates the top inset with the
        // hidden status bar's height (so toolbars keep their place), but this bar replaces the
        // status bar, so pad it with the actual visible inset only.
        viewBinding.infoBar.updatePadding(
            top = ViewCompat.getRootWindowInsets(v)
                ?.getInsets(WindowInsetsCompat.Type.systemBars())?.top
                ?: systemBars.top,
        )
        val innerInsets = Insets.of(
            systemBars.left,
            if (viewBinding.appbarTop.isVisible) viewBinding.appbarTop.height else systemBars.top,
            systemBars.right,
            viewBinding.toolbarDocked?.takeIf { it.isVisible }?.height ?: systemBars.bottom,
        )
        return WindowInsetsCompat.Builder(insets)
            .setInsets(WindowInsetsCompat.Type.systemBars(), innerInsets)
            .build()
    }

    override fun switchPageBy(delta: Int) {
        readerManager.currentReader?.switchPageBy(delta)
    }

    override fun switchChapterBy(delta: Int) {
        viewModel.switchChapterBy(delta)
    }

    override fun openMenu() {
        val currentMode = readerManager.currentMode ?: return
        viewBinding.root.post {
            viewModel.saveCurrentState(readerManager.currentReader?.getCurrentState())
            router.showReaderConfigSheet(currentMode)
        }
    }

    override fun scrollBy(delta: Int, smooth: Boolean): Boolean {
        return readerManager.currentReader?.scrollBy(delta, smooth) == true
    }

    override fun toggleUiVisibility() {
        setUiIsVisible(!viewBinding.appbarTop.isVisible)
    }

    override fun isReaderResumed(): Boolean {
        val reader = readerManager.currentReader ?: return false
        return reader.isResumed && supportFragmentManager.fragments.lastOrNull() === reader
    }

    override fun onBookmarkClick() {
        // Only buzz when adding a bookmark (not when removing one).
        if (viewModel.isBookmarkAdded.value != true) {
            viewBinding.actionsView.hapticFeedback(HapticEffect.CONFIRM)
        }
        viewModel.toggleBookmark()
    }

    override fun onSavePageClick() {
        viewModel.saveCurrentPage(pageSaveHelper)
    }

    override fun onScrollTimerClick(isLongClick: Boolean) {
        viewBinding.ttsControl.hide()
        if (isLongClick) {
            scrollTimer.setActive(!scrollTimer.isActive.value)
        } else {
            viewBinding.timerControl.showOrHide()
        }
    }

    override fun onTextToSpeechClick() {
        val reader = readerManager.currentReader as? EpubReaderFragment ?: return
        settings.isReaderTtsFabVisible = true
        viewBinding.timerControl.hide()
        viewBinding.ttsControl.show()
        if (!tts.isAttached) {
            reader.startTts()
        }
    }

	private fun openEpubSearch() {
		(readerManager.currentReader as? EpubReaderFragment)?.showBookSearch()
	}

	private fun showUpscalePreview() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			UpscalePreviewDialog.show(supportFragmentManager)
		}
	}

    override fun toggleScreenOrientation() {
        if (screenOrientationHelper.toggleScreenOrientation()) {
            Snackbar.make(
                viewBinding.container,
                if (screenOrientationHelper.isLocked) {
                    R.string.screen_rotation_locked
                } else {
                    R.string.screen_rotation_unlocked
                },
                Snackbar.LENGTH_SHORT,
            ).setAnchorView(viewBinding.toolbarDocked)
                .show()
        }
    }

    override fun switchPageTo(index: Int) {
        if (readerManager.isEpub) {
            // slider scrubs through the chapter as a percentage
            readerManager.currentReader?.switchPageTo(index, true)
            return
        }
        val pages = viewModel.getCurrentChapterPages()
        val page = pages?.getOrNull(index) ?: return
        val chapterId = viewModel.getCurrentState()?.chapterId ?: return
        onPageSelected(ReaderPage(page, index, chapterId))
    }

    private fun onToolbarLongClick(view: View): Boolean {
        val chapterId = viewModel.getCurrentState()?.chapterId ?: return false
        view.hapticFeedback(HapticEffect.LONG_PRESS)
        PopupMenu(view.context, view, Gravity.START).run {
            inflate(R.menu.opt_browser)
            setOnMenuItemClickListener { item ->
                (item.itemId == R.id.action_browser).also { isHandled ->
                    if (isHandled) viewModel.openChapterInBrowser(chapterId)
                }
            }
            show()
        }
        return true
    }

    private fun onReaderBarChanged(isBarEnabled: Boolean) {
        viewBinding.infoBar.isVisible = isBarEnabled && viewBinding.appbarTop.isGone
    }

    private fun onUiStateChanged(pair: Pair<ReaderUiState?, ReaderUiState?>) {
        val (previous: ReaderUiState?, uiState: ReaderUiState?) = pair
        title = uiState?.mangaName ?: getString(R.string.loading_)
        viewBinding.infoBar.update(uiState)
        if (uiState == null) {
            supportActionBar?.subtitle = null
            viewBinding.actionsView.setSliderValue(0, 1)
            viewBinding.actionsView.isSliderEnabled = false
            return
        }
        viewBinding.actionsView.isSliderSmooth = uiState.isEpub && !uiState.isEpubPaged
        val chapterTitle = uiState.getChapterTitle(resources)
        supportActionBar?.subtitle = when {
            uiState.incognito -> getString(R.string.incognito_mode)
            uiState.isPeek -> getString(R.string.peek_mode)
            else -> chapterTitle
        }
        if (
            chapterTitle != previous?.getChapterTitle(resources) &&
            chapterTitle.isNotEmpty()
        ) {
            viewBinding.toastView.showTemporary(chapterTitle, TOAST_DURATION)
        }
        if (uiState.isSliderAvailable()) {
            viewBinding.actionsView.setSliderValue(
                value = uiState.currentPage,
                max = uiState.totalPages - 1,
            )
        } else {
            viewBinding.actionsView.setSliderValue(0, 1)
        }
        viewBinding.actionsView.isSliderEnabled = uiState.isSliderAvailable()
        viewBinding.actionsView.isNextEnabled = uiState.hasNextChapter()
        viewBinding.actionsView.isPrevEnabled = uiState.hasPreviousChapter()
    }

    /**
     * Keeps the autoscroll panel just above the docked toolbar. CoordinatorLayout's
     * `dodgeInsetEdges` used to do this, but dodging is resolved in a single layout pass — it
     * teleported the panel instead of animating it. Driving translationY ourselves lets it glide
     * in step with the toolbar's slide.
     */
    private fun updateTimerPanelOffset(animate: Boolean) {
        // Tablet layouts anchor the panel to the top app bar, so there is nothing to dodge.
        if (viewBinding.toolbarDocked == null) return
        // The floating button rides along: with the dock up it would otherwise sit behind it.
        val panels = listOfNotNull<View>(viewBinding.timerControl, viewBinding.ttsControl, viewBinding.buttonTimer)
        // Hiding the system bars makes insets settle over several frames, and every one of those
        // callbacks lands here. Without this guard they snap translationY straight to the target
        // mid-flight, so the panel appears to jump while the toolbar is still sliding.
        if (!animate && isPanelOffsetAnimating) return
        val target = if (viewBinding.appbarTop.isVisible) {
            -(dockedToolbarHeight + resources.getDimensionPixelSize(R.dimen.screen_padding)).toFloat()
        } else {
            0f
        }
        panels.forEach { it.animate().cancel() }
        if (animate && isAnimationsEnabled) {
            isPanelOffsetAnimating = true
            panels.forEach { panel ->
                panel.animate()
                    .translationY(target)
                    .setDuration(PANEL_SLIDE_DURATION)
                    // same curve the toolbar's Slide uses, so the two travel as one
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction { isPanelOffsetAnimating = false }
                    .start()
            }
        } else {
            isPanelOffsetAnimating = false
            panels.forEach { it.translationY = target }
        }
    }

    private fun updateScrollTimerButton() {
        val button = viewBinding.buttonTimer ?: return
        // The TTS face of the FAB is sticky: once speech has been started it stays offered on every
        // novel until it is explicitly stopped, so resuming it doesn't mean digging through the menu.
        val isTts = tts.isPlaying.value ||
            (readerManager.isEpub && settings.isReaderTtsFabVisible)
        val isButtonVisible = (scrollTimer.isActive.value || isTts)
            && settings.isReaderAutoscrollFabVisible
            && !viewBinding.timerControl.isVisible
            && !viewBinding.ttsControl.isVisible
        button.setIconResource(if (isTts) R.drawable.ic_voice_over else R.drawable.ic_timelapse)
        if (button.isVisible == isButtonVisible) {
            return
        }
        // Nothing to fade before the view is attached: ViewPropertyAnimator would never run its
        // end action, leaving the button stuck at whatever the layout started it as.
        if (!isAnimationsEnabled || !button.isAttachedToWindow) {
            button.isVisible = isButtonVisible
            return
        }
        // Fade the FAB with its own animator instead of staging another delayed transition:
        // this runs from inside the panel-slide and toolbar-slide code paths, and a second
        // beginDelayedTransition() on the same scene root cancels the one the caller just
        // queued — which is what made the bottom area flash instead of animating.
        button.animate().cancel()
        if (isButtonVisible) {
            button.alpha = 0f
            button.isVisible = true
            button.animate().alpha(1f).setDuration(FAB_FADE_DURATION).start()
        } else {
            button.animate().alpha(0f).setDuration(FAB_FADE_DURATION).withEndAction {
                button.isVisible = false
                button.alpha = 1f
            }.start()
        }
    }

    // Observe foldable window layout to auto-enable double-page if configured
    private fun observeWindowLayout() {
        WindowInfoTracker.getOrCreate(this)
            .windowLayoutInfo(this)
            .onEach { info ->
                val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                val unfolded = when (fold?.state) {
                    FoldingFeature.State.HALF_OPENED, FoldingFeature.State.FLAT -> true
                    else -> false
                }
                if (unfolded != isFoldUnfolded) {
                    isFoldUnfolded = unfolded
                    applyDoubleModeAuto()
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun askForIncognitoMode() {
        showIncognitoModeDialog(
            activity = this,
            onResult = { incognito, dontAskAgain -> viewModel.setIncognitoMode(incognito, dontAskAgain) },
            onCancel = { finishAfterTransition() },
        )
    }

    companion object {

        private const val TOAST_DURATION = 2000L
        private const val FAB_FADE_DURATION = 200L
        // matches androidx.transition's default duration, so the panel and the toolbar move together
        private const val PANEL_SLIDE_DURATION = 300L
		private const val EPUB_MODE_SCROLL = "scroll"
		private const val EPUB_MODE_PAGED_RTL = "paged_rtl"

        private const val TOP_READER_BAR_ALPHA = 0.7f
    }
}
