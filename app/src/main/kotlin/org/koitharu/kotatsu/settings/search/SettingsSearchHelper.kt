package org.koitharu.kotatsu.settings.search

import android.annotation.SuppressLint
import android.content.Context
import androidx.fragment.app.Fragment
import dagger.Reusable
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.settings.AppearanceSettingsFragment
import org.koitharu.kotatsu.settings.BackupSettingsFragment
import org.koitharu.kotatsu.settings.DownloadsSettingsFragment
import org.koitharu.kotatsu.settings.ProxySettingsFragment
import org.koitharu.kotatsu.settings.ReaderSettingsFragment
import org.koitharu.kotatsu.settings.ServicesSettingsFragment
import org.koitharu.kotatsu.settings.StorageAndNetworkSettingsFragment
import org.koitharu.kotatsu.settings.SuggestionsSettingsFragment
import org.koitharu.kotatsu.settings.NotificationSettingsLegacyFragment
import org.koitharu.kotatsu.settings.about.AboutSettingsFragment
import org.koitharu.kotatsu.settings.appearance.PreviewSettingsFragment
import org.koitharu.kotatsu.settings.discord.DiscordSettingsFragment
import org.koitharu.kotatsu.settings.sources.ExtensionsSettingsFragment
import org.koitharu.kotatsu.settings.tracker.TrackerSettingsFragment
import org.koitharu.kotatsu.settings.userdata.storage.DataCleanupSettingsFragment
import org.koitharu.kotatsu.sync.ui.SyncSettingsFragment
import javax.inject.Inject

