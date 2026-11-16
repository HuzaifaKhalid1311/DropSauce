package org.koitharu.kotatsu.reader.ui.config

import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.ui.sheet.AdaptiveSheetBehavior
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.ui.sheet.SheetAction
import org.koitharu.kotatsu.core.ui.sheet.SheetActionGrid
import org.koitharu.kotatsu.core.ui.sheet.SheetContentPadding
import org.koitharu.kotatsu.core.ui.sheet.SheetRows
import org.koitharu.kotatsu.core.ui.util.syncedSliderState
import org.koitharu.kotatsu.core.util.ext.findParentCallback
import org.koitharu.kotatsu.databinding.SheetReaderConfigBinding
import org.koitharu.kotatsu.local.data.isEpub
import org.koitharu.kotatsu.reader.ui.ReaderViewModel
import org.koitharu.kotatsu.reader.ui.ScreenOrientationHelper
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.LocalSettingsItemColor
import org.koitharu.kotatsu.settings.compose.NavigationSettingsItem
import org.koitharu.kotatsu.settings.compose.PreviewChoiceRow
import org.koitharu.kotatsu.settings.compose.PreviewTile
import org.koitharu.kotatsu.settings.compose.SegmentedRow
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.readerModePreviewOptions
import java.io.File
import javax.inject.Inject
import kotlin.math.roundToInt

/** Base body text size the EPUB reader scales its percentages against (see EpubReaderFragment). */
private const val EPUB_BASE_TEXT_SIZE_DP = 16f

@AndroidEntryPoint
class ReaderConfigSheet : BaseAdaptiveSheet<SheetReaderConfigBinding>() {

    private val viewModel by activityViewModels<ReaderViewModel>()

