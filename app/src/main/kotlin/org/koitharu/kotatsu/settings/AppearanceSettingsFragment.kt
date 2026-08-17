package org.koitharu.kotatsu.settings

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.prefs.AppProtectionTimeout
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ColorScheme
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.prefs.ScreenshotsPolicy
import org.koitharu.kotatsu.core.prefs.SearchSuggestionType
import org.koitharu.kotatsu.core.ui.util.ActivityRecreationHandle
import org.koitharu.kotatsu.core.util.LocaleComparator
import org.koitharu.kotatsu.core.util.ext.getLocalesConfig
import org.koitharu.kotatsu.core.util.ext.sortedWithSafe
import org.koitharu.kotatsu.core.util.ext.toList
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.koitharu.kotatsu.settings.appearance.PreviewSettingsFragment
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.ColorSchemePickerRow
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.MultiSelectSettingsItem
import org.koitharu.kotatsu.settings.compose.NavigationSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SliderSettingsItem
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberIntPref
import org.koitharu.kotatsu.settings.compose.rememberReadingIndicatorPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref
import org.koitharu.kotatsu.settings.compose.rememberStringSetPref
import org.koitharu.kotatsu.settings.nav.NavConfigFragment
import org.koitharu.kotatsu.settings.protect.showProtectMethodDialog
import javax.inject.Inject

@AndroidEntryPoint
class AppearanceSettingsFragment : BaseComposeSettingsFragment(R.string.appearance) {

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var activityRecreationHandle: ActivityRecreationHandle

	@Inject
	lateinit var appShortcutManager: AppShortcutManager

	private var pendingProtectState: Boolean? = null

	// Mirror the legacy fragment behavior: theme / AMOLED toggles must trigger an activity
	// recreation so the new color scheme takes effect immediately.
	private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		when (key) {
			AppSettings.KEY_THEME -> {
				AppCompatDelegate.setDefaultNightMode(settings.theme)
			}
			AppSettings.KEY_COLOR_THEME,
			AppSettings.KEY_THEME_AMOLED,
			AppSettings.KEY_UI_SCALE -> {
				activityRecreationHandle.recreateAll()
			}
			AppSettings.KEY_APP_LOCALE -> {
				AppCompatDelegate.setApplicationLocales(settings.appLocales)
			}
		}
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				AppearanceScreen(
					dynamicShortcutsAvailable = appShortcutManager.isDynamicShortcutsAvailable(),
					onOpenLocaleSettings = ::openSystemLocaleSettings,
					onOpenDetailsAppearance = {
						(activity as? SettingsActivity)?.openFragment(
							PreviewSettingsFragment::class.java,
							null,
							isFromRoot = false,
						)
					},
					onOpenNavConfig = {
						(activity as? SettingsActivity)?.openFragment(
							NavConfigFragment::class.java,
							null,
							isFromRoot = false,
						)
					},
					onProtectToggle = ::onProtectToggle,
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		settings.subscribe(prefListener)
	}

	override fun onDestroyView() {
		settings.unsubscribe(prefListener)
		if (pendingProtectState != null) {
			pendingProtectState = null
		}
		super.onDestroyView()
	}


	private fun openSystemLocaleSettings() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			val intent = Intent(
				SystemSettings.ACTION_APP_LOCALE_SETTINGS,
				Uri.fromParts("package", requireContext().packageName, null),
			)
			startActivity(intent)
		}
	}

	private fun isAuthenticationSupported(): Boolean {
		val manager = context?.let { BiometricManager.from(it) } ?: return false
		return manager.canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS
	}

	/**
	 * Entry point for the "Require unlock" switch. Turning it off clears any PIN and disables the
	 * lock; turning it on opens the M3E method chooser (device auth vs. custom PIN).
	 */
	private fun onProtectToggle(enable: Boolean) {
		if (!enable) {
			settings.isAppProtectionEnabled = false
			settings.clearAppPassword()
			return
		}
		showProtectMethodDialog(
			activity = requireActivity(),
			deviceAuthSupported = isAuthenticationSupported(),
			onSelectDevice = { startProtectionAuthentication(true) },
			onPinConfirmed = { pin ->
				settings.setAppPassword(pin)
				settings.isAppProtectionEnabled = true
			},
		)
	}

	private fun startProtectionAuthentication(requestedState: Boolean): Boolean {
		if (!isAuthenticationSupported() || !isAdded) return false
		val executor = context?.let { ContextCompat.getMainExecutor(it) } ?: return false
		pendingProtectState = requestedState
		val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
			override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
				val state = pendingProtectState ?: return
				// Device/biometric mode — make sure no stale PIN keeps this in PIN mode.
				settings.clearAppPassword()
				settings.isAppProtectionEnabled = state
				pendingProtectState = null
			}

			override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
				// keep current state on failure — read-back triggers Compose re-render via
				// the SharedPreferences listener.
				pendingProtectState = null
			}
		})
		val promptInfo = BiometricPrompt.PromptInfo.Builder()
			.setTitle(getString(R.string.app_name))
			.setSubtitle(getString(R.string.require_unlock))
			.setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
			.setConfirmationRequired(false)
			.build()
		prompt.authenticate(promptInfo)
		return true
	}
}