@Reusable
@SuppressLint("RestrictedApi")
class SettingsSearchHelper @Inject constructor(
	@LocalizedAppContext private val context: Context,
) {

	fun inflatePreferences(): List<SettingsItem> {
		val result = ArrayList<SettingsItem>()
		val ctx = context

		fun addItem(
			key: String,
			titleRes: Int,
			summaryRes: Int? = null,
			breadcrumbs: List<String>,
			fragmentClass: Class<out Fragment>,
			keywordRes: IntArray = IntArray(0),
			keywordArrayRes: IntArray = IntArray(0),
			keywordText: List<String> = emptyList(),
		) {
			val title = ctx.getString(titleRes)
			val summary = summaryRes?.let(ctx::getString).orEmpty()
			val keywords = keywordRes.map(ctx::getString) + keywordArrayRes.flatMap { ctx.resources.getStringArray(it).asList() } + keywordText
			val searchText = buildSearchText(title, key, summary, breadcrumbs, keywords)
			result.add(
				SettingsItem(
					key = key,
					title = title,
					breadcrumbs = breadcrumbs,
					searchText = searchText,
					fragmentClass = fragmentClass,
				),
			)
		}

		fun section(
			titleRes: Int,
			fragmentClass: Class<out Fragment>,
			block: (List<String>) -> Unit,
		) {
			val title = ctx.getString(titleRes)
			block(listOf(title))
		}

		fun group(sectionCrumbs: List<String>, groupTitle: String, block: (List<String>) -> Unit) {
			block(sectionCrumbs + groupTitle)
		}

		section(R.string.appearance, AppearanceSettingsFragment::class.java) { sectionCrumbs ->
			group(sectionCrumbs, "Theme") { crumbs ->
				addItem(AppSettings.KEY_COLOR_THEME, R.string.color_theme, breadcrumbs = crumbs, fragmentClass = AppearanceSettingsFragment::class.java)
				addItem(AppSettings.KEY_THEME, R.string.theme, breadcrumbs = crumbs, fragmentClass = AppearanceSettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.themes))
				addItem(AppSettings.KEY_THEME_AMOLED, R.string.black_dark_theme, R.string.black_dark_theme_summary, crumbs, AppearanceSettingsFragment::class.java)
				addItem(AppSettings.KEY_APP_LOCALE, R.string.language, breadcrumbs = crumbs, fragmentClass = AppearanceSettingsFragment::class.java)
				addItem(AppSettings.KEY_HIDE_STATUS_BAR, R.string.hide_status_bar, R.string.hide_status_bar_summary, crumbs, AppearanceSettingsFragment::class.java)
			}
			group(sectionCrumbs, ctx.getString(R.string.manga_list)) { crumbs ->
				addItem(AppSettings.KEY_LIST_MODE, R.string.list_mode, breadcrumbs = crumbs, fragmentClass = AppearanceSettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.list_modes))
				addItem(AppSettings.KEY_GRID_SIZE, R.string.grid_size, breadcrumbs = crumbs, fragmentClass = AppearanceSettingsFragment::class.java)
				addItem(AppSettings.KEY_QUICK_FILTER, R.string.show_quick_filters, R.string.show_quick_filters_summary, crumbs, AppearanceSettingsFragment::class.java)
				addItem(AppSettings.KEY_PROGRESS_INDICATORS, R.string.show_reading_indicators, breadcrumbs = crumbs, fragmentClass = AppearanceSettingsFragment::class.java)
				addItem(AppSettings.KEY_MANGA_LIST_BADGES, R.string.badges_in_lists, breadcrumbs = crumbs, fragmentClass = AppearanceSettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.list_badges))
			}
			group(sectionCrumbs, ctx.getString(R.string.details)) { crumbs ->
				addItem(AppSettings.KEY_COLLAPSE_DESCRIPTION, R.string.collapse_long_description, breadcrumbs = crumbs, fragmentClass = AppearanceSettingsFragment::class.java)
				addItem(AppSettings.KEY_PAGES_TAB, R.string.show_pages_thumbs, R.string.show_pages_thumbs_summary, crumbs, AppearanceSettingsFragment::class.java)
				addItem(AppSettings.KEY_DETAILS_TAB, R.string.default_tab, breadcrumbs = crumbs, fragmentClass = AppearanceSettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.details_tabs))
				addItem("details_appearance_nav", R.string.details_appearance, R.string.details_appearance_summary, crumbs, AppearanceSettingsFragment::class.java)
				addItem(AppSettings.KEY_DETAILS_UI, R.string.details_ui, breadcrumbs = crumbs + ctx.getString(R.string.details_appearance), fragmentClass = PreviewSettingsFragment::class.java, keywordRes = intArrayOf(R.string.details_ui_compact, R.string.details_ui_expressive))
				addItem(AppSettings.KEY_DETAILS_BACKDROP, R.string.details_backdrop, R.string.details_backdrop_summary, crumbs + ctx.getString(R.string.details_appearance), PreviewSettingsFragment::class.java)
			}
			group(sectionCrumbs, ctx.getString(R.string.main_screen)) { crumbs ->
				addItem(AppSettings.KEY_SEARCH_SUGGESTION_TYPES, R.string.search_suggestions, breadcrumbs = crumbs, fragmentClass = AppearanceSettingsFragment::class.java)
				addItem(AppSettings.KEY_NAV_MAIN, R.string.main_screen_sections, breadcrumbs = crumbs, fragmentClass = AppearanceSettingsFragment::class.java)
				addItem(AppSettings.KEY_MAIN_FAB, R.string.main_screen_fab, R.string.main_screen_fab_summary, crumbs, AppearanceSettingsFragment::class.java)
				addItem(AppSettings.KEY_NAV_LABELS, R.string.show_labels_in_navbar, breadcrumbs = crumbs, fragmentClass = AppearanceSettingsFragment::class.java)
				addItem(AppSettings.KEY_NAV_LEGACY, R.string.use_legacy_navigation_bar, R.string.use_legacy_navigation_bar_summary, crumbs, AppearanceSettingsFragment::class.java)
				addItem(AppSettings.KEY_NAV_PINNED, R.string.pin_navigation_ui, R.string.pin_navigation_ui_summary, crumbs, AppearanceSettingsFragment::class.java)
				addItem(AppSettings.KEY_EXIT_CONFIRM, R.string.exit_confirmation, R.string.exit_confirmation_summary, crumbs, AppearanceSettingsFragment::class.java)
				addItem(AppSettings.KEY_SHORTCUTS, R.string.history_shortcuts, R.string.history_shortcuts_summary, crumbs, AppearanceSettingsFragment::class.java)
			}
			group(sectionCrumbs, ctx.getString(R.string.privacy)) { crumbs ->
				addItem(AppSettings.KEY_PROTECT_APP, R.string.require_unlock, R.string.require_unlock_summary, crumbs, AppearanceSettingsFragment::class.java)
				addItem(AppSettings.KEY_PROTECT_APP_TIMEOUT, R.string.require_unlock_after, breadcrumbs = crumbs, fragmentClass = AppearanceSettingsFragment::class.java)
				addItem(AppSettings.KEY_SCREENSHOTS_POLICY, R.string.screenshots_policy, breadcrumbs = crumbs, fragmentClass = AppearanceSettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.screenshots_policy))
			}
		}

		section(R.string.google_drive_sync, SyncSettingsFragment::class.java) { sectionCrumbs ->
			group(sectionCrumbs, ctx.getString(R.string.sync_account)) { crumbs ->
				addItem("sync_sign_in", R.string.sync_sign_in, R.string.sync_sign_in_summary, crumbs, SyncSettingsFragment::class.java)
				addItem("sync_sign_out", R.string.sync_sign_out, breadcrumbs = crumbs, fragmentClass = SyncSettingsFragment::class.java)
				addItem("sync_account", R.string.sync_account, breadcrumbs = crumbs, fragmentClass = SyncSettingsFragment::class.java, keywordRes = intArrayOf(R.string.sync_hide_email, R.string.sync_show_email))
			}
			group(sectionCrumbs, ctx.getString(R.string.options)) { crumbs ->
				addItem("sync_now", R.string.sync_now, breadcrumbs = crumbs, fragmentClass = SyncSettingsFragment::class.java, keywordRes = intArrayOf(R.string.sync_never, R.string.sync_syncing), keywordText = listOf("last synced"))
				addItem("sync_frequency", R.string.sync_frequency, breadcrumbs = crumbs, fragmentClass = SyncSettingsFragment::class.java, keywordRes = intArrayOf(R.string.sync_freq_off, R.string.sync_freq_6h, R.string.sync_freq_12h, R.string.sync_freq_daily, R.string.sync_freq_weekly))
				addItem("sync_wifi_only", R.string.sync_wifi_only, breadcrumbs = crumbs, fragmentClass = SyncSettingsFragment::class.java)
				addItem("sync_on_start", R.string.sync_on_start, R.string.sync_on_start_summary, crumbs, SyncSettingsFragment::class.java)
				addItem("sync_what", R.string.sync_what, breadcrumbs = crumbs, fragmentClass = SyncSettingsFragment::class.java, keywordRes = intArrayOf(R.string.sync_content_favourites, R.string.sync_content_history, R.string.sync_content_bookmarks, R.string.sync_content_feed, R.string.sync_content_tracking, R.string.sync_content_stats, R.string.sync_content_settings, R.string.sync_content_covers))
				addItem("sync_delete_data", R.string.sync_delete_data, R.string.sync_delete_data_summary, crumbs, SyncSettingsFragment::class.java)
			}
		}

		section(R.string.extensions, ExtensionsSettingsFragment::class.java) { sectionCrumbs ->
			group(sectionCrumbs, "Catalog") { crumbs ->
				addItem("sources_catalog", R.string.manage_extensions, R.string.manage_extensions_summary, crumbs, ExtensionsSettingsFragment::class.java)
			}
			group(sectionCrumbs, ctx.getString(R.string.appearance)) { crumbs ->
				addItem(AppSettings.KEY_SOURCES_ORDER, R.string.sort_order, breadcrumbs = crumbs, fragmentClass = ExtensionsSettingsFragment::class.java)
				addItem(AppSettings.KEY_SOURCES_GRID, R.string.show_in_grid_view, breadcrumbs = crumbs, fragmentClass = ExtensionsSettingsFragment::class.java)
			}
			group(sectionCrumbs, ctx.getString(R.string.filter)) { crumbs ->
				addItem(AppSettings.KEY_DISABLE_NSFW, R.string.disable_nsfw, R.string.disable_nsfw_summary, crumbs, ExtensionsSettingsFragment::class.java)
				addItem(AppSettings.KEY_TAGS_WARNINGS, R.string.tags_warnings, R.string.tags_warnings_summary, crumbs, ExtensionsSettingsFragment::class.java)
				addItem(AppSettings.KEY_INCOGNITO_NSFW, R.string.incognito_for_nsfw, breadcrumbs = crumbs, fragmentClass = ExtensionsSettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.incognito_nsfw_options))
			}
			group(sectionCrumbs, ctx.getString(R.string.handle_links)) { crumbs ->
				addItem(AppSettings.KEY_HANDLE_LINKS, R.string.handle_links, R.string.handle_links_summary, crumbs, ExtensionsSettingsFragment::class.java)
			}
		}

		section(R.string.reader_settings, ReaderSettingsFragment::class.java) { sectionCrumbs ->
			group(sectionCrumbs, "Mode") { crumbs ->
				addItem(AppSettings.KEY_READER_MODE, R.string.default_mode, breadcrumbs = crumbs, fragmentClass = ReaderSettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.reader_modes))
				addItem(AppSettings.KEY_READER_MODE_DETECT, R.string.detect_reader_mode, R.string.detect_reader_mode_summary, crumbs, ReaderSettingsFragment::class.java)
			}
			group(sectionCrumbs, "Zoom") { crumbs ->
				addItem(AppSettings.KEY_ZOOM_MODE, R.string.scale_mode, breadcrumbs = crumbs, fragmentClass = ReaderSettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.zoom_modes))
				addItem(AppSettings.KEY_READER_ZOOM_BUTTONS, R.string.reader_zoom_buttons, R.string.reader_zoom_buttons_summary, crumbs, ReaderSettingsFragment::class.java)
				addItem(AppSettings.KEY_WEBTOON_ZOOM, R.string.webtoon_zoom, R.string.webtoon_zoom_summary, crumbs, ReaderSettingsFragment::class.java)
				addItem(AppSettings.KEY_WEBTOON_ZOOM_OUT, R.string.default_webtoon_zoom_out, breadcrumbs = crumbs, fragmentClass = ReaderSettingsFragment::class.java)
				addItem(AppSettings.KEY_WEBTOON_GAPS, R.string.webtoon_gaps, R.string.webtoon_gaps_summary, crumbs, ReaderSettingsFragment::class.java)
			}
			group(sectionCrumbs, "Controls") { crumbs ->
				addItem(AppSettings.KEY_READER_CONTROLS, R.string.reader_controls_in_bottom_bar, breadcrumbs = crumbs, fragmentClass = ReaderSettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.reader_controls))
				addItem("reader_tap_actions", R.string.reader_actions, R.string.reader_actions_summary, crumbs, ReaderSettingsFragment::class.java)
				addItem(AppSettings.KEY_READER_CONTROL_LTR, R.string.reader_control_ltr, R.string.reader_control_ltr_summary, crumbs, ReaderSettingsFragment::class.java)
				addItem(AppSettings.KEY_READER_VOLUME_BUTTONS, R.string.switch_pages_volume_buttons, R.string.switch_pages_volume_buttons_summary, crumbs, ReaderSettingsFragment::class.java)
				addItem(AppSettings.KEY_READER_NAVIGATION_INVERTED, R.string.reader_navigation_inverted, R.string.reader_navigation_inverted_summary, crumbs, ReaderSettingsFragment::class.java)
				addItem(AppSettings.KEY_READER_ANIMATION, R.string.pages_animation, breadcrumbs = crumbs, fragmentClass = ReaderSettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.reader_animation))
				addItem(AppSettings.KEY_WEBTOON_PULL_GESTURE, R.string.enable_pull_gesture_title, R.string.enable_pull_gesture_summary, crumbs, ReaderSettingsFragment::class.java)
			}
			group(sectionCrumbs, "Image") { crumbs ->
				addItem(AppSettings.KEY_32BIT_COLOR, R.string.enhanced_colors, R.string.enhanced_colors_summary, crumbs, ReaderSettingsFragment::class.java)
				addItem(AppSettings.KEY_READER_OPTIMIZE, R.string.reader_optimize, R.string.reader_optimize_summary, crumbs, ReaderSettingsFragment::class.java)
				addItem(AppSettings.KEY_READER_CROP, R.string.crop_pages, breadcrumbs = crumbs, fragmentClass = ReaderSettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.reader_crop))
			}
			group(sectionCrumbs, "Display") { crumbs ->
				addItem(AppSettings.KEY_READER_FULLSCREEN, R.string.fullscreen_mode, R.string.reader_fullscreen_summary, crumbs, ReaderSettingsFragment::class.java)
				addItem(AppSettings.KEY_READER_ORIENTATION, R.string.screen_orientation, breadcrumbs = crumbs, fragmentClass = ReaderSettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.screen_orientations))
				addItem(AppSettings.KEY_READER_SCREEN_ON, R.string.keep_screen_on, R.string.keep_screen_on_summary, crumbs, ReaderSettingsFragment::class.java)
				addItem(AppSettings.KEY_READER_MULTITASK, R.string.reader_multitask, R.string.reader_multitask_summary, crumbs, ReaderSettingsFragment::class.java)
			}
			group(sectionCrumbs, "Reading info") { crumbs ->
				addItem(AppSettings.KEY_READER_BAR, R.string.reader_info_bar, R.string.reader_info_bar_summary, crumbs, ReaderSettingsFragment::class.java)
				addItem(AppSettings.KEY_READER_BAR_TRANSPARENT, R.string.reader_info_bar_transparent, breadcrumbs = crumbs, fragmentClass = ReaderSettingsFragment::class.java)
				addItem(AppSettings.KEY_READER_CHAPTER_TOAST, R.string.reader_chapter_toast, R.string.reader_chapter_toast_summary, crumbs, ReaderSettingsFragment::class.java)
			}
			group(sectionCrumbs, "Background") { crumbs ->
				addItem(AppSettings.KEY_READER_BACKGROUND, R.string.background, breadcrumbs = crumbs, fragmentClass = ReaderSettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.reader_backgrounds))
				addItem(AppSettings.KEY_PAGES_NUMBERS, R.string.show_pages_numbers, R.string.show_pages_numbers_summary, crumbs, ReaderSettingsFragment::class.java)
				addItem(AppSettings.KEY_PAGES_PRELOAD, R.string.preload_pages, breadcrumbs = crumbs, fragmentClass = ReaderSettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.network_policy))
			}
		}

		section(R.string.storage_and_network, StorageAndNetworkSettingsFragment::class.java) { sectionCrumbs ->
			group(sectionCrumbs, ctx.getString(R.string.storage_usage)) { crumbs ->
				addItem("data_removal", R.string.data_removal, breadcrumbs = crumbs, fragmentClass = DataCleanupSettingsFragment::class.java)
				addItem(AppSettings.KEY_SEARCH_HISTORY_CLEAR, R.string.clear_search_history, breadcrumbs = crumbs + ctx.getString(R.string.data_removal), fragmentClass = DataCleanupSettingsFragment::class.java)
				addItem(AppSettings.KEY_UPDATES_FEED_CLEAR, R.string.clear_updates_feed, breadcrumbs = crumbs + ctx.getString(R.string.data_removal), fragmentClass = DataCleanupSettingsFragment::class.java)
				addItem(AppSettings.KEY_THUMBS_CACHE_CLEAR, R.string.clear_thumbs_cache, breadcrumbs = crumbs + ctx.getString(R.string.data_removal), fragmentClass = DataCleanupSettingsFragment::class.java)
				addItem(AppSettings.KEY_PAGES_CACHE_CLEAR, R.string.clear_pages_cache, breadcrumbs = crumbs + ctx.getString(R.string.data_removal), fragmentClass = DataCleanupSettingsFragment::class.java)
				addItem(AppSettings.KEY_HTTP_CACHE_CLEAR, R.string.clear_network_cache, breadcrumbs = crumbs + ctx.getString(R.string.data_removal), fragmentClass = DataCleanupSettingsFragment::class.java)
				addItem(AppSettings.KEY_CLEAR_MANGA_DATA, R.string.clear_database, R.string.clear_database_summary, crumbs + ctx.getString(R.string.data_removal), DataCleanupSettingsFragment::class.java)
				addItem(AppSettings.KEY_COOKIES_CLEAR, R.string.clear_cookies, R.string.clear_cookies_summary, crumbs + ctx.getString(R.string.data_removal), DataCleanupSettingsFragment::class.java)
				addItem(AppSettings.KEY_WEBVIEW_CLEAR, R.string.clear_browser_data, R.string.clear_browser_data_summary, crumbs + ctx.getString(R.string.data_removal), DataCleanupSettingsFragment::class.java)
				addItem(AppSettings.KEY_CHAPTERS_CLEAR, R.string.delete_read_chapters, R.string.delete_read_chapters_summary, crumbs + ctx.getString(R.string.data_removal), DataCleanupSettingsFragment::class.java)
				addItem(AppSettings.KEY_CHAPTERS_CLEAR_AUTO, R.string.delete_read_chapters_auto, R.string.runs_on_app_start, crumbs + ctx.getString(R.string.data_removal), DataCleanupSettingsFragment::class.java)
			}
			group(sectionCrumbs, "Network") { crumbs ->
				addItem(AppSettings.KEY_PREFETCH_CONTENT, R.string.prefetch_content, breadcrumbs = crumbs, fragmentClass = StorageAndNetworkSettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.network_policy))
				addItem(AppSettings.KEY_PAGES_PRELOAD, R.string.preload_pages, breadcrumbs = crumbs, fragmentClass = StorageAndNetworkSettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.network_policy))
				addItem("proxy", R.string.proxy, breadcrumbs = crumbs, fragmentClass = ProxySettingsFragment::class.java)
				addItem(AppSettings.KEY_PROXY_TYPE, R.string.type, breadcrumbs = crumbs + ctx.getString(R.string.proxy), fragmentClass = ProxySettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.proxy_types))
				addItem(AppSettings.KEY_PROXY_ADDRESS, R.string.address, breadcrumbs = crumbs + ctx.getString(R.string.proxy), fragmentClass = ProxySettingsFragment::class.java)
				addItem(AppSettings.KEY_PROXY_PORT, R.string.port, breadcrumbs = crumbs + ctx.getString(R.string.proxy), fragmentClass = ProxySettingsFragment::class.java)
				addItem(AppSettings.KEY_PROXY_LOGIN, R.string.username, breadcrumbs = crumbs + ctx.getString(R.string.proxy), fragmentClass = ProxySettingsFragment::class.java)
				addItem(AppSettings.KEY_PROXY_PASSWORD, R.string.password, breadcrumbs = crumbs + ctx.getString(R.string.proxy), fragmentClass = ProxySettingsFragment::class.java)
				addItem("proxy_test_connection", R.string.test_connection, breadcrumbs = crumbs + ctx.getString(R.string.proxy), fragmentClass = ProxySettingsFragment::class.java)
				addItem(AppSettings.KEY_DOH, R.string.dns_over_https, breadcrumbs = crumbs, fragmentClass = StorageAndNetworkSettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.doh_providers))
				addItem(AppSettings.KEY_MIHON_USER_AGENT, R.string.user_agent, breadcrumbs = crumbs, fragmentClass = StorageAndNetworkSettingsFragment::class.java, keywordText = listOf("webview", "browser", "mihon"))
				addItem(AppSettings.KEY_IMAGES_PROXY, R.string.images_proxy_title, breadcrumbs = crumbs, fragmentClass = StorageAndNetworkSettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.image_proxies))
				addItem(AppSettings.KEY_SSL_BYPASS, R.string.ignore_ssl_errors, R.string.ignore_ssl_errors_summary, crumbs, StorageAndNetworkSettingsFragment::class.java)
				addItem(AppSettings.KEY_OFFLINE_DISABLED, R.string.disable_connectivity_check, R.string.disable_connectivity_check_summary, crumbs, StorageAndNetworkSettingsFragment::class.java)
				addItem(AppSettings.KEY_ADBLOCK, R.string.adblock, R.string.adblock_summary, crumbs, StorageAndNetworkSettingsFragment::class.java)
			}
		}

		section(R.string.downloads, DownloadsSettingsFragment::class.java) { sectionCrumbs ->
			group(sectionCrumbs, "General") { crumbs ->
				addItem(AppSettings.KEY_LOCAL_MANGA_DIRS, R.string.local_manga_directories, breadcrumbs = crumbs, fragmentClass = DownloadsSettingsFragment::class.java)
				addItem(AppSettings.KEY_LOCAL_STORAGE, R.string.manga_save_location, breadcrumbs = crumbs, fragmentClass = DownloadsSettingsFragment::class.java)
				addItem(AppSettings.KEY_DOWNLOADS_FORMAT, R.string.preferred_download_format, breadcrumbs = crumbs, fragmentClass = DownloadsSettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.download_formats))
				addItem(AppSettings.KEY_DOWNLOADS_METERED_NETWORK, R.string.download_over_cellular, breadcrumbs = crumbs, fragmentClass = DownloadsSettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.metered_network_options))
				addItem("ignore_dose", R.string.disable_battery_optimization, R.string.disable_battery_optimization_summary_downloads, crumbs, DownloadsSettingsFragment::class.java)
			}
			group(sectionCrumbs, ctx.getString(R.string.pages_saving)) { crumbs ->
				addItem(AppSettings.KEY_PAGES_SAVE_DIR, R.string.default_page_save_dir, breadcrumbs = crumbs, fragmentClass = DownloadsSettingsFragment::class.java)
				addItem(AppSettings.KEY_PAGES_SAVE_ASK, R.string.ask_for_dest_dir_every_time, breadcrumbs = crumbs, fragmentClass = DownloadsSettingsFragment::class.java)
			}
		}

		section(R.string.backup_restore, BackupSettingsFragment::class.java) { sectionCrumbs ->
			group(sectionCrumbs, ctx.getString(R.string.backup_restore)) { crumbs ->
				addItem("create_backup", R.string.create_backup, R.string.create_backup_summary, crumbs, BackupSettingsFragment::class.java)
				addItem("restore_local_backup", R.string.restore_backup, R.string.restore_summary, crumbs, BackupSettingsFragment::class.java)
				addItem("backup_periodic_screen", R.string.periodic_backups, R.string.periodic_backups_summary, crumbs, BackupSettingsFragment::class.java)
			}
			group(sectionCrumbs, ctx.getString(R.string.import_from_other_apps)) { crumbs ->
				addItem("restore_backup", R.string.restore_from_tachiyomi, R.string.restore_tachiyomi_summary, crumbs, BackupSettingsFragment::class.java)
			}
		}

		section(R.string.check_for_new_chapters, TrackerSettingsFragment::class.java) { sectionCrumbs ->
			group(sectionCrumbs, "Tracker") { crumbs ->
				addItem(AppSettings.KEY_TRACKER_ENABLED, R.string.check_new_chapters_title, breadcrumbs = crumbs, fragmentClass = TrackerSettingsFragment::class.java)
				addItem(AppSettings.KEY_TRACKER_WIFI_ONLY, R.string.only_using_wifi, R.string.tracker_wifi_only_summary, crumbs, TrackerSettingsFragment::class.java)
				addItem(AppSettings.KEY_TRACKER_FREQUENCY, R.string.frequency_of_check, breadcrumbs = crumbs, fragmentClass = TrackerSettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.tracker_frequency))
				addItem(AppSettings.KEY_TRACK_SOURCES, R.string.track_sources, breadcrumbs = crumbs, fragmentClass = TrackerSettingsFragment::class.java, keywordArrayRes = intArrayOf(R.array.track_sources))
				addItem("track_categories", R.string.favourites_categories, breadcrumbs = crumbs, fragmentClass = TrackerSettingsFragment::class.java)
				addItem("notifications_settings", R.string.notifications_settings, breadcrumbs = crumbs, fragmentClass = TrackerSettingsFragment::class.java)
				addItem("notifications", R.string.notifications, breadcrumbs = crumbs, fragmentClass = TrackerSettingsFragment::class.java)
				addItem(AppSettings.KEY_TRACKER_NOTIFICATIONS, R.string.notifications_enable, breadcrumbs = crumbs + ctx.getString(R.string.notifications), fragmentClass = NotificationSettingsLegacyFragment::class.java)
				addItem(AppSettings.KEY_NOTIFICATIONS_SOUND, R.string.notification_sound, breadcrumbs = crumbs + ctx.getString(R.string.notifications), fragmentClass = NotificationSettingsLegacyFragment::class.java)
				addItem(AppSettings.KEY_NOTIFICATIONS_VIBRATE, R.string.vibration, breadcrumbs = crumbs + ctx.getString(R.string.notifications), fragmentClass = NotificationSettingsLegacyFragment::class.java)
				addItem(AppSettings.KEY_NOTIFICATIONS_LIGHT, R.string.light_indicator, breadcrumbs = crumbs + ctx.getString(R.string.notifications), fragmentClass = NotificationSettingsLegacyFragment::class.java)
				addItem(AppSettings.KEY_TRACKER_NO_NSFW, R.string.disable_nsfw_notifications, R.string.disable_nsfw_notifications_summary, crumbs, TrackerSettingsFragment::class.java)
				addItem(AppSettings.KEY_TRACKER_DOWNLOAD, R.string.download_new_chapters, breadcrumbs = crumbs, fragmentClass = TrackerSettingsFragment::class.java)
			}
			group(sectionCrumbs, ctx.getString(R.string.debug)) { crumbs ->
				addItem("tracker_debug", R.string.tracker_debug_info, R.string.tracker_debug_info_summary, crumbs, TrackerSettingsFragment::class.java)
				addItem("ignore_dose", R.string.disable_battery_optimization, R.string.disable_battery_optimization_summary, crumbs, TrackerSettingsFragment::class.java)
				addItem("track_warning", R.string.tracker_warning, breadcrumbs = crumbs, fragmentClass = TrackerSettingsFragment::class.java)
			}
		}

		section(R.string.services, ServicesSettingsFragment::class.java) { sectionCrumbs ->
			group(sectionCrumbs, "General") { crumbs ->
				addItem("suggestions_screen", R.string.suggestions, breadcrumbs = crumbs, fragmentClass = SuggestionsSettingsFragment::class.java)
				addItem(AppSettings.KEY_SUGGESTIONS, R.string.suggestions_enable, breadcrumbs = crumbs + ctx.getString(R.string.suggestions), fragmentClass = SuggestionsSettingsFragment::class.java)
				addItem(AppSettings.KEY_SUGGESTIONS_WIFI_ONLY, R.string.only_using_wifi, R.string.suggestions_wifi_only_summary, crumbs + ctx.getString(R.string.suggestions), SuggestionsSettingsFragment::class.java)
				addItem(AppSettings.KEY_SUGGESTIONS_NOTIFICATIONS, R.string.notifications_enable, R.string.suggestions_notifications_summary, crumbs + ctx.getString(R.string.suggestions), SuggestionsSettingsFragment::class.java)
				addItem(AppSettings.KEY_SUGGESTIONS_EXCLUDE_TAGS, R.string.suggestions_excluded_genres, R.string.suggestions_excluded_genres_summary, crumbs + ctx.getString(R.string.suggestions), SuggestionsSettingsFragment::class.java)
				addItem(AppSettings.KEY_SUGGESTIONS_EXCLUDE_NSFW, R.string.exclude_nsfw_from_suggestions, R.string.exclude_nsfw_from_suggestions_summary, crumbs + ctx.getString(R.string.suggestions), SuggestionsSettingsFragment::class.java)
				addItem(AppSettings.KEY_RELATED_MANGA, R.string.related_manga, R.string.related_manga_summary, crumbs, ServicesSettingsFragment::class.java)
				addItem(AppSettings.KEY_STATS_ENABLED, R.string.reading_stats, breadcrumbs = crumbs, fragmentClass = ServicesSettingsFragment::class.java)
				addItem(AppSettings.KEY_READING_TIME, R.string.reading_time_estimation, R.string.reading_time_estimation_summary, crumbs, ServicesSettingsFragment::class.java)
			}
			group(sectionCrumbs, ctx.getString(R.string.tracking)) { crumbs ->
				addItem("anilist", R.string.anilist, breadcrumbs = crumbs, fragmentClass = ServicesSettingsFragment::class.java)
				addItem("kitsu", R.string.kitsu, breadcrumbs = crumbs, fragmentClass = ServicesSettingsFragment::class.java)
				addItem("mal", R.string.mal, breadcrumbs = crumbs, fragmentClass = ServicesSettingsFragment::class.java)
				addItem("shikimori", R.string.shikimori, breadcrumbs = crumbs, fragmentClass = ServicesSettingsFragment::class.java)
			}
			group(sectionCrumbs, "External") { crumbs ->
				addItem("discord_rpc", R.string.discord_rpc, R.string.discord_rpc_summary, crumbs, DiscordSettingsFragment::class.java)
				addItem("discord_token", R.string.discord_token, breadcrumbs = crumbs + ctx.getString(R.string.discord), fragmentClass = DiscordSettingsFragment::class.java, keywordText = listOf("token", "sign in", "browser"))
				addItem("discord_skip_nsfw", R.string.disable_nsfw, R.string.rpc_skip_nsfw_summary, crumbs + ctx.getString(R.string.discord), DiscordSettingsFragment::class.java)
			}
		}

		section(R.string.about, AboutSettingsFragment::class.java) { sectionCrumbs ->
			group(sectionCrumbs, "Updates") { crumbs ->
				addItem("app_version", R.string.check_for_updates, breadcrumbs = crumbs, fragmentClass = AboutSettingsFragment::class.java)
				addItem("changelog", R.string.changelog, R.string.changelog_summary, crumbs, AboutSettingsFragment::class.java)
			}
			group(sectionCrumbs, "Links") { crumbs ->
				addItem("about_help", R.string.user_manual, R.string.url_user_manual, crumbs, AboutSettingsFragment::class.java)
				addItem("about_github", R.string.source_code, R.string.url_github, crumbs, AboutSettingsFragment::class.java)
				addItem("about_discord", R.string.discord, R.string.url_discord_web, crumbs, AboutSettingsFragment::class.java)
			}
		}

		return result
	}

	private fun buildSearchText(
		title: CharSequence,
		key: String,
		summary: String,
		breadcrumbs: List<String>,
		keywords: List<String>,
	): String = buildString {
		append(title)
		append(' ')
		append(key)
		if (summary.isNotBlank()) {
			append(' ')
			append(summary)
		}
		if (breadcrumbs.isNotEmpty()) {
			append(' ')
			append(breadcrumbs.joinToString(" "))
		}
		if (keywords.isNotEmpty()) {
			append(' ')
			append(keywords.joinToString(" "))
		}
	}
}
