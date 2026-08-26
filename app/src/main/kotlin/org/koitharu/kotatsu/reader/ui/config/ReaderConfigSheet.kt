package org.koitharu.kotatsu.reader.ui.config

import android.os.Bundle
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import android.content.res.Configuration
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.findParentCallback
import org.koitharu.kotatsu.databinding.SheetReaderConfigBinding
import org.koitharu.kotatsu.reader.ui.ReaderViewModel
import org.koitharu.kotatsu.reader.ui.ScreenOrientationHelper
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import javax.inject.Inject
import java.io.File
import kotlin.math.roundToInt
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import org.koitharu.kotatsu.local.data.isEpub
import androidx.compose.ui.BiasAlignment
import androidx.compose.animation.core.animateFloatAsState

@AndroidEntryPoint
class ReaderConfigSheet : BaseAdaptiveSheet<SheetReaderConfigBinding>() {

    private val viewModel by activityViewModels<ReaderViewModel>()

    @Inject
    lateinit var orientationHelper: ScreenOrientationHelper

    @Inject
    lateinit var mangaRepositoryFactory: MangaRepository.Factory

    @Inject
    lateinit var coil: ImageLoader

    private lateinit var mode: ReaderMode
	@Inject
	lateinit var settings: AppSettings

	private var customFontUiRevision by mutableIntStateOf(0)
	private val epubFontPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if (uri != null) importEpubFont(uri)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		mode = arguments?.getInt(AppRouter.KEY_READER_MODE)
			?.let { ReaderMode.valueOf(it) }
			?: ReaderMode.STANDARD
	}

	override fun onStart() {
		// When the reader is immersive, this sheet's own window must hide the system bars too —
		// otherwise showing it makes the bars reappear over the activity, which resizes the reader
		// (visible as an abrupt jump). FLAG_NOT_FOCUSABLE while showing defers the focus grab until
		// after the bars are hidden so they never flash in.
		val window = dialog?.window
		val activityInsets = activity?.window?.decorView?.let(ViewCompat::getRootWindowInsets)
		val barsHidden = activityInsets?.isVisible(WindowInsetsCompat.Type.statusBars()) == false
		if (window != null && barsHidden) {
			window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
			WindowInsetsControllerCompat(window, window.decorView).apply {
				systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
				hide(WindowInsetsCompat.Type.systemBars())
			}
			super.onStart()
			window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
		} else {
			super.onStart()
		}
	}

	private fun importEpubFont(uri: Uri) {
		lifecycleScope.launch {
			val name = withContext(Dispatchers.IO) { copyCompatibleFont(uri) }
			if (name == null) {
				Toast.makeText(requireContext(), R.string.epub_font_invalid, Toast.LENGTH_SHORT).show()
				return@launch
			}
			settings.epubCustomFontName = name
			settings.epubCustomFontRevision++
			settings.epubFontFamily = EPUB_FONT_CUSTOM
			customFontUiRevision++
		}
	}

	private fun copyCompatibleFont(uri: Uri): String? {
		val context = requireContext()
		val resolver = context.contentResolver
		val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
			if (cursor.moveToFirst()) cursor.getString(0) else null
		}?.takeIf(String::isNotBlank) ?: getString(R.string.epub_font_custom)
		val temporary = File(context.cacheDir, "epub_font_import")
		return try {
			resolver.openInputStream(uri)?.use { input -> temporary.outputStream().use(input::copyTo) } ?: return null
			Typeface.createFromFile(temporary)
			temporary.copyTo(File(context.filesDir, AppSettings.EPUB_CUSTOM_FONT_FILE), overwrite = true)
			displayName
		} catch (_: Exception) {
			null
		} finally {
			temporary.delete()
		}
	}

	private fun removeEpubCustomFont() {
		File(requireContext().filesDir, AppSettings.EPUB_CUSTOM_FONT_FILE).delete()
		settings.epubCustomFontName = ""
		settings.epubCustomFontRevision++
		if (settings.epubFontFamily == EPUB_FONT_CUSTOM) settings.epubFontFamily = "serif"
		customFontUiRevision++
	}

    override fun onCreateViewBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
    ): SheetReaderConfigBinding {
        return SheetReaderConfigBinding.inflate(inflater, container, false)
    }

    override fun onViewBindingCreated(
        binding: SheetReaderConfigBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onViewBindingCreated(binding, savedInstanceState)
        binding.composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        binding.composeView.setContent {
            DropSauceTheme {
                ReaderConfigContent()
            }
        }
        // Expand synchronously, before the enter animation starts — a post{} here caused a second
        // settle (the sheet visibly lifted off and dropped back) because the expanded offset was
        // computed one frame after the animation had already begun.
        expandToContent()
    }

    private fun expandToContent() {
        val b = behavior ?: return
        if (b is AdaptiveSheetBehavior.Bottom) {
            if (isLandscape()) {
                b.isFitToContents = false
                val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                if (sheet != null) {
                    sheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                }
            } else {
                b.isFitToContents = true
            }
        } else if (b is AdaptiveSheetBehavior.Side) {
            val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: dialog?.findViewById<View>(com.google.android.material.R.id.m3_side_sheet)
            if (sheet != null) {
                val displayWidth = resources.displayMetrics.widthPixels
                sheet.layoutParams.width = (displayWidth * 0.5).toInt()
                sheet.requestLayout()
            }
        }
        b.state = AdaptiveSheetBehavior.STATE_EXPANDED
    }

    private fun isLandscape(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
        return insets
    }

    @Composable
    private fun ReaderConfigContent() {
        val context = LocalContext.current
        val isEpubBook = remember { viewModel.getMangaOrNull()?.isEpub == true }
        if (isEpubBook) {
            EpubConfigContent()
            return
        }
        var currentMode by remember { mutableStateOf(mode) }

        // Pager State for 2 pages (0: Options, 1: Info)
        val pagerState = rememberPagerState(pageCount = { 2 })
        val scope = rememberCoroutineScope()

        // Settings states
        var isDoubleOnLandscape by remember { mutableStateOf(settings.isReaderDoubleOnLandscape) }
        var isDoubleOnFoldable by remember { mutableStateOf(settings.isReaderDoubleOnFoldable) }
        var sensitivity by remember { mutableFloatStateOf(settings.readerDoublePagesSensitivity * 100f) }

        // StateFlow observations from ViewModel and OrientationHelper flow
        val isBookmarkAdded by viewModel.isBookmarkAdded.collectAsState()
        val isAutoRotationEnabled by orientationHelper.observeAutoOrientation().collectAsState(initial = false)
        val uiState by viewModel.uiState.collectAsState()
        val manga = remember(uiState) { viewModel.getMangaOrNull() }

        val callback = remember { findParentCallback(Callback::class.java) }

        // Capture the nav bar inset once: the reader toggles system bars while the sheet is open,
        // and a live navigationBarsPadding() resize makes the shown sheet jump.
        val navBarPadding = remember {
            dialog?.window?.decorView?.let { decor ->
                ViewCompat.getRootWindowInsets(decor)
                    ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom
            } ?: 0
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp + with(LocalDensity.current) { navBarPadding.toDp() }),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 1. Swipeable Pager Content
                // No animateContentSize here: the sheet is fit-to-contents, so animating the body
                // height re-settles the whole sheet while it is opening.
                // The pager is pinned to the Read Mode page's height so swiping to the (otherwise
                // shorter) Tools page never resizes the sheet; the tools grid stretches to fill it.
                val density = LocalDensity.current
                var pagerHeightPx by remember { mutableIntStateOf(0) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (pagerHeightPx > 0) {
                                Modifier.height(with(density) { pagerHeightPx.toDp() })
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) { page ->
                        when (page) {
                            0 -> { // Options Page
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState())
                                        .onSizeChanged { pagerHeightPx = it.height },
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    ReadModeSection(
                                        selectedMode = currentMode,
                                        onModeSelected = { newMode ->
                                            if (newMode != currentMode) {
                                                callback?.onReaderModeChanged(newMode)
                                                currentMode = newMode
                                            }
                                        },
                                    )

                                    DoublePageConfigSection(
                                        isModeStandardOrReversed = currentMode == ReaderMode.STANDARD || currentMode == ReaderMode.REVERSED,
                                        isDoubleOnLandscape = isDoubleOnLandscape,
                                        onDoubleOnLandscapeChange = { enabled ->
                                            settings.isReaderDoubleOnLandscape = enabled
                                            isDoubleOnLandscape = enabled
                                            callback?.onDoubleModeChanged(enabled)
                                        },
                                        isDoubleOnFoldable = isDoubleOnFoldable,
                                        onDoubleOnFoldableChange = { enabled ->
                                            settings.isReaderDoubleOnFoldable = enabled
                                            isDoubleOnFoldable = enabled
                                            callback?.onDoubleModeChanged(settings.isReaderDoubleOnLandscape)
                                        },
                                        sensitivity = sensitivity,
                                        onSensitivityChange = { value ->
                                            settings.readerDoublePagesSensitivity = value / 100f
                                            sensitivity = value
                                        },
                                    )

                                    Spacer(modifier = Modifier.height(84.dp))
                                }
                            }

                            1 -> { // Info & Tools Page
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    ToolsGridSection(
                                        showPageTools = true,
                                        isAutoRotationEnabled = isAutoRotationEnabled,
                                        isBookmarkAdded = isBookmarkAdded,
                                        onSaveClick = {
                                            callback?.onSavePageClick()
                                            dismissAllowingStateLoss()
                                        },
                                        onOrientationClick = {
                                            orientationHelper.toggleScreenOrientation()
                                        },
                                        onScrollTimerClick = {
                                            callback?.onScrollTimerClick(false)
                                            dismissAllowingStateLoss()
                                        },
                                        onColorFilterClick = {
                                            val page = viewModel.getCurrentPage()
                                            val manga = viewModel.getMangaOrNull()
                                            if (page != null && manga != null) {
                                                router.openColorFilterConfig(manga, page)
                                            }
                                        },
                                        onBookmarkClick = {
                                            viewModel.toggleBookmark()
                                        },
                                        onSettingsClick = {
                                            router.openReaderSettings()
                                            dismissAllowingStateLoss()
                                        },
                                        modifier = Modifier.weight(1f),
                                    )

                                    Spacer(modifier = Modifier.height(84.dp))
                                }
                            }
                        }
                    }
                }
            }

            // 2. Bottom Floating Capsule Tab Switcher
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                BottomPillTabBar(
                    currentPage = pagerState.currentPage,
                    onTabClick = { index ->
                        scope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    },
                )
            }
        }
    }

    @Composable
    private fun BottomPillTabBar(
        currentPage: Int,
        onTabClick: (Int) -> Unit,
        tabs: List<Triple<String, Int, Int>> = listOf(
            Triple("Read Mode", R.drawable.ic_book_page, 0),
            Triple("Tools", R.drawable.ic_grid, 1),
        ),
        modifier: Modifier = Modifier,
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.95f),
                shadowElevation = 6.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                modifier = Modifier
                    .widthIn(max = if (tabs.size > 2) 336.dp else 280.dp)
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    val targetBias = if (tabs.size == 1) 0f else currentPage * 2f / (tabs.size - 1) - 1f
                    val animatedBias by animateFloatAsState(
                        targetValue = targetBias,
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                        label = "pill_bias",
                    )
                    
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(1f / tabs.size)
                            .padding(4.dp)
                            .align(BiasAlignment(horizontalBias = animatedBias, verticalBias = 0f))
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )

                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tabs.forEach { (title, iconRes, index) ->
                            val isSelected = currentPage == index
                            val contentColor by animateColorAsState(
                                targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                label = "pill_tab_content_color",
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null,
                                    ) { onTabClick(index) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(iconRes),
                                        contentDescription = title,
                                        tint = contentColor,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = contentColor,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // EPUB settings use a compact text page and a larger combined reading/style page.
    @Composable
    private fun EpubConfigContent() {
        val customFontName = remember(customFontUiRevision) { settings.epubCustomFontName }
        var publisherStyleEnabled by remember { mutableStateOf(settings.isEpubPublisherStyleEnabled) }
        var bionicReadingEnabled by remember { mutableStateOf(settings.isEpubBionicReadingEnabled) }
        var readingMode by remember {
            mutableStateOf(if (settings.epubReadingMode == "paged") "paged_ltr" else settings.epubReadingMode)
        }
        val pagerState = rememberPagerState(pageCount = { 3 })
        val scope = rememberCoroutineScope()
        val callback = remember { findParentCallback(Callback::class.java) }
        // Book formatting temporarily owns typography; reading behavior and page colors remain editable.
        val editable = !publisherStyleEnabled
        val navBarPadding = remember {
            dialog?.window?.decorView?.let { decor ->
                ViewCompat.getRootWindowInsets(decor)
                    ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom
            } ?: 0
        }
        val density = LocalDensity.current
        val pageContent: @Composable (Int) -> Unit = { page ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 68.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (page == 0) {
                    EpubTextSizeSection(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        enabled = editable,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        EpubSliderSection(
                            modifier = Modifier.weight(1f),
                            icon = R.drawable.ic_reader_vertical,
                            title = stringResource(R.string.epub_paragraph_spacing),
                            value = settings.epubParagraphSpacing,
                            range = 0..48,
                            suffix = " dp",
                            defaultValue = 0,
                            enabled = editable,
                        ) { settings.epubParagraphSpacing = it }
                        EpubSliderSection(Modifier.weight(1f), R.drawable.ic_reader_vertical, stringResource(R.string.epub_line_height), settings.epubLineHeight, 100..240, "%", defaultValue = 160, enabled = editable) { settings.epubLineHeight = it }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        EpubSliderSection(Modifier.weight(1f), R.drawable.ic_move_horizontal, stringResource(R.string.epub_horizontal_margin), settings.epubHorizontalPadding, 0..64, " dp", defaultValue = 20, enabled = editable) { settings.epubHorizontalPadding = it }
                        EpubSliderSection(Modifier.weight(1f), R.drawable.ic_gesture_vertical, stringResource(R.string.epub_vertical_margin), settings.epubVerticalPadding, 0..112, " dp", defaultValue = 112, enabled = editable && readingMode != "scroll") { settings.epubVerticalPadding = it }
                    }
                } else if (page == 1) {
                    EpubReadModeSection(readingMode) { mode ->
                        readingMode = mode
                        settings.epubReadingMode = mode
                        when {
                            mode == "paged_rtl" && settings.epubTextAlign == "left" -> settings.epubTextAlign = "right"
                            mode != "paged_rtl" && settings.epubTextAlign == "right" -> settings.epubTextAlign = "left"
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(IntrinsicSize.Max),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        EpubChoiceSection(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            icon = R.drawable.ic_reader_ltr,
                            title = stringResource(R.string.epub_text_alignment),
                            choices = listOf(
                                if (readingMode == "paged_rtl") "right" to stringResource(R.string.epub_align_right)
                                else "left" to stringResource(R.string.epub_align_left),
                                "justify" to stringResource(R.string.epub_align_justified),
                            ),
                            selected = when {
                                readingMode == "paged_rtl" && settings.epubTextAlign == "left" -> "right"
                                readingMode != "paged_rtl" && settings.epubTextAlign == "right" -> "left"
                                else -> settings.epubTextAlign
                            },
                            enabled = editable,
                        ) { settings.epubTextAlign = it }
                        EpubTapGestureSection(Modifier.weight(1f).fillMaxHeight(), enabled = readingMode != "scroll")
                    }
                    EpubFontSection(
                        selected = settings.epubFontFamily,
                        customFontName = customFontName,
                        enabled = editable,
                        onChooseCustom = {
                            epubFontPicker.launch(EPUB_FONT_MIME_TYPES)
                        },
                        onRemoveCustom = ::removeEpubCustomFont,
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ToolGridCard(
                            icon = R.drawable.ic_auto_fix,
                            label = stringResource(R.string.epub_publisher_style),
                            checked = publisherStyleEnabled,
                            onClick = {
                                publisherStyleEnabled = !publisherStyleEnabled
                                settings.isEpubPublisherStyleEnabled = publisherStyleEnabled
                            },
                            modifier = Modifier.weight(1f).height(120.dp),
                            iconSize = 24.dp,
                        )
                        ToolGridCard(
                            icon = R.drawable.ic_bolt,
                            label = stringResource(R.string.epub_bionic_reading),
                            checked = bionicReadingEnabled,
                            onClick = {
                                bionicReadingEnabled = !bionicReadingEnabled
                                settings.isEpubBionicReadingEnabled = bionicReadingEnabled
                            },
                            modifier = Modifier.weight(1f).height(120.dp),
                            iconSize = 24.dp,
                        )
                        EpubThemeCard(modifier = Modifier.weight(1f).height(120.dp))
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ToolGridCard(
                            icon = R.drawable.ic_timer,
                            label = stringResource(R.string.automatic_scroll),
                            onClick = {
                                dismiss()
                                callback?.onScrollTimerClick(isLongClick = false)
                            },
                            modifier = Modifier.weight(1f).height(120.dp),
                            iconSize = 24.dp,
                        )
                        ToolGridCard(
                            icon = R.drawable.ic_voice_over,
                            label = stringResource(R.string.text_to_speech),
                            onClick = {
                                dismiss()
                                callback?.onTextToSpeechClick()
                            },
                            modifier = Modifier.weight(1f).height(120.dp),
                            iconSize = 24.dp,
                        )
                    }
                }
                if (publisherStyleEnabled) {
                    Text(
                        text = stringResource(R.string.epub_publisher_style_disabled_note),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp + with(density) { navBarPadding.toDp() }),
        ) {
            var measuredHeights by remember { mutableStateOf(emptyList<Float>()) }
            SubcomposeLayout(modifier = Modifier.fillMaxWidth()) { constraints ->
                val measureConstraints = Constraints(minWidth = constraints.maxWidth, maxWidth = constraints.maxWidth)
                val heights = List(3) { page ->
                    subcompose("epub_page_height_$page") { pageContent(page) }
                        .maxOf { it.measure(measureConstraints).height }
                        .toFloat()
                }
                if (measuredHeights != heights) measuredHeights = heights
                layout(0, 0) {}
            }
            val targetHeight = measuredHeights.getOrNull(pagerState.currentPage) ?: 0f
            val animatedHeight = remember { Animatable(0f) }
            var heightInitialized by remember { mutableStateOf(false) }
            LaunchedEffect(targetHeight) {
                if (targetHeight <= 0f) return@LaunchedEffect
                if (heightInitialized) {
                    animatedHeight.animateTo(
                        targetHeight,
                        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                    )
                } else {
                    animatedHeight.snapTo(targetHeight)
                    heightInitialized = true
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().height(with(density) { animatedHeight.value.toDp() }),
                verticalAlignment = Alignment.Top,
            ) { page ->
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    pageContent(page)
                }
            }
            BottomPillTabBar(
                currentPage = pagerState.currentPage,
                onTabClick = { scope.launch { pagerState.animateScrollToPage(it) } },
                tabs = listOf(
                    Triple(stringResource(R.string.epub_text_tab), R.drawable.ic_appearance, 0),
                    Triple(stringResource(R.string.epub_reading_tab), R.drawable.ic_book_page, 1),
                    Triple(stringResource(R.string.epub_tools_tab), R.drawable.ic_tune, 2),
                ),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    @Composable
    private fun EpubSliderSection(
        modifier: Modifier = Modifier,
        icon: Int,
        title: String,
        value: Int,
        range: IntRange,
        suffix: String,
        defaultValue: Int,
        enabled: Boolean = true,
        onChange: (Int) -> Unit,
    ) {
        var current by remember { mutableIntStateOf(value.coerceIn(range)) }
        EpubSettingCard(
            icon, title, "$current$suffix", modifier = modifier, compact = true, enabled = enabled,
            resetEnabled = current != defaultValue,
            onReset = { current = defaultValue.also(onChange) },
        ) {
            EpubContinuousSlider(
                value = current.toFloat(),
                onValueChange = { value ->
                    val rounded = value.roundToInt()
                    if (rounded != current) current = rounded.also(onChange)
                },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                enabled = enabled,
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun EpubContinuousSlider(
        value: Float,
        onValueChange: (Float) -> Unit,
        valueRange: ClosedFloatingPointRange<Float>,
        enabled: Boolean,
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = 0,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun EpubChoiceSection(
        modifier: Modifier,
        icon: Int,
        title: String,
        choices: List<Pair<String, String>>,
        selected: String,
        enabled: Boolean = true,
        onSelected: (String) -> Unit,
    ) {
        var current by remember { mutableStateOf(selected) }
        LaunchedEffect(selected) { current = selected }
        EpubSettingCard(icon, title, null, modifier = modifier, compact = true, enabled = enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { (value, label) ->
                    val selected = current == value
                    val color by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        label = "epub_choice_color",
                    )
                    Surface(
                        onClick = { current = value; onSelected(value) },
                        enabled = enabled,
                        shape = CircleShape,
                        color = color,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Box(contentAlignment = Alignment.Center) { Text(label, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp)) } }
                }
            }
        }
    }

    @Composable
    private fun EpubTapGestureSection(modifier: Modifier, enabled: Boolean) {
        var checked by remember { mutableStateOf(settings.isEpubPagedTapGesturesEnabled) }
        EpubSettingCard(
            icon = R.drawable.ic_tap,
            title = stringResource(R.string.epub_paged_tap_gestures),
            value = null,
            modifier = modifier,
            compact = true,
            enabled = enabled,
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.epub_paged_tap_gestures_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Switch(
                    checked = checked,
                    onCheckedChange = {
                        checked = it
                        settings.isEpubPagedTapGesturesEnabled = it
                    },
                    enabled = enabled,
                )
            }
        }
    }

    @Composable
    private fun EpubFontSection(
        selected: String,
        customFontName: String,
        enabled: Boolean,
        onChooseCustom: () -> Unit,
        onRemoveCustom: () -> Unit,
    ) {
        var current by remember(selected, customFontName) { mutableStateOf(selected) }
        val choices = listOf(
            "serif" to stringResource(R.string.epub_font_serif),
            "sans-serif" to stringResource(R.string.epub_font_sans),
            "monospace" to stringResource(R.string.epub_font_mono),
            EPUB_FONT_CUSTOM to customFontName.substringBeforeLast('.').ifBlank { stringResource(R.string.epub_font_choose) },
        )
        EpubSettingCard(R.drawable.ic_title, stringResource(R.string.epub_font_family), null, enabled = enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (value, label) ->
                            val isSelected = current == value
                            Surface(
                                onClick = {
                                    if (value == EPUB_FONT_CUSTOM && customFontName.isBlank()) {
                                        onChooseCustom()
                                    } else {
                                        current = value
                                        settings.epubFontFamily = value
                                    }
                                },
                                enabled = enabled,
                                shape = RoundedCornerShape(18.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                ),
                                modifier = Modifier.weight(1f),
                            ) {
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = label,
                                        textAlign = TextAlign.Center,
                                        fontFamily = when (value) {
                                            "sans-serif" -> androidx.compose.ui.text.font.FontFamily.SansSerif
                                            "monospace" -> androidx.compose.ui.text.font.FontFamily.Monospace
                                            EPUB_FONT_CUSTOM -> null
                                            else -> androidx.compose.ui.text.font.FontFamily.Serif
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(
                                            horizontal = if (value == EPUB_FONT_CUSTOM && customFontName.isNotBlank()) 40.dp else 8.dp,
                                            vertical = 12.dp,
                                        ),
                                    )
                                    if (value == EPUB_FONT_CUSTOM && customFontName.isNotBlank()) {
                                        IconButton(
                                            onClick = onRemoveCustom,
                                            enabled = enabled,
                                            modifier = Modifier.align(Alignment.CenterEnd).size(40.dp),
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_close),
                                                contentDescription = stringResource(R.string.remove),
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // read-mode picker styled like the manga reader's segmented control
    @Composable
    private fun EpubReadModeSection(
        current: String,
        enabled: Boolean = true,
        onSelected: (String) -> Unit,
    ) {
        val modes = listOf(
            "scroll" to (R.string.epub_mode_scroll to R.drawable.ic_reader_vertical),
            "paged_ltr" to (R.string.epub_mode_paged_ltr to R.drawable.ic_reader_ltr),
            "paged_rtl" to (R.string.epub_mode_paged_rtl to R.drawable.ic_reader_rtl),
        )

        val selectedIndex = modes.indexOfFirst { it.first == current }.coerceAtLeast(0)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .alpha(if (enabled) 1f else 0.38f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_book_page),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.epub_read_mode),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(6.dp),
                ) {
                    val animatedBias by animateFloatAsState(
                        targetValue = selectedIndex - 1f,
                        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                        label = "epub_mode_highlighter",
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(1f / modes.size)
                            .align(BiasAlignment(horizontalBias = animatedBias, verticalBias = 0f))
                            .clip(RoundedCornerShape(22.dp))
                            .background(MaterialTheme.colorScheme.primary),
                    )

                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        modes.forEach { (value, pair) ->
                            val (labelRes, iconRes) = pair
                            val isSelected = current == value
                            val contentColor by animateColorAsState(
                                targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                label = "epub_segment_fg_$value",
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable(
                                        enabled = enabled,
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null,
                                    ) { onSelected(value) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        painter = painterResource(iconRes),
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                        tint = contentColor,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(labelRes),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = contentColor,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EpubThemeCard(modifier: Modifier = Modifier) {
        var showDialog by remember { mutableStateOf(false) }
        ToolGridCard(
            icon = R.drawable.ic_appearance,
            label = stringResource(R.string.theme),
            checked = settings.epubTheme == EPUB_THEME_CUSTOM,
            onClick = { showDialog = true },
            modifier = modifier,
            iconSize = 22.dp,
        )
        if (showDialog) EpubThemeSheet { showDialog = false }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun EpubThemeSheet(onDismiss: () -> Unit) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        val dismissAnimated: () -> Unit = {
            scope.launch {
                sheetState.hide()
                onDismiss()
            }
        }
        var theme by remember { mutableStateOf(canonicalEpubTheme(settings.epubTheme)) }
        var background by remember { mutableIntStateOf(settings.epubCustomBackgroundColor) }
        var foreground by remember { mutableIntStateOf(settings.epubCustomTextColor) }
        var highlighter by remember { mutableIntStateOf(settings.epubCustomHighlightColor) }
        var colorTarget by remember { mutableStateOf(EPUB_COLOR_BACKGROUND) }
        val activeColor = when (colorTarget) {
            EPUB_COLOR_TEXT -> foreground
            EPUB_COLOR_HIGHLIGHT -> highlighter
            else -> background
        }
        val hsv = remember(activeColor) {
            FloatArray(3).also { android.graphics.Color.colorToHSV(activeColor, it) }
        }
        var hexColor by remember(colorTarget) { mutableStateOf(activeColor.toHexColor()) }
        LaunchedEffect(activeColor) {
            val current = activeColor.toHexColor()
            if (!hexColor.equals(current, ignoreCase = true)) hexColor = current
        }
        val updateActiveColor: (Int) -> Unit = { color ->
            when (colorTarget) {
                EPUB_COLOR_TEXT -> {
                    foreground = color
                    settings.epubCustomTextColor = color
                }
                EPUB_COLOR_HIGHLIGHT -> {
                    highlighter = color
                    settings.epubCustomHighlightColor = color
                }
                else -> {
                    background = color
                    settings.epubCustomBackgroundColor = color
                }
            }
        }
        val customSelected = theme == EPUB_THEME_CUSTOM
        ModalBottomSheet(
            onDismissRequest = dismissAnimated,
            sheetState = sheetState,
            dragHandle = null,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 20.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.theme),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalIconButton(
                            onClick = dismissAnimated,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.close),
                            )
                        }
                    }
                    listOf(
                        EPUB_THEME_SYSTEM to R.string.epub_theme_system,
                        "white" to R.string.epub_theme_light,
                        "gray" to R.string.epub_theme_dark,
                        "black" to R.string.epub_theme_black,
                    ).chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (value, label) ->
                                val selected = theme == value
                                Surface(
                                    onClick = {
                                        theme = value
                                        settings.epubTheme = value
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    ),
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = stringResource(label),
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            style = MaterialTheme.typography.labelMedium,
                                            modifier = Modifier.padding(horizontal = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Surface(
                        onClick = {
                            theme = EPUB_THEME_CUSTOM
                            settings.epubTheme = EPUB_THEME_CUSTOM
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = androidx.compose.foundation.BorderStroke(
                            if (customSelected) 2.dp else 1.dp,
                            if (customSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp).alpha(if (customSelected) 1f else 0.38f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.epub_theme_custom),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    EPUB_COLOR_BACKGROUND to R.string.epub_theme_background,
                                    EPUB_COLOR_TEXT to R.string.epub_theme_text,
                                    EPUB_COLOR_HIGHLIGHT to R.string.epub_theme_highlighter,
                                ).forEach { (value, label) ->
                                    val selected = colorTarget == value
                                    Surface(
                                        onClick = { colorTarget = value },
                                        enabled = customSelected,
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color.Transparent,
                                        border = androidx.compose.foundation.BorderStroke(
                                            if (selected) 2.dp else 1.dp,
                                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        ),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(
                                            text = stringResource(label),
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                                        )
                                    }
                                }
                            }
                            EPUB_COLOR_PRESETS.chunked(8).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    row.forEach { color ->
                                        Surface(
                                            onClick = { updateActiveColor(color) },
                                            enabled = customSelected,
                                            shape = CircleShape,
                                            color = Color.Transparent,
                                            modifier = Modifier.weight(1f).aspectRatio(1f),
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Surface(
                                                    shape = CircleShape,
                                                    color = Color(color),
                                                    border = androidx.compose.foundation.BorderStroke(
                                                        if (activeColor == color) 3.dp else 1.dp,
                                                        if (activeColor == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                                    ),
                                                    modifier = Modifier.size(28.dp),
                                                ) {}
                                            }
                                        }
                                    }
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedTextField(
                                    value = hexColor,
                                    onValueChange = { input ->
                                        val digits = input.filter {
                                            it.isDigit() || it.lowercaseChar() in 'a'..'f'
                                        }.take(6).uppercase()
                                        hexColor = "#$digits"
                                        if (digits.length == 6) {
                                            updateActiveColor(0xFF000000.toInt() or digits.toInt(16))
                                        }
                                    },
                                    enabled = customSelected,
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.epub_color_hex)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                                    modifier = Modifier.weight(1f),
                                )
                                EpubSaturationSlider(
                                    value = hsv[1],
                                    enabled = customSelected,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    updateActiveColor(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], it, hsv[2])))
                                }
                            }
                            Text(stringResource(R.string.epub_theme_preview), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = Color(background),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                val preview = stringResource(R.string.epub_theme_preview_text)
                                val highlightedWord = stringResource(R.string.epub_theme_preview_highlighted)
                                val highlightStart = preview.indexOf(highlightedWord).coerceAtLeast(0)
                                Text(
                                    text = buildAnnotatedString {
                                        append(preview)
                                        addStyle(
                                            SpanStyle(background = Color(highlighter).copy(alpha = 0.4f)),
                                            highlightStart,
                                            (highlightStart + highlightedWord.length).coerceAtMost(preview.length),
                                        )
                                    },
                                    color = Color(foreground),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(18.dp),
                                )
                            }
                        }
                    }
            }
        }
    }

    @Composable
    private fun EpubSaturationSlider(
        value: Float,
        enabled: Boolean,
        modifier: Modifier = Modifier,
        onChange: (Float) -> Unit,
    ) {
        Column(modifier) {
            Text(stringResource(R.string.epub_color_saturation), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = value, onValueChange = onChange, enabled = enabled, valueRange = 0f..1f)
        }
    }

    private fun Int.toHexColor() = "#%06X".format(this and 0xFFFFFF)

    private fun canonicalEpubTheme(value: String): String = when (value) {
        EPUB_THEME_SYSTEM -> EPUB_THEME_SYSTEM
        "light", "white" -> "white"
        "dark", "gray" -> "gray"
        "black" -> "black"
        EPUB_THEME_CUSTOM -> EPUB_THEME_CUSTOM
        else -> EPUB_THEME_SYSTEM
    }

    @Composable
    private fun EpubSettingCard(
        icon: Int,
        title: String,
        value: String?,
        modifier: Modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        compact: Boolean = false,
        enabled: Boolean = true,
        resetEnabled: Boolean = true,
        onReset: (() -> Unit)? = null,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
            shadowElevation = 1.dp,
            modifier = modifier.alpha(if (enabled) 1f else 0.38f),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 20.dp, vertical = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(if (compact) 18.dp else 22.dp))
                        Spacer(Modifier.width(if (compact) 8.dp else 14.dp))
                        Text(
                            title,
                            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = if (compact) 2 else Int.MAX_VALUE,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (compact) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (onReset != null) {
                                IconButton(onClick = onReset, enabled = enabled && resetEnabled, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        painterResource(R.drawable.ic_revert),
                                        null,
                                        tint = if (resetEnabled) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            if (value != null) Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (onReset != null) {
                                IconButton(onClick = onReset, enabled = enabled && resetEnabled, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        painterResource(R.drawable.ic_revert),
                                        null,
                                        tint = if (resetEnabled) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Spacer(Modifier.width(4.dp))
                            }
                            if (value != null) Text(value, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }

    @Composable
    private fun EpubTextSizeSection(modifier: Modifier = Modifier, enabled: Boolean = true) {
        var textSize by remember { mutableIntStateOf(settings.epubFontSize.coerceIn(50, 200)) }
        EpubSettingCard(
            R.drawable.ic_size_large, stringResource(R.string.epub_text_size), "$textSize%",
            modifier = modifier, compact = true, enabled = enabled,
            resetEnabled = textSize != 100,
            onReset = { textSize = 100.also { settings.epubFontSize = it } },
        ) {
            EpubContinuousSlider(
                value = textSize.toFloat(),
                onValueChange = { value ->
                    val rounded = value.roundToInt()
                    if (rounded != textSize) {
                        textSize = rounded
                        settings.epubFontSize = rounded
                    }
                },
                valueRange = 50f..200f,
                enabled = enabled,
            )
        }
    }

    @Composable
    private fun ToolsGridSection(
        showPageTools: Boolean,
        isAutoRotationEnabled: Boolean,
        isBookmarkAdded: Boolean,
        onSaveClick: () -> Unit,
        onOrientationClick: () -> Unit,
        onScrollTimerClick: () -> Unit,
        onColorFilterClick: () -> Unit,
        onBookmarkClick: () -> Unit,
        onSettingsClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        // Determine rotation values
        val rotationTitle = if (isAutoRotationEnabled) {
            R.string.lock_screen_rotation
        } else {
            R.string.rotate_screen
        }
        val rotationIcon = if (isAutoRotationEnabled) {
            R.drawable.ic_screen_rotation_lock
        } else {
            R.drawable.ic_screen_rotation
        }
        // Rows share the page height equally so this page matches the Read Mode page exactly.
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Row 1: Save Page, Rotation (Pills) - not applicable to EPUB text chapters
            if (showPageTools) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ToolPillCard(
                        icon = R.drawable.ic_save,
                        label = stringResource(R.string.save_page),
                        onClick = onSaveClick,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    ToolPillCard(
                        icon = rotationIcon,
                        label = stringResource(rotationTitle),
                        onClick = onOrientationClick,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }

            // Row 2: Scroll Timer, Color correction (Squares)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ToolGridCard(
                    icon = R.drawable.ic_timer,
                    label = stringResource(R.string.automatic_scroll),
                    onClick = onScrollTimerClick,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                ToolGridCard(
                    icon = R.drawable.ic_appearance,
                    label = stringResource(R.string.color_correction),
                    onClick = onColorFilterClick,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }

            // Row 3: Settings Card (Wide) | Bookmark Button (Round)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ToolWideCard(
                    icon = R.drawable.ic_settings,
                    title = stringResource(R.string.settings),
                    subtitle = "Advanced reader options",
                    onClick = onSettingsClick,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                ToolGridCard(
                    icon = if (isBookmarkAdded) R.drawable.ic_bookmark_checked else R.drawable.ic_bookmark,
                    label = null,
                    checked = isBookmarkAdded,
                    onClick = onBookmarkClick,
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f),
                    shape = CircleShape,
                    iconSize = 42.dp,
                )
            }
        }
    }

    @Composable
    private fun ToolWideCard(
        icon: Int,
        title: String,
        subtitle: String,
        checked: Boolean = false,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val containerColor = if (checked) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }

        val contentColor = if (checked) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }

        val iconColor = if (checked) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.primary
        }

        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(24.dp),
            color = containerColor,
            contentColor = contentColor,
            modifier = modifier.heightIn(min = 96.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = iconColor,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (checked) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(-90f),
                    tint = if (checked) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun ToolGridCard(
        icon: Int,
        label: String?,
        checked: Boolean = false,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp),
        iconSize: androidx.compose.ui.unit.Dp = 32.dp,
    ) {
        val containerColor = if (checked) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }

        val contentColor = if (checked) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }

        val iconColor = if (checked) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.primary
        }

        Surface(
            onClick = onClick,
            shape = shape,
            color = containerColor,
            contentColor = contentColor,
            modifier = modifier.heightIn(min = 96.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = label,
                    modifier = Modifier.size(iconSize),
                    tint = iconColor,
                )
                if (label != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    @Composable
    private fun ToolPillCard(
        icon: Int,
        label: String,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(48.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = modifier.heightIn(min = 96.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = label,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

	private companion object {
		const val EPUB_FONT_CUSTOM = "custom"
		const val EPUB_THEME_CUSTOM = "custom"
		const val EPUB_THEME_SYSTEM = "system"
		const val EPUB_COLOR_BACKGROUND = "background"
		const val EPUB_COLOR_TEXT = "text"
		const val EPUB_COLOR_HIGHLIGHT = "highlight"
		val EPUB_COLOR_PRESETS = listOf(
			0xFFFFFFFF.toInt(), 0xFFE0E0E0.toInt(), 0xFF9E9E9E.toInt(), 0xFF424242.toInt(), 0xFF121212.toInt(), 0xFF6D4C41.toInt(),
			0xFFE53935.toInt(), 0xFFF4511E.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFFC0CA33.toInt(), 0xFF43A047.toInt(),
			0xFF00A86B.toInt(), 0xFF00897B.toInt(), 0xFF00ACC1.toInt(), 0xFF039BE5.toInt(), 0xFF1E88E5.toInt(), 0xFF3949AB.toInt(),
			0xFF5E35B1.toInt(), 0xFF8E24AA.toInt(), 0xFFD81B60.toInt(), 0xFFE91E63.toInt(), 0xFFFF7043.toInt(), 0xFFC99A00.toInt(),
		)
		val EPUB_FONT_MIME_TYPES = arrayOf(
			"font/ttf",
			"font/otf",
			"application/x-font-ttf",
			"application/x-font-opentype",
			"application/octet-stream",
		)
	}

    interface Callback {

        fun onReaderModeChanged(mode: ReaderMode)

        fun onDoubleModeChanged(isEnabled: Boolean)

        fun onSavePageClick()

        fun onScrollTimerClick(isLongClick: Boolean)

        fun onTextToSpeechClick()

        fun onBookmarkClick()

    }
}