@Composable
private fun AppearanceScreen(
	dynamicShortcutsAvailable: Boolean,
	onOpenLocaleSettings: () -> Unit,
	onOpenDetailsAppearance: () -> Unit,
	onOpenNavConfig: () -> Unit,
	onProtectToggle: (Boolean) -> Unit,
) {
	val ctx = LocalContext.current

	// Enum-backed array sources
	val themeEntries = remember { ctx.resources.getStringArray(R.array.themes).toList() }
	val themeValues = remember { ctx.resources.getStringArray(R.array.values_theme).toList() }

	val listModeEntries = remember { ctx.resources.getStringArray(R.array.list_modes).toList() }
	val listModeValues = remember { ListMode.entries.names().toList() }
	val badgeEntries = remember { ctx.resources.getStringArray(R.array.list_badges).toList() }
	val badgeValues = remember { ctx.resources.getStringArray(R.array.values_list_badges).toList() }
	val detailsTabEntries = remember { ctx.resources.getStringArray(R.array.details_tabs).toList() }
	val detailsTabValues = remember { ctx.resources.getStringArray(R.array.details_tabs_values).toList() }
	val readingIndicatorEntries = remember { ctx.resources.getStringArray(R.array.reading_indicator_modes).toList() }
	val readingIndicatorValues = remember { ctx.resources.getStringArray(R.array.values_reading_indicator_modes).toList() }
	val searchSuggestionEntries = remember {
		SearchSuggestionType.entries.map { ctx.getString(it.titleResId) }
	}
	val searchSuggestionValues = remember { SearchSuggestionType.entries.names().toList() }
	val protectTimeoutEntries = remember {
		AppProtectionTimeout.entries.map { ctx.getString(it.titleResId) }
	}
	val protectTimeoutValues = remember { AppProtectionTimeout.entries.names().toList() }
	val screenshotsPolicyEntries = remember {
		ctx.resources.getStringArray(R.array.screenshots_policy).toList()
	}
	val screenshotsPolicyValues = remember { ScreenshotsPolicy.entries.names().toList() }
	val locales = remember {
		ctx.getLocalesConfig().toList().sortedWithSafe(LocaleComparator())
	}
	val localeEntries = remember(locales) {
		listOf(ctx.getString(R.string.follow_system)) + locales.map { it.getDisplayName(it).toTitleCase(it) }
	}
	val localeValues = remember(locales) {
		listOf("") + locales.map { it.toLanguageTag() }
	}

	// Bound preferences
	var colorScheme by rememberStringPref(AppSettings.KEY_COLOR_THEME, ColorScheme.default.name)
	var theme by rememberStringPref(AppSettings.KEY_THEME, "-1")
	var amoled by rememberBooleanPref(AppSettings.KEY_THEME_AMOLED, false)
	var uiScale by rememberIntPref(AppSettings.KEY_UI_SCALE, 100)
	var hapticFeedback by rememberBooleanPref(AppSettings.KEY_HAPTIC_FEEDBACK, true)
	var hideStatusBar by rememberBooleanPref(AppSettings.KEY_HIDE_STATUS_BAR, false)
	var locale by rememberStringPref(AppSettings.KEY_APP_LOCALE, "")
	var listMode by rememberStringPref(AppSettings.KEY_LIST_MODE, ListMode.GRID.name)
	var gridSize by rememberIntPref(AppSettings.KEY_GRID_SIZE, 100)
	var quickFilter by rememberBooleanPref(AppSettings.KEY_QUICK_FILTER, true)
	var readingIndicator by rememberReadingIndicatorPref(AppSettings.KEY_PROGRESS_INDICATORS)
	var mangaListBadges by rememberStringSetPref(AppSettings.KEY_MANGA_LIST_BADGES, emptySet())

	var descriptionCollapse by rememberBooleanPref(AppSettings.KEY_COLLAPSE_DESCRIPTION, true)
	var pagesTab by rememberBooleanPref(AppSettings.KEY_PAGES_TAB, true)
	var detailsTab by rememberStringPref(AppSettings.KEY_DETAILS_TAB, "-1")

	var searchSuggestions by rememberStringSetPref(AppSettings.KEY_SEARCH_SUGGESTION_TYPES, emptySet())
	var mainFab by rememberBooleanPref(AppSettings.KEY_MAIN_FAB, true)
	var navLabels by rememberBooleanPref(AppSettings.KEY_NAV_LABELS, true)
	var navPinned by rememberBooleanPref(AppSettings.KEY_NAV_PINNED, false)
	var navLegacy by rememberBooleanPref(AppSettings.KEY_NAV_LEGACY, false)
	var exitConfirm by rememberBooleanPref(AppSettings.KEY_EXIT_CONFIRM, false)
	var dynamicShortcuts by rememberBooleanPref(AppSettings.KEY_SHORTCUTS, true)

	var protectApp by rememberBooleanPref(AppSettings.KEY_PROTECT_APP, false)
	var protectAppTimeout by rememberStringPref(
		AppSettings.KEY_PROTECT_APP_TIMEOUT,
		AppProtectionTimeout.INSTANT.name,
	)
	var screenshotsPolicy by rememberStringPref(
		AppSettings.KEY_SCREENSHOTS_POLICY,
		ScreenshotsPolicy.ALLOW.name,
	)

	// PIN works without a device screen lock, so the option is always available; the method
	// chooser greys out the device-auth choice when biometric/credential auth is unsupported.
	val protectAppSummary = stringResource(R.string.require_unlock_summary)

	SettingsScaffold {
		// The color scheme picker is its own inline widget (horizontal cards), rendered
		// directly here rather than via a SettingsGroup row.
		item {
			ColorSchemePickerRow(
				title = stringResource(R.string.color_theme),
				selectedValue = colorScheme,
				onValueChange = { colorScheme = it },
				shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
			)
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = "Theme") {
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.theme),
						entries = themeEntries,
						entryValues = themeValues,
						selectedValue = theme,
						onValueChange = {
							theme = it
							@Suppress("WrongConstant")
							val mode = it.toIntOrNull() ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
							AppCompatDelegate.setDefaultNightMode(mode)
						},
						icon = R.drawable.ic_appearance,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					val isSystemDark = isSystemInDarkTheme()
					val isDarkActive = when (theme.toIntOrNull()) {
						AppCompatDelegate.MODE_NIGHT_YES -> true
						AppCompatDelegate.MODE_NIGHT_NO -> false
						else -> isSystemDark
					}
					SwitchSettingsItem(
						title = stringResource(R.string.black_dark_theme),
						subtitle = stringResource(R.string.black_dark_theme_summary),
						checked = amoled,
						onCheckedChange = { amoled = it },
						icon = R.drawable.ic_eye_off,
						enabled = isDarkActive,
						shape = pos.shape,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.language),
						entries = localeEntries,
						entryValues = localeValues,
						selectedValue = locale,
						onValueChange = { locale = it },
						icon = R.drawable.ic_language,

						shape = pos.shape,
					)
				}
				item { pos ->
					SliderSettingsItem(
						title = stringResource(R.string.ui_scale),
						value = uiScale,
						valueFrom = 80,
						valueTo = 120,
						stepSize = 10,
						valueLabel = { v ->
							when {
								v <= 80 -> ctx.getString(R.string.ui_scale_smallest)
								v < 100 -> ctx.getString(R.string.ui_scale_smaller)
								v == 100 -> ctx.getString(R.string.ui_scale_default)
								v < 120 -> ctx.getString(R.string.ui_scale_larger)
								else -> ctx.getString(R.string.ui_scale_largest)
							}
						},
						onValueChange = { uiScale = it },
						icon = R.drawable.ic_zoom_in,
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.haptic_feedback),
						subtitle = stringResource(R.string.haptic_feedback_summary),
						checked = hapticFeedback,
						onCheckedChange = { hapticFeedback = it },
						icon = R.drawable.ic_haptic,

						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.hide_status_bar),
						subtitle = stringResource(R.string.hide_status_bar_summary),
						checked = hideStatusBar,
						onCheckedChange = { hideStatusBar = it },
						icon = R.drawable.ic_eye_off,

						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.manga_list)) {
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.list_mode),
						entries = listModeEntries,
						entryValues = listModeValues,
						selectedValue = listMode,
						onValueChange = { listMode = it },
						icon = R.drawable.ic_list,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					SliderSettingsItem(
						title = stringResource(R.string.grid_size),
						value = gridSize,
						valueFrom = 50,
						valueTo = 150,
						stepSize = 5,
						unitSuffix = "%",
						onValueChange = { gridSize = it },
						icon = R.drawable.ic_grid,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.show_quick_filters),
						subtitle = stringResource(R.string.show_quick_filters_summary),
						checked = quickFilter,
						onCheckedChange = { quickFilter = it },
						icon = R.drawable.ic_filter_menu,

						shape = pos.shape,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.show_reading_indicators),
						entries = readingIndicatorEntries,
						entryValues = readingIndicatorValues,
						selectedValue = readingIndicator,
						onValueChange = { readingIndicator = it },
						icon = R.drawable.ic_history,

						shape = pos.shape,
					)
				}
				item { pos ->
					MultiSelectSettingsItem(
						title = stringResource(R.string.badges_in_lists),
						entries = badgeEntries,
						entryValues = badgeValues,
						selectedValues = mangaListBadges,
						onValuesChange = { mangaListBadges = it },
						icon = R.drawable.ic_tag,
						
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.details)) {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.collapse_long_description),
						checked = descriptionCollapse,
						onCheckedChange = { descriptionCollapse = it },
						icon = R.drawable.ic_expand,

						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.show_pages_thumbs),
						subtitle = stringResource(R.string.show_pages_thumbs_summary),
						checked = pagesTab,
						onCheckedChange = { pagesTab = it },
						icon = R.drawable.ic_images,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.default_tab),
						entries = detailsTabEntries,
						entryValues = detailsTabValues,
						selectedValue = detailsTab,
						onValueChange = { detailsTab = it },
						icon = R.drawable.ic_list_group,
						
						shape = pos.shape,
						enabled = pagesTab,
					)
				}
				item { pos ->
					NavigationSettingsItem(
						title = stringResource(R.string.details_appearance),
						subtitle = stringResource(R.string.details_appearance_summary),
						icon = R.drawable.ic_list_detailed,
						
						shape = pos.shape,
						onClick = onOpenDetailsAppearance,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.main_screen)) {
				item { pos ->
					MultiSelectSettingsItem(
						title = stringResource(R.string.search_suggestions),
						entries = searchSuggestionEntries,
						entryValues = searchSuggestionValues,
						selectedValues = searchSuggestions,
						onValuesChange = { searchSuggestions = it },
						icon = R.drawable.ic_suggestion,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					NavigationSettingsItem(
						title = stringResource(R.string.main_screen_sections),
						icon = R.drawable.ic_drawer_menu,
						
						shape = pos.shape,
						onClick = onOpenNavConfig,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.main_screen_fab),
						subtitle = stringResource(R.string.main_screen_fab_summary),
						checked = mainFab,
						onCheckedChange = { mainFab = it },
						icon = R.drawable.ic_read,

						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.show_labels_in_navbar),
						checked = navLabels,
						onCheckedChange = { navLabels = it },
						icon = R.drawable.ic_title,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.use_legacy_navigation_bar),
						subtitle = stringResource(R.string.use_legacy_navigation_bar_summary),
						checked = navLegacy,
						onCheckedChange = { navLegacy = it },
						icon = R.drawable.ic_bottom_navigation,

						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.pin_navigation_ui),
						subtitle = stringResource(R.string.pin_navigation_ui_summary),
						checked = navPinned,
						onCheckedChange = { navPinned = it },
						icon = R.drawable.ic_pin,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.exit_confirmation),
						subtitle = stringResource(R.string.exit_confirmation_summary),
						checked = exitConfirm,
						onCheckedChange = { exitConfirm = it },
						icon = R.drawable.ic_alert_outline,
						
						shape = pos.shape,
					)
				}
				if (dynamicShortcutsAvailable) {
					item { pos ->
						SwitchSettingsItem(
							title = stringResource(R.string.history_shortcuts),
							subtitle = stringResource(R.string.history_shortcuts_summary),
							checked = dynamicShortcuts,
							onCheckedChange = { dynamicShortcuts = it },
							icon = R.drawable.ic_shortcut,
							
							shape = pos.shape,
						)
					}
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.privacy)) {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.require_unlock),
						subtitle = protectAppSummary,
						checked = protectApp,
						onCheckedChange = { requested ->
							// The actual pref write happens in the fragment's callback after the
							// user picks a lock method (or confirms a PIN).
							onProtectToggle(requested)
						},
						icon = R.drawable.ic_lock,

						shape = pos.shape,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.require_unlock_after),
						entries = protectTimeoutEntries,
						entryValues = protectTimeoutValues,
						selectedValue = protectAppTimeout,
						onValueChange = { protectAppTimeout = it },
						icon = R.drawable.ic_timer,

						shape = pos.shape,
						enabled = protectApp,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.screenshots_policy),
						entries = screenshotsPolicyEntries,
						entryValues = screenshotsPolicyValues,
						selectedValue = screenshotsPolicy,
						onValueChange = { screenshotsPolicy = it },
						icon = R.drawable.ic_eye,
						
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}
