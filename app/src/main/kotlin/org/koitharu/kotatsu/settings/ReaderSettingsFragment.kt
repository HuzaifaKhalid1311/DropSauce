package org.koitharu.kotatsu.settings

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.ZoomMode
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderAnimation
import org.koitharu.kotatsu.core.prefs.ReaderBackground
import org.koitharu.kotatsu.core.prefs.ReaderControl
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.ui.util.ActivityRecreationHandle
import org.koitharu.kotatsu.parsers.util.mapToSet
import javax.inject.Inject
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.CategoryPalette
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.MultiSelectSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SliderSettingsItem
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberIntPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref
import org.koitharu.kotatsu.settings.compose.rememberStringSetPref

@AndroidEntryPoint
class ReaderSettingsFragment : BaseComposeSettingsFragment(R.string.reader_settings) {

	@Inject
	lateinit var activityRecreationHandle: ActivityRecreationHandle

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				ReaderScreen(
					onTapActions = router::openReaderTapGridSettings,
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
	}

}

@Composable
private fun ReaderScreen(
	onTapActions: () -> Unit,
) {
	val ctx = LocalContext.current
	val colors = CategoryPalette.forKey("reader")

	// Enum-backed entry/value arrays
	val readerModeEntries = remember { ctx.resources.getStringArray(R.array.reader_modes).toList() }
	val readerModeValues = remember { ReaderMode.entries.names().toList() }
	val zoomModeEntries = remember { ctx.resources.getStringArray(R.array.zoom_modes).toList() }
	val zoomModeValues = remember { ZoomMode.entries.names().toList() }
	val readerControlEntries = remember { ctx.resources.getStringArray(R.array.reader_controls).toList() }
	val readerControlValues = remember { ReaderControl.entries.names().toList() }
	val readerAnimationEntries = remember { ctx.resources.getStringArray(R.array.reader_animation).toList() }
	val readerAnimationValues = remember { ReaderAnimation.entries.names().toList() }
	val readerBackgroundEntries = remember { ctx.resources.getStringArray(R.array.reader_backgrounds).toList() }
	val readerBackgroundValues = remember { ReaderBackground.entries.names().toList() }
	val readerCropEntries = remember { ctx.resources.getStringArray(R.array.reader_crop).toList() }
	val readerCropValues = remember { ctx.resources.getStringArray(R.array.values_reader_crop).toList() }
	val orientationEntries = remember { ctx.resources.getStringArray(R.array.screen_orientations).toList() }
	val orientationValues = remember {
		listOf(
			ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED.toString(),
			ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR.toString(),
			ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT.toString(),
			ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE.toString(),
		)
	}

	var readerMode by rememberStringPref(AppSettings.KEY_READER_MODE, ReaderMode.STANDARD.name)
	var readerModeDetect by rememberBooleanPref(AppSettings.KEY_READER_MODE_DETECT, true)
	var zoomMode by rememberStringPref(AppSettings.KEY_ZOOM_MODE, ZoomMode.FIT_CENTER.name)
	var readerZoomButtons by rememberBooleanPref(AppSettings.KEY_READER_ZOOM_BUTTONS, false)
	var webtoonZoomOut by rememberIntPref(AppSettings.KEY_WEBTOON_ZOOM_OUT, 0)
	var webtoonGaps by rememberBooleanPref(AppSettings.KEY_WEBTOON_GAPS, false)
	var readerControls by rememberStringSetPref(
		AppSettings.KEY_READER_CONTROLS,
		ReaderControl.DEFAULT.mapToSet { it.name },
	)
	var readerTapsLtr by rememberBooleanPref(AppSettings.KEY_READER_CONTROL_LTR, false)
	var readerVolumeButtons by rememberBooleanPref(AppSettings.KEY_READER_VOLUME_BUTTONS, false)
	var readerNavigationInverted by rememberBooleanPref(AppSettings.KEY_READER_NAVIGATION_INVERTED, false)
	var readerAnimation by rememberStringPref(AppSettings.KEY_READER_ANIMATION, ReaderAnimation.DEFAULT.name)
	var webtoonPullGesture by rememberBooleanPref(AppSettings.KEY_WEBTOON_PULL_GESTURE, false)
	var enhancedColors by rememberBooleanPref(AppSettings.KEY_32BIT_COLOR, false)
	var readerOptimize by rememberBooleanPref(AppSettings.KEY_READER_OPTIMIZE, false)
	var readerUpscale by rememberBooleanPref(AppSettings.KEY_READER_UPSCALE, false)
	var readerCrop by rememberStringSetPref(AppSettings.KEY_READER_CROP, emptySet())
	var readerFullscreen by rememberBooleanPref(AppSettings.KEY_READER_FULLSCREEN, true)
	var readerOrientation by rememberStringPref(
		AppSettings.KEY_READER_ORIENTATION,
		ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED.toString(),
	)
	var readerScreenOn by rememberBooleanPref(AppSettings.KEY_READER_SCREEN_ON, true)
	var readerMultitask by rememberBooleanPref(AppSettings.KEY_READER_MULTITASK, false)
	var readerBar by rememberBooleanPref(AppSettings.KEY_READER_BAR, true)
	var chapterJumpDialog by rememberBooleanPref(AppSettings.KEY_CHAPTER_JUMP_DIALOG, true)
	var readerBackground by rememberStringPref(
		AppSettings.KEY_READER_BACKGROUND,
		ReaderBackground.DEFAULT.name,
	)
	var pagesNumbers by rememberBooleanPref(AppSettings.KEY_PAGES_NUMBERS, false)
	var titleTapToRead by rememberBooleanPref(AppSettings.KEY_TITLE_TAP_TO_READ, false)

	val isWebtoonMode = readerMode == ReaderMode.WEBTOON.name

	SettingsScaffold {
		item {
			SettingsGroup(title = "Mode") {
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.default_mode),
						entries = readerModeEntries,
						entryValues = readerModeValues,
						selectedValue = readerMode,
						onValueChange = { readerMode = it },
						icon = R.drawable.ic_book_page,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.detect_reader_mode),
						subtitle = stringResource(R.string.detect_reader_mode_summary),
						checked = readerModeDetect,
						onCheckedChange = { readerModeDetect = it },
						icon = R.drawable.ic_auto_detect,

						shape = pos.shape,
						enabled = !isWebtoonMode,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.title_tap_to_read),
						subtitle = stringResource(R.string.title_tap_to_read_summary),
						checked = titleTapToRead,
						onCheckedChange = { titleTapToRead = it },
						icon = R.drawable.ic_read,

						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = "Zoom") {
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.scale_mode),
						entries = zoomModeEntries,
						entryValues = zoomModeValues,
						selectedValue = zoomMode,
						onValueChange = { zoomMode = it },
						icon = R.drawable.ic_zoom_in,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.reader_zoom_buttons),
						subtitle = stringResource(R.string.reader_zoom_buttons_summary),
						checked = readerZoomButtons,
						onCheckedChange = { readerZoomButtons = it },
						icon = R.drawable.ic_add,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					SliderSettingsItem(
						title = stringResource(R.string.default_webtoon_zoom_out),
						value = webtoonZoomOut,
						valueFrom = 0,
						valueTo = 50,
						stepSize = 10,
						unitSuffix = "%",
						onValueChange = { webtoonZoomOut = it },
						icon = R.drawable.ic_zoom_out,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.webtoon_gaps),
						subtitle = stringResource(R.string.webtoon_gaps_summary),
						checked = webtoonGaps,
						onCheckedChange = { webtoonGaps = it },
						icon = R.drawable.ic_move_horizontal,
						
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = "Controls") {
				item { pos ->
					MultiSelectSettingsItem(
						title = stringResource(R.string.reader_controls_in_bottom_bar),
						entries = readerControlEntries,
						entryValues = readerControlValues,
						selectedValues = readerControls,
						onValuesChange = { readerControls = it },
						icon = R.drawable.ic_tap,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.reader_actions),
						subtitle = stringResource(R.string.reader_actions_summary),
						icon = R.drawable.ic_tap_reorder,
						
						shape = pos.shape,
						onClick = onTapActions,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.reader_control_ltr),
						subtitle = stringResource(R.string.reader_control_ltr_summary),
						checked = readerTapsLtr,
						onCheckedChange = { readerTapsLtr = it },
						icon = R.drawable.ic_reader_ltr,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.switch_pages_volume_buttons),
						subtitle = stringResource(R.string.switch_pages_volume_buttons_summary),
						checked = readerVolumeButtons,
						onCheckedChange = { readerVolumeButtons = it },
						icon = R.drawable.ic_action_skip,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.reader_navigation_inverted),
						subtitle = stringResource(R.string.reader_navigation_inverted_summary),
						checked = readerNavigationInverted,
						onCheckedChange = { readerNavigationInverted = it },
						icon = R.drawable.ic_revert,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.pages_animation),
						entries = readerAnimationEntries,
						entryValues = readerAnimationValues,
						selectedValue = readerAnimation,
						onValueChange = { readerAnimation = it },
						icon = R.drawable.ic_play,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.enable_pull_gesture_title),
						subtitle = stringResource(R.string.enable_pull_gesture_summary),
						checked = webtoonPullGesture,
						onCheckedChange = { webtoonPullGesture = it },
						icon = R.drawable.ic_gesture_vertical,
						
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = "Image") {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.enhanced_colors),
						subtitle = stringResource(R.string.enhanced_colors_summary),
						checked = enhancedColors,
						onCheckedChange = { enhancedColors = it },
						icon = R.drawable.ic_images,
						
						shape = pos.shape,
					)
				}
				if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
					item { pos ->
						SwitchSettingsItem(
							title = stringResource(R.string.reader_upscale),
							subtitle = stringResource(R.string.reader_upscale_summary),
							checked = readerUpscale,
							onCheckedChange = { readerUpscale = it },
							icon = R.drawable.ic_sparkles,

							shape = pos.shape,
						)
					}
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.reader_optimize),
						subtitle = stringResource(R.string.reader_optimize_summary),
						checked = readerOptimize,
						onCheckedChange = { readerOptimize = it },
						icon = R.drawable.ic_filter_funnel,

						shape = pos.shape,
					)
				}
				item { pos ->
					MultiSelectSettingsItem(
						title = stringResource(R.string.crop_pages),
						entries = readerCropEntries,
						entryValues = readerCropValues,
						selectedValues = readerCrop,
						onValuesChange = { readerCrop = it },
						icon = R.drawable.ic_select_range,
						
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = "Display") {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.fullscreen_mode),
						subtitle = stringResource(R.string.reader_fullscreen_summary),
						checked = readerFullscreen,
						onCheckedChange = { readerFullscreen = it },
						icon = R.drawable.ic_expand,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.screen_orientation),
						entries = orientationEntries,
						entryValues = orientationValues,
						selectedValue = readerOrientation,
						onValueChange = { readerOrientation = it },
						icon = R.drawable.ic_screen_rotation,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.keep_screen_on),
						subtitle = stringResource(R.string.keep_screen_on_summary),
						checked = readerScreenOn,
						onCheckedChange = { readerScreenOn = it },
						icon = R.drawable.ic_eye,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.reader_multitask),
						subtitle = stringResource(R.string.reader_multitask_summary),
						checked = readerMultitask,
						onCheckedChange = { readerMultitask = it },
						icon = R.drawable.ic_drawer_menu_open,
						
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = "Reading info") {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.reader_info_bar),
						subtitle = stringResource(R.string.reader_info_bar_summary),
						checked = readerBar,
						onCheckedChange = { readerBar = it },
						icon = R.drawable.ic_info_outline,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.chapter_jump_setting_title),
						subtitle = stringResource(R.string.chapter_jump_setting_summary),
						checked = chapterJumpDialog,
						onCheckedChange = { chapterJumpDialog = it },
						icon = R.drawable.ic_eye,

						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = "Background") {
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.background),
						entries = readerBackgroundEntries,
						entryValues = readerBackgroundValues,
						selectedValue = readerBackground,
						onValueChange = { readerBackground = it },
						icon = R.drawable.ic_appearance,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.show_pages_numbers),
						subtitle = stringResource(R.string.show_pages_numbers_summary),
						checked = pagesNumbers,
						onCheckedChange = { pagesNumbers = it },
						icon = R.drawable.ic_title,
						
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}