    @Inject
    lateinit var orientationHelper: ScreenOrientationHelper

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
		val isEpubBook = remember { viewModel.getMangaOrNull()?.isEpub == true }
		// Capture the nav bar inset once: the reader toggles system bars while the sheet is open,
		// and a live navigationBarsPadding() resize makes the shown sheet jump.
		val navBarPadding = remember {
			dialog?.window?.decorView?.let { decor ->
				ViewCompat.getRootWindowInsets(decor)
					?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom
			} ?: 0
		}
		val bottomPadding = 16.dp + with(LocalDensity.current) { navBarPadding.toDp() }
		SheetRows {
			if (isEpubBook) EpubConfigContent(bottomPadding) else MangaConfigContent(bottomPadding)
		}
	}

	// One page, most used first: how to read, then what to do with the page. Built from the settings
	// screens' own rows, so the sheet and Settings > Reader always look alike.
	@Composable
	private fun MangaConfigContent(bottomPadding: Dp) {
		var currentMode by remember { mutableStateOf(mode) }
		val isBookmarkAdded by viewModel.isBookmarkAdded.collectAsState()
		val isAutoRotationEnabled by orientationHelper.observeAutoOrientation().collectAsState(initial = false)
		val callback = remember { findParentCallback(Callback::class.java) }
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.verticalScroll(rememberScrollState())
				.padding(horizontal = SheetContentPadding)
				.padding(top = 4.dp, bottom = bottomPadding),
		) {
			// The tiles sit straight on the sheet: a card behind them only added a box around boxes.
			SettingsGroup(title = stringResource(R.string.read_mode)) {
				item {
					PreviewChoiceRow(
						options = readerModePreviewOptions(READER_MODES.map { stringResource(it.second) }),
						selectedIndex = READER_MODES.indexOfFirst { it.first == currentMode }.coerceAtLeast(0),
						onSelected = { index ->
							val newMode = READER_MODES[index].first
							if (newMode != currentMode) {
								callback?.onReaderModeChanged(newMode)
								currentMode = newMode
							}
						},
					)
				}
			}
			Text(
				text = stringResource(R.string.reader_mode_hint),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 14.dp),
			)
			SheetActionGrid(
				actions = listOf(
					SheetAction(
						icon = if (isBookmarkAdded) R.drawable.ic_bookmark_checked else R.drawable.ic_bookmark,
						label = stringResource(if (isBookmarkAdded) R.string.bookmark_remove else R.string.bookmark_add),
						checked = isBookmarkAdded,
					) { viewModel.toggleBookmark() },
					SheetAction(R.drawable.ic_timer, stringResource(R.string.automatic_scroll)) {
						callback?.onScrollTimerClick(isLongClick = false)
						dismissAllowingStateLoss()
					},
					SheetAction(R.drawable.ic_save, stringResource(R.string.save_page)) {
						callback?.onSavePageClick()
						dismissAllowingStateLoss()
					},
					SheetAction(R.drawable.ic_share, stringResource(R.string.share_page)) {
						callback?.onSharePageClick()
						dismissAllowingStateLoss()
					},
					SheetAction(
						icon = if (isAutoRotationEnabled) R.drawable.ic_screen_rotation_lock else R.drawable.ic_screen_rotation,
						label = stringResource(if (isAutoRotationEnabled) R.string.lock_screen_rotation else R.string.rotate_screen),
					) { orientationHelper.toggleScreenOrientation() },
					SheetAction(R.drawable.ic_appearance, stringResource(R.string.color_correction)) {
						val page = viewModel.getCurrentPage()
						val manga = viewModel.getMangaOrNull()
						if (page != null && manga != null) {
							router.openColorFilterConfig(manga, page)
						}
					},
				),
				attachedBelow = true,
			)
			Spacer(Modifier.height(2.dp))
			NavigationSettingsItem(
				title = stringResource(R.string.settings),
				subtitle = stringResource(R.string.reader_settings_summary),
				onClick = {
					router.openReaderSettings()
					dismissAllowingStateLoss()
				},
				icon = R.drawable.ic_settings,
				shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 24.dp, bottomEnd = 24.dp),
			)
		}
	}

	// Three pages, by how often they are reached for: the text itself, how the page behaves, the rest.
	@Composable
	private fun EpubConfigContent(bottomPadding: Dp) {
		val customFontName = remember(customFontUiRevision) { settings.epubCustomFontName }
		var publisherStyleEnabled by remember { mutableStateOf(settings.isEpubPublisherStyleEnabled) }
		var bionicReadingEnabled by remember { mutableStateOf(settings.isEpubBionicReadingEnabled) }
		var tapGesturesEnabled by remember { mutableStateOf(settings.isEpubPagedTapGesturesEnabled) }
		var chapterDividerEnabled by remember { mutableStateOf(settings.isEpubChapterDividerEnabled) }
		var readingMode by remember { mutableStateOf(settings.epubReadingMode) }
		var isRtl by remember { mutableStateOf(settings.isEpubRtl) }
		var textAlign by remember { mutableStateOf(settings.epubTextAlign) }
		var fontSize by remember { mutableIntStateOf(settings.epubFontSize.coerceIn(50, 200)) }
		var lineHeight by remember { mutableIntStateOf(settings.epubLineHeight.coerceIn(100, 240)) }
		var paragraphSpacing by remember { mutableIntStateOf(settings.epubParagraphSpacing.coerceIn(-32, 48)) }
		var horizontalMargin by remember { mutableIntStateOf(settings.epubHorizontalPadding.coerceIn(0, 64)) }
		var verticalMargin by remember { mutableIntStateOf(settings.epubVerticalPadding.coerceIn(0, 112)) }
		var isThemeSheetShown by remember { mutableStateOf(false) }
		val pagerState = rememberPagerState(pageCount = { 3 })
		val scope = rememberCoroutineScope()
		val callback = remember { findParentCallback(Callback::class.java) }
		// Book formatting temporarily owns typography; reading behavior and page colors remain editable.
		val editable = !publisherStyleEnabled
		val density = LocalDensity.current
		val dpLabel: (Int) -> String = { "$it dp" }
		val pageContent: @Composable (Int) -> Unit = { page ->
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = SheetContentPadding)
					.padding(top = 4.dp),
			) {
				when (page) {
					0 -> {
						EpubFontRow(customFontName = customFontName, enabled = editable)
						Spacer(Modifier.height(12.dp))
						SettingsGroup {
							item { pos ->
								EpubSliderRow(
									title = stringResource(R.string.epub_text_size),
									value = fontSize,
									range = 50..200,
									defaultValue = 100,
									shape = pos.shape,
									enabled = editable,
									// Shown as the resulting body text size in dp rather than the stored percentage.
									valueLabel = { "${(EPUB_BASE_TEXT_SIZE_DP * it / 100f).roundToInt()} dp" },
								) {
									fontSize = it
									settings.epubFontSize = it
								}
							}
							item { pos ->
								EpubSliderRow(
									title = stringResource(R.string.epub_line_height),
									value = lineHeight,
									range = 100..240,
									defaultValue = 160,
									shape = pos.shape,
									enabled = editable,
									// Shown as the resulting line box in dp, which depends on the text size too.
									valueLabel = { "${(EPUB_BASE_TEXT_SIZE_DP * fontSize * it / 10000f).roundToInt()} dp" },
								) {
									lineHeight = it
									settings.epubLineHeight = it
								}
							}
							item { pos ->
								EpubSliderRow(
									title = stringResource(R.string.epub_paragraph_spacing),
									value = paragraphSpacing,
									// Negative values eat into the blank line EPUBs put between paragraphs,
									// so the slider can go all the way down to plain line spacing.
									range = -32..48,
									defaultValue = 0,
									shape = pos.shape,
									enabled = editable,
									valueLabel = dpLabel,
								) {
									paragraphSpacing = it
									settings.epubParagraphSpacing = it
								}
							}
						}
					}

					1 -> {
						SettingsGroup {
							item { pos ->
								EpubChoiceRow(
									title = stringResource(R.string.epub_read_mode),
									choices = listOf(
										"scroll" to stringResource(R.string.epub_mode_scroll),
										"paged" to stringResource(R.string.epub_mode_paged),
									),
									selected = readingMode,
									shape = pos.shape,
								) { value ->
									readingMode = value
									settings.epubReadingMode = value
								}
							}
							// Direction is its own axis, so scroll mode can be RTL too.
							item { pos ->
								EpubChoiceRow(
									title = stringResource(R.string.epub_reading_direction),
									choices = listOf(
										"ltr" to stringResource(R.string.epub_mode_paged_ltr),
										"rtl" to stringResource(R.string.epub_mode_paged_rtl),
									),
									selected = if (isRtl) "rtl" else "ltr",
									shape = pos.shape,
								) { value ->
									val rtl = value == "rtl"
									isRtl = rtl
									settings.isEpubRtl = rtl
									// Keep the side-aligned choice on the reading side of the page.
									when {
										rtl && textAlign == "left" -> textAlign = "right"
										!rtl && textAlign == "right" -> textAlign = "left"
									}
									settings.epubTextAlign = textAlign
								}
							}
							item { pos ->
								EpubChoiceRow(
									title = stringResource(R.string.epub_text_alignment),
									choices = listOf(
										"justify" to stringResource(R.string.epub_align_justified),
										(if (isRtl) "right" else "left") to stringResource(R.string.epub_align_side),
									),
									selected = when {
										isRtl && textAlign == "left" -> "right"
										!isRtl && textAlign == "right" -> "left"
										else -> textAlign
									},
									shape = pos.shape,
									enabled = editable,
								) { value ->
									textAlign = value
									settings.epubTextAlign = value
								}
							}
						}
						Spacer(Modifier.height(12.dp))
						SettingsGroup {
							item { pos ->
								EpubSliderRow(
									title = stringResource(R.string.epub_horizontal_margin),
									value = horizontalMargin,
									range = 0..64,
									defaultValue = 20,
									shape = pos.shape,
									enabled = editable,
									valueLabel = dpLabel,
								) {
									horizontalMargin = it
									settings.epubHorizontalPadding = it
								}
							}
							item { pos ->
								EpubSliderRow(
									title = stringResource(R.string.epub_vertical_margin),
									value = verticalMargin,
									range = 0..112,
									defaultValue = 112,
									shape = pos.shape,
									enabled = editable && readingMode != "scroll",
									valueLabel = dpLabel,
								) {
									verticalMargin = it
									settings.epubVerticalPadding = it
								}
							}
						}
					}

					else -> {
						SheetActionGrid(
							actions = listOf(
								SheetAction(R.drawable.ic_timer, stringResource(R.string.automatic_scroll)) {
									dismiss()
									callback?.onScrollTimerClick(isLongClick = false)
								},
								SheetAction(R.drawable.ic_voice_over, stringResource(R.string.text_to_speech)) {
									dismiss()
									callback?.onTextToSpeechClick()
								},
								SheetAction(R.drawable.ic_appearance, stringResource(R.string.theme)) {
									isThemeSheetShown = true
								},
							),
						)
						Spacer(Modifier.height(12.dp))
						SettingsGroup {
							item { pos ->
								SwitchSettingsItem(
									title = stringResource(R.string.epub_publisher_style),
									checked = publisherStyleEnabled,
									onCheckedChange = {
										publisherStyleEnabled = it
										settings.isEpubPublisherStyleEnabled = it
									},
									icon = R.drawable.ic_auto_fix,
									shape = pos.shape,
								)
							}
							item { pos ->
								SwitchSettingsItem(
									title = stringResource(R.string.epub_bionic_reading),
									checked = bionicReadingEnabled,
									onCheckedChange = {
										bionicReadingEnabled = it
										settings.isEpubBionicReadingEnabled = it
									},
									icon = R.drawable.ic_bolt,
									shape = pos.shape,
								)
							}
							item { pos ->
								val isPaged = readingMode != "scroll"
								SwitchSettingsItem(
									title = stringResource(R.string.epub_paged_tap_gestures),
									// Greyed out, the row says why instead of what it does.
									subtitle = stringResource(
										if (isPaged) {
											R.string.epub_paged_tap_gestures_summary
										} else {
											R.string.epub_paged_tap_gestures_unavailable
										},
									),
									checked = tapGesturesEnabled,
									onCheckedChange = {
										tapGesturesEnabled = it
										settings.isEpubPagedTapGesturesEnabled = it
									},
									icon = R.drawable.ic_tap,
									shape = pos.shape,
									enabled = isPaged,
								)
							}
							item { pos ->
								val isScroll = readingMode == "scroll"
								SwitchSettingsItem(
									title = stringResource(R.string.epub_chapter_divider),
									subtitle = stringResource(
										if (isScroll) {
											R.string.epub_chapter_divider_summary
										} else {
											R.string.epub_chapter_divider_unavailable
										},
									),
									checked = chapterDividerEnabled,
									onCheckedChange = {
										chapterDividerEnabled = it
										settings.isEpubChapterDividerEnabled = it
									},
									icon = R.drawable.ic_horizontal_rule,
									shape = pos.shape,
									enabled = isScroll,
								)
							}
						}
					}
				}
				if (publisherStyleEnabled && page != 2) {
					Text(
						text = stringResource(R.string.epub_publisher_style_disabled_note),
						modifier = Modifier
							.fillMaxWidth()
							.padding(top = 10.dp),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						textAlign = TextAlign.Center,
					)
				}
			}
		}
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(bottom = bottomPadding),
		) {
			// Every page is measured up front, so the sheet can glide to the height of the one showing.
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
				modifier = Modifier
					.fillMaxWidth()
					// Gives way first on a short screen, so the tabs underneath always fit.
					.weight(1f, fill = false)
					.height(with(density) { animatedHeight.value.toDp() }),
				verticalAlignment = Alignment.Top,
			) { page ->
				Box(
					Modifier
						.fillMaxSize()
						.verticalScroll(rememberScrollState()),
				) {
					pageContent(page)
				}
			}
			// Under the pages, not above them: the sheet grows upwards, so down here the tabs stay put
			// under the thumb whichever page is showing.
			PillTabBar(
				currentPage = pagerState.currentPage,
				onTabClick = { scope.launch { pagerState.animateScrollToPage(it) } },
				tabs = listOf(
					stringResource(R.string.epub_text_tab) to R.drawable.ic_title,
					stringResource(R.string.epub_reading_tab) to R.drawable.ic_book_page,
					stringResource(R.string.epub_tools_tab) to R.drawable.ic_tune,
				),
				modifier = Modifier
					.align(Alignment.CenterHorizontally)
					.padding(horizontal = 24.dp)
					.padding(top = 16.dp),
			)
		}
		if (isThemeSheetShown) EpubThemeSheet { isThemeSheetShown = false }
	}

	/** Font picker: each tile shows the typeface itself. */
	@Composable
	private fun EpubFontRow(customFontName: String, enabled: Boolean) {
		var current by remember(customFontName) { mutableStateOf(settings.epubFontFamily) }
		val customFont = remember(customFontUiRevision) {
			if (customFontName.isBlank()) {
				null
			} else {
				runCatching {
					FontFamily(Typeface.createFromFile(File(requireContext().filesDir, AppSettings.EPUB_CUSTOM_FONT_FILE)))
				}.getOrNull()
			}
		}
		val fonts = listOf(
			Triple("serif", stringResource(R.string.epub_font_serif), FontFamily.Serif),
			Triple("sans-serif", stringResource(R.string.epub_font_sans), FontFamily.SansSerif),
			Triple("monospace", stringResource(R.string.epub_font_mono), FontFamily.Monospace),
			Triple(
				EPUB_FONT_CUSTOM,
				customFontName.substringBeforeLast('.').ifBlank { stringResource(R.string.epub_font_choose) },
				customFont,
			),
		)
		SettingsGroup(title = stringResource(R.string.epub_font_family)) {
			item {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					fonts.forEach { (value, label, family) ->
						val isCustom = value == EPUB_FONT_CUSTOM
						PreviewTile(
							label = label,
							selected = current == value,
							enabled = enabled,
							onClick = {
								if (isCustom && customFontName.isBlank()) {
									epubFontPicker.launch(EPUB_FONT_MIME_TYPES)
								} else {
									current = value
									settings.epubFontFamily = value
								}
							},
							modifier = Modifier
								.weight(1f, fill = false)
								.widthIn(max = 96.dp),
							aspectRatio = 1.25f,
						) {
							if (isCustom && customFontName.isBlank()) {
								Icon(
									painter = painterResource(R.drawable.ic_add),
									contentDescription = null,
									tint = MaterialTheme.colorScheme.onSurfaceVariant,
									modifier = Modifier.align(Alignment.Center),
								)
							} else {
								Text(
									text = "Aa",
									style = MaterialTheme.typography.titleLarge,
									fontFamily = family,
									color = MaterialTheme.colorScheme.onSurface,
									modifier = Modifier.align(Alignment.Center),
								)
							}
							if (isCustom && customFontName.isNotBlank()) {
								Box(
									modifier = Modifier
										.align(Alignment.TopEnd)
										.padding(5.dp)
										.size(22.dp)
										.clip(CircleShape)
										.background(MaterialTheme.colorScheme.surfaceContainerLow)
										.clickable(enabled = enabled, onClick = ::removeEpubCustomFont),
									contentAlignment = Alignment.Center,
								) {
									Icon(
										painter = painterResource(R.drawable.ic_close),
										contentDescription = stringResource(R.string.remove),
										tint = MaterialTheme.colorScheme.onSurfaceVariant,
										modifier = Modifier.size(14.dp),
									)
								}
							}
						}
					}
				}
			}
		}
	}

	/**
	 * A slider that applies as it moves, so the page behind follows the thumb. The value pill doubles
	 * as the reset button once the value has left [defaultValue].
	 */
	@Composable
	private fun EpubSliderRow(
		title: String,
		value: Int,
		range: IntRange,
		defaultValue: Int,
		shape: Shape,
		enabled: Boolean,
		valueLabel: (Int) -> String,
		onChange: (Int) -> Unit,
	) {
		val cs = MaterialTheme.colorScheme
		val changed = value != defaultValue
		Surface(
			shape = shape,
			color = LocalSettingsItemColor.current ?: cs.surfaceContainer,
			modifier = Modifier.alpha(if (enabled) 1f else 0.38f),
		) {
			Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp)) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						text = title,
						style = MaterialTheme.typography.titleMedium,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.weight(1f),
					)
					Spacer(Modifier.width(8.dp))
					Row(
						modifier = Modifier
							.clip(CircleShape)
							.background(cs.secondaryContainer)
							.clickable(enabled = enabled && changed) { onChange(defaultValue) }
							.padding(horizontal = 10.dp, vertical = 5.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						if (changed) {
							Icon(
								painter = painterResource(R.drawable.ic_revert),
								contentDescription = stringResource(R.string.reset),
								tint = cs.onSecondaryContainer,
								modifier = Modifier
									.padding(end = 4.dp)
									.size(16.dp),
							)
						}
						Text(
							text = valueLabel(value),
							style = MaterialTheme.typography.labelMedium,
							color = cs.onSecondaryContainer,
						)
					}
				}
				Slider(
					state = syncedSliderState(value.toFloat(), range.first.toFloat()..range.last.toFloat()),
					onValueChange = { position ->
						val rounded = position.roundToInt()
						if (rounded != value) onChange(rounded)
					},
					enabled = enabled,
					modifier = Modifier.fillMaxWidth(),
				)
			}
		}
	}

	/** A two-way choice on one line: the title, then both options as connected buttons. */
	@Composable
	private fun EpubChoiceRow(
		title: String,
		choices: List<Pair<String, String>>,
		selected: String,
		shape: Shape,
		enabled: Boolean = true,
		onSelected: (String) -> Unit,
	) {
		Surface(
			shape = shape,
			color = LocalSettingsItemColor.current ?: MaterialTheme.colorScheme.surfaceContainer,
		) {
			Row(
				modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = title,
					style = MaterialTheme.typography.titleMedium,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier
						.weight(1f)
						.alpha(if (enabled) 1f else 0.38f),
				)
				Spacer(Modifier.width(8.dp))
				Box(modifier = Modifier.width(176.dp)) {
					SegmentedRow(
						labels = choices.map { it.second },
						selectedIndex = choices.indexOfFirst { it.first == selected },
						onSelected = { onSelected(choices[it].first) },
						enabled = enabled,
					)
				}
			}
		}
	}

	/** The page switcher: a capsule with a filled pill that slides to the page showing. */
	@Composable
	private fun PillTabBar(
		currentPage: Int,
		onTabClick: (Int) -> Unit,
		tabs: List<Pair<String, Int>>,
		modifier: Modifier = Modifier,
	) {
		Surface(
			shape = CircleShape,
			color = MaterialTheme.colorScheme.surfaceContainerHighest,
			shadowElevation = 6.dp,
			border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
			modifier = modifier
				.widthIn(max = 336.dp)
				.fillMaxWidth()
				.height(52.dp),
		) {
			Box(modifier = Modifier.fillMaxSize()) {
				val animatedBias by animateFloatAsState(
					// -1f..1f across the tabs
					targetValue = currentPage * 2f / (tabs.size - 1) - 1f,
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
					tabs.forEachIndexed { index, (title, iconRes) ->
						val contentColor by animateColorAsState(
							targetValue = if (currentPage == index) {
								MaterialTheme.colorScheme.onPrimary
							} else {
								MaterialTheme.colorScheme.onSurfaceVariant
							},
							label = "pill_tab_content_color",
						)
						Row(
							modifier = Modifier
								.weight(1f)
								.fillMaxHeight()
								.clickable(interactionSource = null, indication = null) { onTabClick(index) },
							horizontalArrangement = Arrangement.Center,
							verticalAlignment = Alignment.CenterVertically,
						) {
							Icon(
								painter = painterResource(iconRes),
								contentDescription = null,
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun EpubThemeSheet(onDismiss: () -> Unit) {
        val sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
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
            Slider(state = syncedSliderState(value), onValueChange = onChange, enabled = enabled)
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

	private companion object {
		/** Modes in display order, each with its label. */
		val READER_MODES = listOf(
			ReaderMode.STANDARD to R.string.standard,
			ReaderMode.REVERSED to R.string.r_to_l,
			ReaderMode.VERTICAL to R.string.vertical,
			ReaderMode.WEBTOON to R.string.webtoon,
		)

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

        fun onSavePageClick()

        fun onSharePageClick()

        fun onScrollTimerClick(isLongClick: Boolean)

        fun onTextToSpeechClick()
    }
}
