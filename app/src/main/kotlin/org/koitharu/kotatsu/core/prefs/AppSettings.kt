package org.koitharu.kotatsu.core.prefs

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.FloatRange
import androidx.appcompat.app.AppCompatDelegate
import androidx.collection.ArraySet
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.util.lang.Hash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.mihon.model.ExternalRepoInfo
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionInstallMode
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreRegistryState
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.ZoomMode
import org.koitharu.kotatsu.core.network.DoHProvider
import org.koitharu.kotatsu.core.prefs.DetailsUiMode.COMPACT
import org.koitharu.kotatsu.core.util.ext.connectivityManager
import org.koitharu.kotatsu.core.util.ext.getEnumValue
import org.koitharu.kotatsu.core.util.ext.observeChanges
import org.koitharu.kotatsu.core.util.ext.putAll
import org.koitharu.kotatsu.core.util.ext.putEnumValue
import org.koitharu.kotatsu.core.util.ext.takeIfReadable
import org.koitharu.kotatsu.core.util.ext.toUriOrNull
import org.koitharu.kotatsu.explore.data.SourcesSortOrder
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.find
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.mapToSet
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.reader.domain.ReaderColorFilter
import java.io.File
import java.net.Proxy
import java.util.UUID
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppSettings @Inject constructor(@ApplicationContext context: Context) {

	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
	private val json = Json { ignoreUnknownKeys = true }
	private val onboardingInstallIdFile = File(context.noBackupFilesDir, "onboarding_install_id")
	private val connectivityManager = context.connectivityManager
	private val mangaListBadgesDefault = ArraySet(context.resources.getStringArray(R.array.values_list_badges))
	private val onboardingInstallId by lazy {
		runCatching {
			onboardingInstallIdFile.parentFile?.mkdirs()
			if (onboardingInstallIdFile.exists()) {
				onboardingInstallIdFile.readText().trim().ifBlank { throw IllegalStateException() }
			} else {
				val value = UUID.randomUUID().toString()
				onboardingInstallIdFile.writeText(value)
				value
			}
		}.getOrElse {
			UUID.randomUUID().toString()
		}
	}

	var listMode: ListMode
		get() = prefs.getEnumValue(KEY_LIST_MODE, ListMode.GRID)
		set(value) = prefs.edit { putEnumValue(KEY_LIST_MODE, value) }

	val theme: Int
		get() = prefs.getString(KEY_THEME, null)?.toIntOrNull()
			?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM

	fun setTheme(mode: Int) = prefs.edit {
		putString(KEY_THEME, mode.toString())
	}

	val colorScheme: ColorScheme
		get() = prefs.getEnumValue(KEY_COLOR_THEME, ColorScheme.default)

	fun setColorScheme(value: ColorScheme) = prefs.edit {
		putEnumValue(KEY_COLOR_THEME, value)
	}

	val isAmoledTheme: Boolean
		get() = prefs.getBoolean(KEY_THEME_AMOLED, false)

	fun setAmoledTheme(enabled: Boolean) = prefs.edit {
		putBoolean(KEY_THEME_AMOLED, enabled)
	}

	val isStatusBarHidden: Boolean
		get() = prefs.getBoolean(KEY_HIDE_STATUS_BAR, false)

	var isOnboardingCompleted: Boolean
		get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
			&& prefs.getString(KEY_ONBOARDING_INSTALL_ID, null) == onboardingInstallId
		set(value) = prefs.edit {
			putBoolean(KEY_ONBOARDING_COMPLETED, value)
			if (value) {
				putString(KEY_ONBOARDING_INSTALL_ID, onboardingInstallId)
			} else {
				remove(KEY_ONBOARDING_INSTALL_ID)
			}
		}

	var mainNavItems: List<NavItem>
		get() {
			val raw = prefs.getString(KEY_NAV_MAIN, null)?.split(',')
			val items = if (raw.isNullOrEmpty()) {
				listOf(NavItem.FAVORITES, NavItem.FEED, NavItem.HISTORY, NavItem.EXPLORE)
			} else {
				raw.mapNotNull { x -> NavItem.entries.find(x) }.ifEmpty { listOf(NavItem.EXPLORE) }
			}
			return items.take(4)
		}
		set(value) {
			prefs.edit {
				putString(KEY_NAV_MAIN, value.joinToString(",") { it.name })
			}
		}

	val isNavLabelsVisible: Boolean
		get() = prefs.getBoolean(KEY_NAV_LABELS, true)

	val isNavBarPinned: Boolean
		get() = prefs.getBoolean(KEY_NAV_PINNED, false)

	val isLegacyNavigationBar: Boolean
		get() = prefs.getBoolean(KEY_NAV_LEGACY, false)

	val isMainFabEnabled: Boolean
		get() = prefs.getBoolean(KEY_MAIN_FAB, true)

	var gridSize: Int
		get() = prefs.getInt(KEY_GRID_SIZE, 100)
		set(value) = prefs.edit { putInt(KEY_GRID_SIZE, value) }

	// Global UI scale as a percent (85 = smaller, 100 = default, 115 = larger). Applied by
	// overriding densityDpi in BaseActivity.attachBaseContext, so it scales everything.
	var uiScalePercent: Int
		get() = prefs.getInt(KEY_UI_SCALE, 100)
		set(value) = prefs.edit { putInt(KEY_UI_SCALE, value) }

	var isTitleOverCover: Boolean
		get() = prefs.getBoolean(KEY_TITLE_OVER_COVER, true)
		set(value) = prefs.edit { putBoolean(KEY_TITLE_OVER_COVER, value) }

	var isTitleTapToReadEnabled: Boolean
		get() = prefs.getBoolean(KEY_TITLE_TAP_TO_READ, false)
		set(value) = prefs.edit { putBoolean(KEY_TITLE_TAP_TO_READ, value) }

	var isGridSpacingIncreased: Boolean
		get() = prefs.getBoolean(KEY_GRID_SPACING_INCREASED, false)
		set(value) = prefs.edit { putBoolean(KEY_GRID_SPACING_INCREASED, value) }

	var gridSizePages: Int
		get() = prefs.getInt(KEY_GRID_SIZE_PAGES, 100)
		set(value) = prefs.edit { putInt(KEY_GRID_SIZE_PAGES, value) }

	val isQuickFilterEnabled: Boolean
		get() = prefs.getBoolean(KEY_QUICK_FILTER, true)

	val isBackdropEnabled: Boolean
		get() = prefs.getBoolean(KEY_DETAILS_BACKDROP, true)

	var backdropBlurAmount: Int
		get() {
			val raw = prefs.getInt(KEY_DETAILS_BACKDROP_BLUR_AMOUNT, 2)
			return when {
				raw <= 0 -> 0
				raw == 1 -> 1
				else -> 2
			}
		}
		set(value) = prefs.edit { putInt(KEY_DETAILS_BACKDROP_BLUR_AMOUNT, value) }

	var historyListMode: ListMode
		get() = prefs.getEnumValue(KEY_LIST_MODE_HISTORY, listMode)
		set(value) = prefs.edit { putEnumValue(KEY_LIST_MODE_HISTORY, value) }

	var suggestionsListMode: ListMode
		get() = prefs.getEnumValue(KEY_LIST_MODE_SUGGESTIONS, listMode)
		set(value) = prefs.edit { putEnumValue(KEY_LIST_MODE_SUGGESTIONS, value) }

	var favoritesListMode: ListMode
		get() = prefs.getEnumValue(KEY_LIST_MODE_FAVORITES, listMode)
		set(value) = prefs.edit { putEnumValue(KEY_LIST_MODE_FAVORITES, value) }

	val isTagsWarningsEnabled: Boolean
		get() = prefs.getBoolean(KEY_TAGS_WARNINGS, true)

	var isNsfwContentDisabled: Boolean
		get() = prefs.getBoolean(KEY_DISABLE_NSFW, false)
		set(value) = prefs.edit { putBoolean(KEY_DISABLE_NSFW, value) }

	var appLocales: LocaleListCompat
		get() {
			val raw = prefs.getString(KEY_APP_LOCALE, null)
			return LocaleListCompat.forLanguageTags(raw)
		}
		set(value) {
			prefs.edit {
				putString(KEY_APP_LOCALE, value.toLanguageTags())
			}
		}

	var isReaderDoubleOnLandscape: Boolean
		get() = prefs.getBoolean(KEY_READER_DOUBLE_PAGES, false)
		set(value) = prefs.edit { putBoolean(KEY_READER_DOUBLE_PAGES, value) }

	var isReaderDoubleOnFoldable: Boolean
		get() = prefs.getBoolean(KEY_READER_DOUBLE_FOLDABLE, false)
		set(value) = prefs.edit { putBoolean(KEY_READER_DOUBLE_FOLDABLE, value) }

	@get:FloatRange(0.0, 1.0)
	var readerDoublePagesSensitivity: Float
		get() = prefs.getFloat(KEY_READER_DOUBLE_PAGES_SENSITIVITY, 0.5f)
		set(@FloatRange(0.0, 1.0) value) = prefs.edit { putFloat(KEY_READER_DOUBLE_PAGES_SENSITIVITY, value) }

	val readerScreenOrientation: Int
		get() = prefs.getString(KEY_READER_ORIENTATION, null)?.toIntOrNull()
			?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

	val isReaderVolumeButtonsEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_VOLUME_BUTTONS, false)

	var epubFontSize: Int
		get() = prefs.getInt(KEY_EPUB_FONT_SIZE, 100)
		set(value) = prefs.edit { putInt(KEY_EPUB_FONT_SIZE, value.coerceIn(50, 200)) }

	var epubFontFamily: String
		get() = prefs.getString(KEY_EPUB_FONT_FAMILY, "serif") ?: "serif"
		set(value) = prefs.edit { putString(KEY_EPUB_FONT_FAMILY, value) }

	var epubLineHeight: Int
		get() = prefs.getInt(KEY_EPUB_LINE_HEIGHT, 160)
		set(value) = prefs.edit { putInt(KEY_EPUB_LINE_HEIGHT, value.coerceIn(100, 240)) }

	var epubParagraphSpacing: Int
		get() = prefs.getInt(KEY_EPUB_PARAGRAPH_SPACING, 0)
		set(value) = prefs.edit { putInt(KEY_EPUB_PARAGRAPH_SPACING, value.coerceIn(0, 48)) }

	var epubHorizontalPadding: Int
		get() = prefs.getInt(KEY_EPUB_HORIZONTAL_PADDING, 20)
		set(value) = prefs.edit { putInt(KEY_EPUB_HORIZONTAL_PADDING, value.coerceIn(0, 64)) }

	var epubVerticalPadding: Int
		get() = prefs.getInt(KEY_EPUB_VERTICAL_PADDING, 112).coerceIn(0, 112)
		set(value) = prefs.edit { putInt(KEY_EPUB_VERTICAL_PADDING, value.coerceIn(0, 112)) }

	var epubTextAlign: String
		get() = prefs.getString(KEY_EPUB_TEXT_ALIGN, "justify") ?: "justify"
		set(value) = prefs.edit { putString(KEY_EPUB_TEXT_ALIGN, value) }

	var epubReadingMode: String
		get() = prefs.getString(KEY_EPUB_READING_MODE, "scroll") ?: "scroll"
		set(value) = prefs.edit { putString(KEY_EPUB_READING_MODE, value) }

	var isEpubPagedTapGesturesEnabled: Boolean
		get() = prefs.getBoolean(KEY_EPUB_PAGED_TAP_GESTURES, false)
		set(value) = prefs.edit { putBoolean(KEY_EPUB_PAGED_TAP_GESTURES, value) }

	var isEpubPublisherStyleEnabled: Boolean
		get() = prefs.getBoolean(KEY_EPUB_PUBLISHER_STYLE, false)
		set(value) = prefs.edit { putBoolean(KEY_EPUB_PUBLISHER_STYLE, value) }

	// "system" | "white" | "gray" | "black" | "custom" - page colors of the EPUB reader only
	var epubTheme: String
		get() = prefs.getString(KEY_EPUB_THEME, "system") ?: "system"
		set(value) = prefs.edit { putString(KEY_EPUB_THEME, value) }

	var epubCustomBackgroundColor: Int
		get() = prefs.getInt(KEY_EPUB_CUSTOM_BACKGROUND_COLOR, 0xFFFFFFFF.toInt())
		set(value) = prefs.edit { putInt(KEY_EPUB_CUSTOM_BACKGROUND_COLOR, value) }

	var epubCustomTextColor: Int
		get() = prefs.getInt(KEY_EPUB_CUSTOM_TEXT_COLOR, 0xFF1B1B1F.toInt())
		set(value) = prefs.edit { putInt(KEY_EPUB_CUSTOM_TEXT_COLOR, value) }

	var epubCustomHighlightColor: Int
		get() = prefs.getInt(KEY_EPUB_CUSTOM_HIGHLIGHT_COLOR, 0xFFFFD54F.toInt())
		set(value) = prefs.edit { putInt(KEY_EPUB_CUSTOM_HIGHLIGHT_COLOR, value) }

	var epubCustomFontName: String
		get() = prefs.getString(KEY_EPUB_CUSTOM_FONT_NAME, "").orEmpty()
		set(value) = prefs.edit { putString(KEY_EPUB_CUSTOM_FONT_NAME, value) }

	var epubCustomFontRevision: Int
		get() = prefs.getInt(KEY_EPUB_CUSTOM_FONT_REVISION, 0)
		set(value) = prefs.edit { putInt(KEY_EPUB_CUSTOM_FONT_REVISION, value) }

	val isReaderZoomButtonsEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_ZOOM_BUTTONS, false)

	val isReaderControlAlwaysLTR: Boolean
		get() = prefs.getBoolean(KEY_READER_CONTROL_LTR, false)

	val isReaderNavigationInverted: Boolean
		get() = prefs.getBoolean(KEY_READER_NAVIGATION_INVERTED, false)

	val isReaderFullscreenEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_FULLSCREEN, true)

	val isReaderOptimizationEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_OPTIMIZE, false)

	val isReaderUpscaleEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_UPSCALE, false)

	var isChapterJumpDialogEnabled: Boolean
		get() = prefs.getBoolean(KEY_CHAPTER_JUMP_DIALOG, true)
		set(value) = prefs.edit { putBoolean(KEY_CHAPTER_JUMP_DIALOG, value) }

	val readerControls: Set<ReaderControl>
		get() = prefs.getStringSet(KEY_READER_CONTROLS, null)?.mapNotNullTo(EnumSet.noneOf(ReaderControl::class.java)) {
			ReaderControl.entries.find(it)
		} ?: ReaderControl.DEFAULT

	val isOfflineCheckDisabled: Boolean
		get() = prefs.getBoolean(KEY_OFFLINE_DISABLED, false)

	var isAllFavouritesVisible: Boolean
		get() = prefs.getBoolean(KEY_ALL_FAVOURITES_VISIBLE, true)
		set(value) = prefs.edit { putBoolean(KEY_ALL_FAVOURITES_VISIBLE, value) }

	val isTrackerEnabled: Boolean
		get() = prefs.getBoolean(KEY_TRACKER_ENABLED, true)

	val isTrackerWifiOnly: Boolean
		get() = prefs.getBoolean(KEY_TRACKER_WIFI_ONLY, false)

	val trackerFrequencyFactor: Float
		get() = prefs.getString(KEY_TRACKER_FREQUENCY, null)?.toFloatOrNull() ?: 1f

	val isTrackerNotificationsEnabled: Boolean
		get() = prefs.getBoolean(KEY_TRACKER_NOTIFICATIONS, true)

	val isTrackerNsfwDisabled: Boolean
		get() = prefs.getBoolean(KEY_TRACKER_NO_NSFW, false)

	val isFeedSwipeGesturesEnabled: Boolean
		get() = prefs.getBoolean(KEY_FEED_SWIPE_GESTURES, true)

	val trackerDownloadStrategy: TrackerDownloadStrategy
		get() = prefs.getEnumValue(KEY_TRACKER_DOWNLOAD, TrackerDownloadStrategy.DISABLED)

	fun consumeLegacyTrackerDownloadStrategy(): Boolean {
		val strategy = trackerDownloadStrategy
		if (strategy != TrackerDownloadStrategy.DISABLED) {
			prefs.edit { remove(KEY_TRACKER_DOWNLOAD) }
		}
		return strategy == TrackerDownloadStrategy.DOWNLOADED
	}

	var notificationSound: Uri
		get() = prefs.getString(KEY_NOTIFICATIONS_SOUND, null)?.toUriOrNull()
			?: Settings.System.DEFAULT_NOTIFICATION_URI
		set(value) = prefs.edit { putString(KEY_NOTIFICATIONS_SOUND, value.toString()) }

	val notificationVibrate: Boolean
		get() = prefs.getBoolean(KEY_NOTIFICATIONS_VIBRATE, false)

	val notificationLight: Boolean
		get() = prefs.getBoolean(KEY_NOTIFICATIONS_LIGHT, true)

	val readerAnimation: ReaderAnimation
		get() = prefs.getEnumValue(KEY_READER_ANIMATION, ReaderAnimation.DEFAULT)

	val readerBackground: ReaderBackground
		get() = prefs.getEnumValue(KEY_READER_BACKGROUND, ReaderBackground.DEFAULT)

	val defaultReaderMode: ReaderMode
		get() = prefs.getEnumValue(KEY_READER_MODE, ReaderMode.STANDARD)

	val isReaderModeDetectionEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_MODE_DETECT, true)

	var isHistoryGroupingEnabled: Boolean
		get() = prefs.getBoolean(KEY_HISTORY_GROUPING, true)
		set(value) = prefs.edit { putBoolean(KEY_HISTORY_GROUPING, value) }

	var isUpdatedGroupingEnabled: Boolean
		get() = prefs.getBoolean(KEY_UPDATED_GROUPING, true)
		set(value) = prefs.edit { putBoolean(KEY_UPDATED_GROUPING, value) }

	// A single on/off toggle now: on = show the read-percentage pill, off = no indicator.
	var progressIndicatorMode: ProgressIndicatorMode
		get() {
			val value = try {
				prefs.getString(KEY_PROGRESS_INDICATORS, null)
			} catch (e: ClassCastException) {
				null
			}
			if (value == null) {
				val legacyEnabled = try {
					prefs.getBoolean(KEY_PROGRESS_INDICATORS, true)
				} catch (e: ClassCastException) {
					true
				}
				return if (legacyEnabled) ProgressIndicatorMode.PERCENT_READ else ProgressIndicatorMode.NONE
			}
			return prefs.getEnumValue(KEY_PROGRESS_INDICATORS, ProgressIndicatorMode.PERCENT_READ)
		}
		set(value) = prefs.edit { putEnumValue(KEY_PROGRESS_INDICATORS, value) }

	var detailsUiMode: DetailsUiMode
		get() = prefs.getEnumValue(KEY_DETAILS_UI, COMPACT)
		set(value) = prefs.edit { putEnumValue(KEY_DETAILS_UI, value) }

	var incognitoModeForNsfw: TriStateOption
		get() = prefs.getEnumValue(KEY_INCOGNITO_NSFW, TriStateOption.ASK)
		set(value) = prefs.edit { putEnumValue(KEY_INCOGNITO_NSFW, value) }

	var isIncognitoModeEnabled: Boolean
		get() = prefs.getBoolean(KEY_INCOGNITO_MODE, false)
		set(value) = prefs.edit { putBoolean(KEY_INCOGNITO_MODE, value) }

	val isReaderMultiTaskEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_MULTITASK, false)

	var isChaptersReverse: Boolean
		get() = prefs.getBoolean(KEY_REVERSE_CHAPTERS, false)
		set(value) = prefs.edit { putBoolean(KEY_REVERSE_CHAPTERS, value) }

	var isChaptersGridView: Boolean
		get() = prefs.getBoolean(KEY_GRID_VIEW_CHAPTERS, false)
		set(value) = prefs.edit { putBoolean(KEY_GRID_VIEW_CHAPTERS, value) }

	val zoomMode: ZoomMode
		get() = prefs.getEnumValue(KEY_ZOOM_MODE, ZoomMode.FIT_CENTER)

	val trackSources: Set<String>
		get() = prefs.getStringSet(KEY_TRACK_SOURCES, null) ?: setOf(TRACK_FAVOURITES)

	var isAppProtectionEnabled: Boolean
		get() = prefs.getBoolean(KEY_PROTECT_APP, false)
		set(value) = prefs.edit { putBoolean(KEY_PROTECT_APP, value) }

	var appProtectionTimeout: AppProtectionTimeout
		get() = prefs.getEnumValue(KEY_PROTECT_APP_TIMEOUT, AppProtectionTimeout.INSTANT)
		set(value) = prefs.edit { putEnumValue(KEY_PROTECT_APP_TIMEOUT, value) }

	val appProtectionTimeoutMillis: Long
		get() = appProtectionTimeout.timeoutMillis

	/** Salted SHA-256 of the custom unlock PIN; null means the lock uses device/biometric auth. */
	val appPasswordHash: String?
		get() = prefs.getString(KEY_APP_PASSWORD, null)

	/** True when the app lock uses a custom PIN rather than device/biometric auth. */
	val isAppPasswordSet: Boolean
		get() = !appPasswordHash.isNullOrEmpty()

	/** Store a new unlock PIN as a salted SHA-256 hash. Never backed up (see [SENSITIVE_BACKUP_KEYS]). */
	fun setAppPassword(pin: String) {
		val salt = UUID.randomUUID().toString()
		prefs.edit {
			putString(KEY_APP_PASSWORD_SALT, salt)
			putString(KEY_APP_PASSWORD, Hash.sha256(salt + pin))
		}
	}

	fun clearAppPassword() = prefs.edit {
		remove(KEY_APP_PASSWORD)
		remove(KEY_APP_PASSWORD_SALT)
	}

	fun verifyAppPassword(pin: String): Boolean {
		val salt = prefs.getString(KEY_APP_PASSWORD_SALT, null) ?: return false
		val hash = prefs.getString(KEY_APP_PASSWORD, null) ?: return false
		return Hash.sha256(salt + pin) == hash
	}

	var pendingExtensionDownloads: Set<Long>
		get() = prefs.getStringSet(KEY_PENDING_EXTENSION_DOWNLOADS, emptySet())
			.orEmpty()
			.mapNotNullToSet { it.toLongOrNull() }
		set(value) = prefs.edit {
			putStringSet(KEY_PENDING_EXTENSION_DOWNLOADS, value.mapToSet { it.toString() })
		}

	/**
	 * Ids of installed novel (text) extension sources, remembered from the last extension scan.
	 * Loading extensions needs a classloader pass, so right after launch a novel already in the
	 * library would otherwise look like a manga source and open in the image reader.
	 */
	var novelSourceIds: Set<Long>
		get() = prefs.getStringSet(KEY_NOVEL_SOURCE_IDS, emptySet())
			.orEmpty()
			.mapNotNullToSet { it.toLongOrNull() }
		set(value) = prefs.edit {
			putStringSet(KEY_NOVEL_SOURCE_IDS, value.mapToSet { it.toString() })
		}

	var isShizukuInstallerEnabled: Boolean
		get() = prefs.getBoolean(KEY_SHIZUKU_INSTALLER, false)
		set(value) = prefs.edit {
			putBoolean(KEY_SHIZUKU_INSTALLER, value)
			if (!value) putBoolean(KEY_AUTO_UPDATE_EXTENSIONS, false)
			if (value) putBoolean(KEY_PRIVATE_INSTALLER, false)
		}

	var isPrivateInstallEnabled: Boolean
		get() = prefs.getBoolean(KEY_PRIVATE_INSTALLER, false)
		set(value) = prefs.edit {
			putBoolean(KEY_PRIVATE_INSTALLER, value)
			if (value) putBoolean(KEY_SHIZUKU_INSTALLER, false)
		}

	var isAutoUpdateExtensionsEnabled: Boolean
		get() = prefs.getBoolean(KEY_AUTO_UPDATE_EXTENSIONS, false)
		set(value) = prefs.edit { putBoolean(KEY_AUTO_UPDATE_EXTENSIONS, value) }

	var isExtensionUpdateNotificationsEnabled: Boolean
		get() = prefs.getBoolean(KEY_EXTENSION_UPDATE_NOTIFICATIONS, true)
		set(value) = prefs.edit { putBoolean(KEY_EXTENSION_UPDATE_NOTIFICATIONS, value) }

	var lastExtensionUpdateNotificationTime: Long
		get() = prefs.getLong(KEY_LAST_EXTENSION_UPDATE_NOTIFICATION_TIME, 0L)
		set(value) = prefs.edit { putLong(KEY_LAST_EXTENSION_UPDATE_NOTIFICATION_TIME, value) }

	val searchSuggestionTypes: Set<SearchSuggestionType>
		get() = prefs.getStringSet(KEY_SEARCH_SUGGESTION_TYPES, null)?.let { stringSet ->
			stringSet.mapNotNullTo(EnumSet.noneOf(SearchSuggestionType::class.java)) { x ->
				enumValueOf<SearchSuggestionType>(x)
			}
		} ?: EnumSet.allOf(SearchSuggestionType::class.java)

	val isExitConfirmationEnabled: Boolean
		get() = prefs.getBoolean(KEY_EXIT_CONFIRM, false)

	val isDynamicShortcutsEnabled: Boolean
		get() = prefs.getBoolean(KEY_SHORTCUTS, true)

	val isPagesTabEnabled: Boolean
		get() = prefs.getBoolean(KEY_PAGES_TAB, true)

	val defaultDetailsTab: Int
		get() = if (isPagesTabEnabled) {
			val raw = prefs.getString(KEY_DETAILS_TAB, null)?.toIntOrNull() ?: -1
			if (raw == -1) {
				lastDetailsTab
			} else {
				raw
			}.coerceIn(0, 2)
		} else {
			0
		}

	var lastDetailsTab: Int
		get() = prefs.getInt(KEY_DETAILS_LAST_TAB, 0)
		set(value) = prefs.edit { putInt(KEY_DETAILS_LAST_TAB, value) }

	val isContentPrefetchEnabled: Boolean
		get() {
			if (isBackgroundNetworkRestricted()) {
				return false
			}
			val policy =
				NetworkPolicy.from(prefs.getString(KEY_PREFETCH_CONTENT, null), NetworkPolicy.NON_METERED)
			return policy.isNetworkAllowed(connectivityManager)
		}

	var sourcesSortOrder: SourcesSortOrder
		get() = prefs.getEnumValue(KEY_SOURCES_ORDER, SourcesSortOrder.ALPHABETIC)
		set(value) = prefs.edit { putEnumValue(KEY_SOURCES_ORDER, value) }

	var isSourcesGridMode: Boolean
		get() = prefs.getBoolean(KEY_SOURCES_GRID, true)
		set(value) = prefs.edit { putBoolean(KEY_SOURCES_GRID, value) }

	/**
	 * The active language chosen per logical source (a package + source-name pair) for
	 * multi-language extensions. Each entry is encoded as "lang\npkgName\nsourceName" (newline
	 * delimited; none of the parts can contain a newline), so a single source collapses its
	 * language variants into one Explore entity and the user picks exactly one active language
	 * for it. Unset sources fall back to the install-time default (app language -> English ->
	 * any), resolved at read time.
	 */
	var mihonPerExtActiveLangs: Set<String>
		get() = prefs.getStringSet(KEY_MIHON_PER_EXT_ACTIVE_LANG, emptySet()).orEmpty()
		set(value) = prefs.edit { putStringSet(KEY_MIHON_PER_EXT_ACTIVE_LANG, value) }

	fun getMihonActiveLang(pkgName: String, sourceName: String): String? {
		val suffix = "\n" + mihonSourceKey(pkgName, sourceName)
		return mihonPerExtActiveLangs.firstOrNull { it.endsWith(suffix) }
			?.substringBefore('\n')
			?.takeIf { it.isNotEmpty() }
	}

	fun setMihonActiveLang(pkgName: String, sourceName: String, lang: String) {
		val suffix = "\n" + mihonSourceKey(pkgName, sourceName)
		mihonPerExtActiveLangs = mihonPerExtActiveLangs.filterNot { it.endsWith(suffix) }.toSet() + (lang + suffix)
	}

	private fun mihonSourceKey(pkgName: String, sourceName: String): String = "$pkgName\n$sourceName"

	/** Package names of extensions hidden from Explore. They remain in the extension manager. */
	var mihonHiddenPackages: Set<String>
		get() = prefs.getStringSet(KEY_MIHON_HIDDEN_PACKAGES, emptySet()).orEmpty()
		set(value) = prefs.edit { putStringSet(KEY_MIHON_HIDDEN_PACKAGES, value) }

	/** LNReader plugin ids hidden from Explore. Mirrors [mihonHiddenPackages] for novel plugins. */
	var lnHiddenPlugins: Set<String>
		get() = prefs.getStringSet(KEY_LN_HIDDEN_PLUGINS, emptySet()).orEmpty()
		set(value) = prefs.edit { putStringSet(KEY_LN_HIDDEN_PLUGINS, value) }

	fun isMihonPackageHidden(pkgName: String): Boolean = pkgName in mihonHiddenPackages

	fun setMihonPackageHidden(pkgName: String, hidden: Boolean) {
		val updated = mihonHiddenPackages.toMutableSet()
		if (hidden) updated.add(pkgName) else updated.remove(pkgName)
		mihonHiddenPackages = updated
	}

	var externalExtensionsRepoUrl: String?
		get() = prefs.getString(KEY_EXTERNAL_EXTENSIONS_REPO_URL, null)?.takeIf { it.isNotBlank() }
		set(value) = prefs.edit {
			if (value.isNullOrBlank()) {
				remove(KEY_EXTERNAL_EXTENSIONS_REPO_URL)
			} else {
				putString(KEY_EXTERNAL_EXTENSIONS_REPO_URL, value.trim())
			}
		}

	// Legacy single-store ownership, read once by ExtensionStoreRegistry migration.
	private val extensionRepoMap: Map<String, String>
		get() = prefs.getStringSet(KEY_MIHON_EXTENSION_REPOS, emptySet()).orEmpty()
			.mapNotNull { entry ->
				val sep = entry.indexOf('\t')
				if (sep <= 0) null else entry.substring(0, sep) to entry.substring(sep + 1)
			}.toMap()

	fun getExtensionRepoUrls(): Map<String, String> = extensionRepoMap

	// Legacy repo metadata, read once by ExtensionStoreRegistry migration.
	val externalRepoInfos: List<ExternalRepoInfo>
		get() = prefs.getString(KEY_MIHON_REPO_INFOS, null)
			?.let { runCatching { json.decodeFromString<List<ExternalRepoInfo>>(it) }.getOrNull() }
			?: emptyList()

	var extensionStoreRegistryState: ExtensionStoreRegistryState
		get() = prefs.getString(KEY_EXTENSION_STORE_REGISTRY, null)
			?.let { runCatching { json.decodeFromString<ExtensionStoreRegistryState>(it) }.getOrNull() }
			?: ExtensionStoreRegistryState()
		set(value) = prefs.edit {
			putString(KEY_EXTENSION_STORE_REGISTRY, json.encodeToString(value))
		}

	var isExtensionStoreMigrationComplete: Boolean
		get() = prefs.getBoolean(KEY_EXTENSION_STORE_MIGRATED, false)
		set(value) = prefs.edit { putBoolean(KEY_EXTENSION_STORE_MIGRATED, value) }

	fun getExtensionStoreLabel(packageName: String, signatures: Collection<String>): String? {
		val state = extensionStoreRegistryState
		val mode = if (isPrivateInstallEnabled) ExtensionInstallMode.SANDBOX else ExtensionInstallMode.SYSTEM
		state.ownerships.firstOrNull { it.mode == mode && it.packageName == packageName }
			?.storeId
			?.let { id -> state.stores.firstOrNull { it.id == id } }
			?.let { return it.displayName }
		return state.stores.filter { store ->
			store.fingerprint?.let { fingerprint ->
				signatures.any { it.equals(fingerprint, ignoreCase = true) }
			} == true
		}.singleOrNull()?.displayName
	}


	val isPagesNumbersEnabled: Boolean
		get() = prefs.getBoolean(KEY_PAGES_NUMBERS, false)

	val screenshotsPolicy: ScreenshotsPolicy
		get() = prefs.getEnumValue(KEY_SCREENSHOTS_POLICY, ScreenshotsPolicy.ALLOW)

	val isAdBlockEnabled: Boolean
		get() = prefs.getBoolean(KEY_ADBLOCK, false)

	var userSpecifiedMangaDirectories: Set<File>
		get() {
			val set = prefs.getStringSet(KEY_LOCAL_MANGA_DIRS, emptySet()).orEmpty()
			return set.mapNotNullToSet { File(it).takeIfReadable() }
		}
		set(value) {
			val set = value.mapToSet { it.absolutePath }
			prefs.edit { putStringSet(KEY_LOCAL_MANGA_DIRS, set) }
		}

	var mangaStorageDir: File?
		get() = prefs.getString(KEY_LOCAL_STORAGE, null)?.let {
			File(it)
		}?.takeIf { it.exists() && it in userSpecifiedMangaDirectories }
		set(value) = prefs.edit {
			if (value == null) {
				remove(KEY_LOCAL_STORAGE)
			} else {
				val userDirs = userSpecifiedMangaDirectories
				if (value !in userDirs) {
					userSpecifiedMangaDirectories = userDirs + value
				}
				putString(KEY_LOCAL_STORAGE, value.path)
			}
		}

	var allowDownloadOnMeteredNetwork: TriStateOption
		get() = prefs.getEnumValue(KEY_DOWNLOADS_METERED_NETWORK, TriStateOption.ASK)
		set(value) = prefs.edit { putEnumValue(KEY_DOWNLOADS_METERED_NETWORK, value) }

	val preferredDownloadFormat: DownloadFormat
		get() = prefs.getEnumValue(KEY_DOWNLOADS_FORMAT, DownloadFormat.AUTOMATIC)

	var isSuggestionsEnabled: Boolean
		get() = prefs.getBoolean(KEY_SUGGESTIONS, false)
		set(value) = prefs.edit { putBoolean(KEY_SUGGESTIONS, value) }

	val isSuggestionsWiFiOnly: Boolean
		get() = prefs.getBoolean(KEY_SUGGESTIONS_WIFI_ONLY, false)

	val isSuggestionsExcludeNsfw: Boolean
		get() = prefs.getBoolean(KEY_SUGGESTIONS_EXCLUDE_NSFW, false)

	val isSuggestionsNotificationAvailable: Boolean
		get() = prefs.getBoolean(KEY_SUGGESTIONS_NOTIFICATIONS, false)

	val suggestionsTagsBlacklist: Set<String>
		get() {
			val string = prefs.getString(KEY_SUGGESTIONS_EXCLUDE_TAGS, null)?.trimEnd(' ', ',')
			if (string.isNullOrEmpty()) {
				return emptySet()
			}
			return string.split(',').mapToSet { it.trim() }
		}

	val isReaderBarEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_BAR, true)

	val isReaderBarTransparent: Boolean
		get() = prefs.getBoolean(KEY_READER_BAR_TRANSPARENT, true)

	val isReaderChapterToastEnabled: Boolean
		get() = prefs.getBoolean(KEY_READER_CHAPTER_TOAST, true)

	val isReaderKeepScreenOn: Boolean
		get() = prefs.getBoolean(KEY_READER_SCREEN_ON, true)

	var readerColorFilter: ReaderColorFilter?
		get() = runCatching {
			ReaderColorFilter(
				brightness = prefs.getFloat(KEY_CF_BRIGHTNESS, ReaderColorFilter.EMPTY.brightness),
				contrast = prefs.getFloat(KEY_CF_CONTRAST, ReaderColorFilter.EMPTY.contrast),
				isInverted = prefs.getBoolean(KEY_CF_INVERTED, ReaderColorFilter.EMPTY.isInverted),
				isGrayscale = prefs.getBoolean(KEY_CF_GRAYSCALE, ReaderColorFilter.EMPTY.isGrayscale),
				isBookBackground = prefs.getBoolean(KEY_CF_BOOK, ReaderColorFilter.EMPTY.isBookBackground),
			).takeUnless { it.isEmpty }
		}.getOrNull()
		set(value) {
			prefs.edit {
				if (value != null) {
					putFloat(KEY_CF_BRIGHTNESS, value.brightness)
					putFloat(KEY_CF_CONTRAST, value.contrast)
					putBoolean(KEY_CF_INVERTED, value.isInverted)
					putBoolean(KEY_CF_GRAYSCALE, value.isGrayscale)
					putBoolean(KEY_CF_BOOK, value.isBookBackground)
				} else {
					remove(KEY_CF_BRIGHTNESS)
					remove(KEY_CF_CONTRAST)
					remove(KEY_CF_INVERTED)
					remove(KEY_CF_GRAYSCALE)
					remove(KEY_CF_BOOK)
				}
			}
		}

	val imagesProxy: Int
		get() {
			val raw = prefs.getString(KEY_IMAGES_PROXY, null)?.toIntOrNull()
			return raw ?: if (prefs.getBoolean(KEY_IMAGES_PROXY_OLD, false)) 0 else -1
		}

	val dnsOverHttps: DoHProvider
		get() = prefs.getEnumValue(KEY_DOH, DoHProvider.NONE)

	/**
	 * User-supplied override for the User-Agent header sent by Mihon/Tachiyomi extensions.
	 * `null` when the user hasn't set one, in which case the extension layer falls back to the
	 * device WebView's User-Agent (so it matches the UA that solves Cloudflare challenges) and
	 * finally to [DEFAULT_MIHON_USER_AGENT]. Mirrors Mihon's "Default user agent string" option.
	 */
	val mihonUserAgentOverride: String?
		get() = prefs.getString(KEY_MIHON_USER_AGENT, null)
			?.trim()
			?.takeIf { it.isNotEmpty() }

	var isSSLBypassEnabled: Boolean
		get() = prefs.getBoolean(KEY_SSL_BYPASS, false)
		set(value) = prefs.edit { putBoolean(KEY_SSL_BYPASS, value) }

	val proxyType: Proxy.Type
		get() {
			val raw = prefs.getString(KEY_PROXY_TYPE, null) ?: return Proxy.Type.DIRECT
			return enumValues<Proxy.Type>().find { it.name == raw } ?: Proxy.Type.DIRECT
		}

	val proxyAddress: String?
		get() = prefs.getString(KEY_PROXY_ADDRESS, null)

	val proxyPort: Int
		get() = prefs.getString(KEY_PROXY_PORT, null)?.toIntOrNull() ?: 0

	val proxyLogin: String?
		get() = prefs.getString(KEY_PROXY_LOGIN, null)?.nullIfEmpty()

	val proxyPassword: String?
		get() = prefs.getString(KEY_PROXY_PASSWORD, null)?.nullIfEmpty()

	var localListOrder: SortOrder
		get() = prefs.getEnumValue(KEY_LOCAL_LIST_ORDER, SortOrder.NEWEST)
		set(value) = prefs.edit { putEnumValue(KEY_LOCAL_LIST_ORDER, value) }

	var historySortOrder: ListSortOrder
		get() = prefs.getEnumValue(KEY_HISTORY_ORDER, ListSortOrder.LAST_READ)
		set(value) = prefs.edit { putEnumValue(KEY_HISTORY_ORDER, value) }

	var allFavoritesSortOrder: ListSortOrder
		get() = prefs.getEnumValue(KEY_FAVORITES_ORDER, ListSortOrder.NEWEST)
		set(value) = prefs.edit { putEnumValue(KEY_FAVORITES_ORDER, value) }

	// comma-joined in pin order, oldest pin first
	fun getPinnedFavourites(categoryId: Long): List<Long> =
		prefs.getString(KEY_FAVORITES_PINNED + categoryId, null)
			?.split(',')
			?.mapNotNull { it.toLongOrNull() }
			.orEmpty()

	fun setPinnedFavourites(categoryId: Long, ids: List<Long>) {
		prefs.edit { putString(KEY_FAVORITES_PINNED + categoryId, ids.joinToString(",")) }
	}

	val isRelatedMangaEnabled: Boolean
		get() = prefs.getBoolean(KEY_RELATED_MANGA, true)

	val isWebtoonZoomEnabled: Boolean
		get() = prefs.getBoolean(KEY_WEBTOON_ZOOM, true)

	var isWebtoonGapsEnabled: Boolean
		get() = prefs.getBoolean(KEY_WEBTOON_GAPS, false)
		set(value) = prefs.edit { putBoolean(KEY_WEBTOON_GAPS, value) }

	var isWebtoonPullGestureEnabled: Boolean
		get() = prefs.getBoolean(KEY_WEBTOON_PULL_GESTURE, false)
		set(value) = prefs.edit { putBoolean(KEY_WEBTOON_PULL_GESTURE, value) }

	@get:FloatRange(from = 0.0, to = 0.5)
	val defaultWebtoonZoomOut: Float
		get() = prefs.getInt(KEY_WEBTOON_ZOOM_OUT, 0).coerceIn(0, 50) / 100f

	@get:FloatRange(from = 0.0, to = 1.0)
	var readerAutoscrollSpeed: Float
		get() = prefs.getFloat(KEY_READER_AUTOSCROLL_SPEED, 0f)
		set(@FloatRange(from = 0.0, to = 1.0) value) = prefs.edit {
			putFloat(
				KEY_READER_AUTOSCROLL_SPEED,
				value,
			)
		}

	var isReaderAutoscrollFabVisible: Boolean
		get() = prefs.getBoolean(KEY_READER_AUTOSCROLL_FAB, true)
		set(value) = prefs.edit { putBoolean(KEY_READER_AUTOSCROLL_FAB, value) }

	val isPagesPreloadEnabled: Boolean
		get() {
			if (isBackgroundNetworkRestricted()) {
				return false
			}
			val policy = NetworkPolicy.from(
				prefs.getString(KEY_PAGES_PRELOAD, null),
				NetworkPolicy.NON_METERED,
			)
			return policy.isNetworkAllowed(connectivityManager)
		}

	val is32BitColorsEnabled: Boolean
		get() = prefs.getBoolean(KEY_32BIT_COLOR, false)

	val isDiscordRpcEnabled: Boolean
		get() = prefs.getBoolean(KEY_DISCORD_RPC, false)

	val isDiscordRpcSkipNsfw: Boolean
		get() = prefs.getBoolean(KEY_DISCORD_RPC_SKIP_NSFW, false)

	var discordToken: String?
		get() = prefs.getString(KEY_DISCORD_TOKEN, null)?.trim()?.nullIfEmpty()
		set(value) = prefs.edit { putString(KEY_DISCORD_TOKEN, value?.nullIfEmpty()) }

	var isVerboseLoggingEnabled: Boolean
		get() = prefs.getBoolean(KEY_VERBOSE_LOGGING, false)
		set(value) = prefs.edit { putBoolean(KEY_VERBOSE_LOGGING, value) }

	val isReadingTimeEstimationEnabled: Boolean
		get() = prefs.getBoolean(KEY_READING_TIME, true)

	val isPagesSavingAskEnabled: Boolean
		get() = prefs.getBoolean(KEY_PAGES_SAVE_ASK, true)

	val isStatsEnabled: Boolean
		get() = prefs.getBoolean(KEY_STATS_ENABLED, true)

	val isAutoLocalChaptersCleanupEnabled: Boolean
		get() = prefs.getBoolean(KEY_CHAPTERS_CLEAR_AUTO, false)

	fun isPagesCropEnabled(mode: ReaderMode): Boolean {
		val rawValue = prefs.getStringSet(KEY_READER_CROP, emptySet())
		if (rawValue.isNullOrEmpty()) {
			return false
		}
		val needle = if (mode == ReaderMode.WEBTOON) READER_CROP_WEBTOON else READER_CROP_PAGED
		return needle.toString() in rawValue
	}

	fun isTipEnabled(tip: String): Boolean {
		return prefs.getStringSet(KEY_TIPS_CLOSED, emptySet())?.contains(tip) != true
	}

	fun closeTip(tip: String) {
		val closedTips = prefs.getStringSet(KEY_TIPS_CLOSED, emptySet()).orEmpty()
		if (tip in closedTips) {
			return
		}
		prefs.edit { putStringSet(KEY_TIPS_CLOSED, closedTips + tip) }
	}

	fun isIncognitoModeEnabled(isNsfw: Boolean): Boolean {
		return isIncognitoModeEnabled || (isNsfw && incognitoModeForNsfw == TriStateOption.ENABLED)
	}

	fun getPagesSaveDir(context: Context): DocumentFile? =
		prefs.getString(KEY_PAGES_SAVE_DIR, null)?.toUriOrNull()?.let {
			DocumentFile.fromTreeUri(context, it)?.takeIf { it.canWrite() }
		}

	val isPeriodicalBackupEnabled: Boolean
		get() = prefs.getBoolean(KEY_BACKUP_PERIODICAL_ENABLED, false)

	var periodicalBackupDirectory: Uri?
		get() = prefs.getString(KEY_BACKUP_PERIODICAL_OUTPUT, null)?.toUriOrNull()
		set(value) = prefs.edit {
			if (value != null) putString(KEY_BACKUP_PERIODICAL_OUTPUT, value.toString())
			else remove(KEY_BACKUP_PERIODICAL_OUTPUT)
		}

	val periodicalBackupFrequencyMillis: Long
		get() {
			if (!isPeriodicalBackupEnabled) return 0L
			return when (prefs.getString(KEY_BACKUP_PERIODICAL_FREQ, "3")?.toIntOrNull() ?: 3) {
				0 -> 6 * 60 * 60 * 1000L
				1 -> 24 * 60 * 60 * 1000L
				2 -> 2 * 24 * 60 * 60 * 1000L
				3 -> 7 * 24 * 60 * 60 * 1000L
				4 -> 14 * 24 * 60 * 60 * 1000L
				5 -> 30 * 24 * 60 * 60 * 1000L
				else -> 7 * 24 * 60 * 60 * 1000L
			}
		}

	val periodicalBackupMaxCount: Int
		get() {
			if (!prefs.getBoolean(KEY_BACKUP_PERIODICAL_TRIM, true)) return Int.MAX_VALUE
			return prefs.getInt(KEY_BACKUP_PERIODICAL_COUNT, 10).coerceAtLeast(1)
		}

	fun setPagesSaveDir(uri: Uri?) {
		prefs.edit { putString(KEY_PAGES_SAVE_DIR, uri?.toString()) }
	}

	fun getMangaListBadges(): Int {
		val raw = prefs.getStringSet(KEY_MANGA_LIST_BADGES, mangaListBadgesDefault).orEmpty()
		var result = 0
		for (item in raw) {
			result = result or item.toInt()
		}
		return result
	}

	fun subscribe(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
		prefs.registerOnSharedPreferenceChangeListener(listener)
	}

	fun unsubscribe(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
		prefs.unregisterOnSharedPreferenceChangeListener(listener)
	}

	fun observeChanges() = prefs.observeChanges()

	fun observe(vararg keys: String): Flow<String?> = prefs.observeChanges()
		.filter { key -> key == null || key in keys }
		.onStart { emit(null) }
		.flowOn(Dispatchers.IO)

	fun getAllValues(): Map<String, *> = prefs.all

	// NOTE: intentionally does NOT clear() existing prefs. A restore should merge the backed-up
	// values over the current ones, not wipe everything first — clearing dropped local-only prefs
	// (e.g. the per-install onboarding state) and sent the app back to the welcome screen.
	fun upsertAll(m: Map<String, *>) = prefs.edit {
		putAll(m)
	}

	private fun isBackgroundNetworkRestricted(): Boolean {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			connectivityManager.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
		} else {
			false
		}
	}

	private fun migrateBackdropBlur() {
		val oldKey = "details_backdrop_blur"
		if (!prefs.contains(oldKey)) return
		prefs.edit {
			if (!prefs.contains(KEY_DETAILS_BACKDROP_BLUR_AMOUNT))
				putInt(KEY_DETAILS_BACKDROP_BLUR_AMOUNT, if (prefs.getBoolean(oldKey, true)) 60 else 0)
			remove(oldKey)
		}
	}

	init {
		migrateBackdropBlur()
	}

	companion object {

		/**
		 * Default User-Agent for Mihon extensions. Kept in sync with Mihon's own default so that
		 * sources gated behind UA-based anti-bot checks (e.g. Kagane) behave identically.
		 */
		const val DEFAULT_MIHON_USER_AGENT =
			"Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"

		const val TRACK_HISTORY = "history"
		const val TRACK_FAVOURITES = "favourites"

		const val KEY_ADBLOCK = "adblock"
		const val KEY_LIST_MODE = "list_mode_2"
		const val KEY_TITLE_OVER_COVER = "title_over_cover"
		const val KEY_TITLE_TAP_TO_READ = "title_tap_to_read"
		const val KEY_GRID_SPACING_INCREASED = "grid_spacing_increased"
		const val KEY_LIST_MODE_HISTORY = "list_mode_history"
		const val KEY_LIST_MODE_FAVORITES = "list_mode_favorites"
		const val KEY_LIST_MODE_SUGGESTIONS = "list_mode_suggestions"
		const val KEY_THEME = "theme"
		const val KEY_COLOR_THEME = "color_theme"
		const val KEY_THEME_AMOLED = "amoled_theme"
		const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"
		const val KEY_HIDE_STATUS_BAR = "hide_status_bar"
		const val KEY_OFFLINE_DISABLED = "no_offline"
		const val KEY_PAGES_CACHE_CLEAR = "pages_cache_clear"
		const val KEY_HTTP_CACHE_CLEAR = "http_cache_clear"
		const val KEY_COOKIES_CLEAR = "cookies_clear"
		const val KEY_CHAPTERS_CLEAR = "chapters_clear"
		const val KEY_CHAPTERS_CLEAR_AUTO = "chapters_clear_auto"
		const val KEY_THUMBS_CACHE_CLEAR = "thumbs_cache_clear"
		const val KEY_SEARCH_HISTORY_CLEAR = "search_history_clear"
		const val KEY_UPDATES_FEED_CLEAR = "updates_feed_clear"
		const val KEY_GRID_SIZE = "grid_size"
		const val KEY_UI_SCALE = "ui_scale"
		const val KEY_GRID_SIZE_PAGES = "grid_size_pages"
		const val KEY_LOCAL_STORAGE = "local_storage"
		const val KEY_READER_DOUBLE_PAGES = "reader_double_pages"
		const val KEY_READER_DOUBLE_PAGES_SENSITIVITY = "reader_double_pages_sensitivity_2"
		const val KEY_READER_DOUBLE_FOLDABLE = "reader_double_foldable"
		const val KEY_READER_ZOOM_BUTTONS = "reader_zoom_buttons"
		const val KEY_READER_CONTROL_LTR = "reader_taps_ltr"
		const val KEY_READER_NAVIGATION_INVERTED = "reader_navigation_inverted"
		const val KEY_READER_FULLSCREEN = "reader_fullscreen"
		const val KEY_READER_VOLUME_BUTTONS = "reader_volume_buttons"
		const val KEY_READER_ORIENTATION = "reader_orientation"
		const val KEY_TRACKER_ENABLED = "tracker_enabled"
		const val KEY_TRACKER_WIFI_ONLY = "tracker_wifi"
		const val KEY_TRACKER_FREQUENCY = "tracker_freq"
		const val KEY_TRACK_SOURCES = "track_sources"
		const val KEY_TRACKER_NOTIFICATIONS = "tracker_notifications"
		const val KEY_TRACKER_NO_NSFW = "tracker_no_nsfw"
		const val KEY_FEED_SWIPE_GESTURES = "feed_swipe_gestures"
		const val KEY_TRACKER_DOWNLOAD = "tracker_download"
		const val KEY_NOTIFICATIONS_SOUND = "notifications_sound"
		const val KEY_NOTIFICATIONS_VIBRATE = "notifications_vibrate"
		const val KEY_NOTIFICATIONS_LIGHT = "notifications_light"
		const val KEY_READER_ANIMATION = "reader_animation2"
		const val KEY_READER_CONTROLS = "reader_controls"
		const val KEY_READER_MODE = "reader_mode"
		const val KEY_EPUB_FONT_SIZE = "epub_font_size"
		const val KEY_EPUB_FONT_FAMILY = "epub_font_family"
		const val KEY_EPUB_LINE_HEIGHT = "epub_line_height"
		const val KEY_EPUB_PARAGRAPH_SPACING = "epub_paragraph_spacing"
		const val KEY_EPUB_HORIZONTAL_PADDING = "epub_horizontal_padding"
		const val KEY_EPUB_VERTICAL_PADDING = "epub_vertical_padding"
		const val KEY_EPUB_TEXT_ALIGN = "epub_text_align"
		const val KEY_EPUB_READING_MODE = "epub_reading_mode"
		const val KEY_EPUB_PAGED_TAP_GESTURES = "epub_paged_tap_gestures"
		const val KEY_EPUB_PUBLISHER_STYLE = "epub_publisher_style"
		const val KEY_EPUB_THEME = "epub_theme"
		const val KEY_EPUB_CUSTOM_BACKGROUND_COLOR = "epub_custom_background_color"
		const val KEY_EPUB_CUSTOM_TEXT_COLOR = "epub_custom_text_color"
		const val KEY_EPUB_CUSTOM_HIGHLIGHT_COLOR = "epub_custom_highlight_color"
		const val KEY_EPUB_CUSTOM_FONT_NAME = "epub_custom_font_name"
		const val KEY_EPUB_CUSTOM_FONT_REVISION = "epub_custom_font_revision"
		const val EPUB_CUSTOM_FONT_FILE = "epub_custom_font"
		const val KEY_READER_MODE_DETECT = "reader_mode_detect"
		const val KEY_READER_CROP = "reader_crop"
		const val KEY_PROTECT_APP = "protect_app"
		const val KEY_PROTECT_APP_TIMEOUT = "protect_app_timeout"
		const val KEY_APP_PASSWORD = "app_password"
		const val KEY_APP_PASSWORD_SALT = "app_password_salt"
		private const val KEY_APP_PASSWORD_NUMERIC_OLD = "app_password_num"
		private const val KEY_PROTECT_APP_BIOMETRIC_OLD = "protect_app_bio"
		const val KEY_ZOOM_MODE = "zoom_mode"
		const val KEY_HISTORY_GROUPING = "history_grouping"
		const val KEY_UPDATED_GROUPING = "updated_grouping"
		const val KEY_PROGRESS_INDICATORS = "reading_indicator_enabled"
		const val KEY_DETAILS_UI = "details_ui"
		const val KEY_REVERSE_CHAPTERS = "reverse_chapters"
		const val KEY_GRID_VIEW_CHAPTERS = "grid_view_chapters"
		const val KEY_INCOGNITO_NSFW = "incognito_nsfw"
		const val KEY_PAGES_NUMBERS = "pages_numbers"
		const val KEY_SCREENSHOTS_POLICY = "screenshots_policy"
		const val KEY_PAGES_PRELOAD = "pages_preload"
		const val KEY_SUGGESTIONS = "suggestions"
		const val KEY_SUGGESTIONS_WIFI_ONLY = "suggestions_wifi"
		const val KEY_SUGGESTIONS_EXCLUDE_NSFW = "suggestions_exclude_nsfw"
		const val KEY_SUGGESTIONS_EXCLUDE_TAGS = "suggestions_exclude_tags"
		const val KEY_SUGGESTIONS_NOTIFICATIONS = "suggestions_notifications"
		const val KEY_DOWNLOADS_METERED_NETWORK = "downloads_metered_network"
		const val KEY_DOWNLOADS_FORMAT = "downloads_format"
		const val KEY_ALL_FAVOURITES_VISIBLE = "all_favourites_visible"
		const val KEY_DOH = "doh"
		const val KEY_MIHON_USER_AGENT = "mihon_user_agent"
		const val KEY_EXIT_CONFIRM = "exit_confirm"
		const val KEY_INCOGNITO_MODE = "incognito"
		const val KEY_READER_MULTITASK = "reader_multitask"
		const val KEY_READER_BAR = "reader_bar"
		const val KEY_READER_BAR_TRANSPARENT = "reader_bar_transparent"
		const val KEY_READER_CHAPTER_TOAST = "reader_chapter_toast"
		const val KEY_CHAPTER_JUMP_DIALOG = "chapter_jump_dialog"
		const val KEY_READER_BACKGROUND = "reader_background"
		const val KEY_READER_SCREEN_ON = "reader_screen_on"
		const val KEY_SHORTCUTS = "dynamic_shortcuts"
		const val KEY_READER_OPTIMIZE = "reader_optimize"
		const val KEY_READER_UPSCALE = "reader_upscale"
		const val KEY_LOCAL_LIST_ORDER = "local_order"
		const val KEY_HISTORY_ORDER = "history_order"
		const val KEY_FAVORITES_ORDER = "fav_order"
		const val KEY_FAVORITES_PINNED = "fav_pinned_order_"
		const val KEY_WEBTOON_GAPS = "webtoon_gaps"
		const val KEY_WEBTOON_ZOOM = "webtoon_zoom"
		const val KEY_WEBTOON_ZOOM_OUT = "webtoon_zoom_out"
		const val KEY_WEBTOON_PULL_GESTURE = "webtoon_pull_gesture"
		const val KEY_PREFETCH_CONTENT = "prefetch_content"
		const val KEY_APP_LOCALE = "app_locale"
		const val KEY_SOURCES_GRID = "sources_grid"
		const val KEY_TIPS_CLOSED = "tips_closed"
		const val KEY_SSL_BYPASS = "ssl_bypass"
		const val KEY_READER_AUTOSCROLL_SPEED = "as_speed"
		const val KEY_READER_AUTOSCROLL_FAB = "as_fab"
		const val KEY_PROXY_TYPE = "proxy_type_2"
		const val KEY_PROXY_ADDRESS = "proxy_address"
		const val KEY_PROXY_PORT = "proxy_port"
		const val KEY_PROXY_LOGIN = "proxy_login"
		const val KEY_PROXY_PASSWORD = "proxy_password"
		const val KEY_IMAGES_PROXY = "images_proxy_2"
		const val KEY_LOCAL_MANGA_DIRS = "local_manga_dirs"
		const val KEY_DISABLE_NSFW = "no_nsfw"
		const val KEY_RELATED_MANGA = "related_manga"
		const val KEY_NAV_MAIN = "nav_main"
		const val KEY_NAV_LABELS = "nav_labels"
		const val KEY_NAV_PINNED = "nav_pinned"
		const val KEY_NAV_LEGACY = "nav_legacy"
		const val KEY_MAIN_FAB = "main_fab"
		const val KEY_32BIT_COLOR = "enhanced_colors"
		const val KEY_SOURCES_ORDER = "sources_sort_order"
		const val KEY_MIHON_PER_EXT_ACTIVE_LANG = "mihon_per_ext_active_lang"
		const val KEY_MIHON_HIDDEN_PACKAGES = "mihon_hidden_packages"
		const val KEY_LN_HIDDEN_PLUGINS = "ln_hidden_plugins"
		const val KEY_EXTERNAL_EXTENSIONS_REPO_URL = "external_extensions_repo_url"
		const val KEY_MIHON_EXTENSION_REPOS = "mihon_extension_repos"
		const val KEY_MIHON_REPO_INFOS = "mihon_repo_infos"
		const val KEY_EXTENSION_STORE_REGISTRY = "extension_store_registry"
		const val KEY_EXTENSION_STORE_MIGRATED = "extension_store_migrated"
		const val KEY_CF_BRIGHTNESS = "cf_brightness"
		const val KEY_CF_CONTRAST = "cf_contrast"
		const val KEY_CF_INVERTED = "cf_inverted"
		const val KEY_CF_GRAYSCALE = "cf_grayscale"
		const val KEY_CF_BOOK = "cf_book"
		const val KEY_PAGES_TAB = "pages_tab"
		const val KEY_DETAILS_TAB = "details_tab"
		const val KEY_DETAILS_LAST_TAB = "details_last_tab"
		const val KEY_DETAILS_BACKDROP = "details_backdrop"
		const val KEY_DETAILS_BACKDROP_BLUR_AMOUNT = "details_backdrop_blur_amount"
		const val KEY_VERBOSE_LOGGING = "verbose_logging"
		const val KEY_READING_TIME = "reading_time"
		const val KEY_PAGES_SAVE_DIR = "pages_dir"
		const val KEY_PAGES_SAVE_ASK = "pages_dir_ask"
		const val KEY_STATS_ENABLED = "stats_on"
		const val KEY_SEARCH_SUGGESTION_TYPES = "search_suggest_types"
		const val KEY_QUICK_FILTER = "quick_filter"
		const val KEY_COLLAPSE_DESCRIPTION = "description_collapse"
		const val KEY_MANGA_LIST_BADGES = "manga_list_badges"
		const val KEY_PENDING_EXTENSION_DOWNLOADS = "pending_extension_downloads"
		const val KEY_NOVEL_SOURCE_IDS = "novel_source_ids"
		const val KEY_SHIZUKU_INSTALLER = "shizuku_installer"
		const val KEY_PRIVATE_INSTALLER = "private_installer"
		const val KEY_AUTO_UPDATE_EXTENSIONS = "auto_update_extensions"
		const val KEY_EXTENSION_UPDATE_NOTIFICATIONS = "extension_update_notifications"
		const val KEY_LAST_EXTENSION_UPDATE_NOTIFICATION_TIME = "last_extension_update_notification_time"
		const val KEY_TAGS_WARNINGS = "tags_warnings"
		const val KEY_DISCORD_RPC = "discord_rpc"
		const val KEY_DISCORD_RPC_SKIP_NSFW = "discord_rpc_skip_nsfw"
		const val KEY_DISCORD_TOKEN = "discord_token"
		const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
		const val KEY_ONBOARDING_INSTALL_ID = "onboarding_install_id"

		/**
		 * Keys that must never leave the device: credentials, app-lock state and per-install ids.
		 * Stripped from local backups and cloud sync, both when writing and when applying.
		 */
		val SENSITIVE_BACKUP_KEYS = setOf(
			KEY_APP_PASSWORD,
			KEY_APP_PASSWORD_SALT,
			KEY_APP_PASSWORD_NUMERIC_OLD,
			KEY_PROTECT_APP,
			KEY_PROTECT_APP_TIMEOUT,
			KEY_PROTECT_APP_BIOMETRIC_OLD,
			KEY_PROXY_PASSWORD,
			KEY_PROXY_LOGIN,
			KEY_INCOGNITO_MODE,
			KEY_ONBOARDING_COMPLETED,
			KEY_ONBOARDING_INSTALL_ID,
		)

		// keys for non-persistent preferences
		const val KEY_APP_VERSION = "app_version"
		const val KEY_HANDLE_LINKS = "handle_links"
		const val KEY_CLEAR_MANGA_DATA = "manga_data_clear"
		const val KEY_WEBVIEW_CLEAR = "webview_clear"
		const val KEY_BACKUP_PERIODICAL_ENABLED = "backup_periodic"
		const val KEY_BACKUP_PERIODICAL_OUTPUT = "backup_periodic_output"
		const val KEY_BACKUP_PERIODICAL_FREQ = "backup_periodic_freq"
		const val KEY_BACKUP_PERIODICAL_TRIM = "backup_periodic_trim"
		const val KEY_BACKUP_PERIODICAL_COUNT = "backup_periodic_count"

		// old keys are for migration only
		private const val KEY_IMAGES_PROXY_OLD = "images_proxy"

		// values
		private const val READER_CROP_PAGED = 1
		private const val READER_CROP_WEBTOON = 2
	}
}
