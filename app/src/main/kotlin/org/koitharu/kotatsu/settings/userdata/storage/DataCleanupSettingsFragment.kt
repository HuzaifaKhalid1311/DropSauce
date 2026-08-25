package org.koitharu.kotatsu.settings.userdata.storage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.StateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.FileSize
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.local.data.CacheDir
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.SegmentedSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberIntPref

@AndroidEntryPoint
class DataCleanupSettingsFragment : BaseComposeSettingsFragment(R.string.data_removal) {

	private val viewModel by viewModels<DataCleanupSettingsViewModel>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				DataCleanupScreen(
					searchHistoryCount = viewModel.searchHistoryCount,
					feedItemsCount = viewModel.feedItemsCount,
					httpCacheSize = viewModel.httpCacheSize,
					thumbsCacheSize = checkNotNull(viewModel.cacheSizes[CacheDir.THUMBS]),
					pagesCacheSize = checkNotNull(viewModel.cacheSizes[CacheDir.PAGES]),
					loadingKeys = viewModel.loadingKeys,
					isBrowserCleanupEnabled = viewModel.isBrowserDataCleanupEnabled,
					onAction = ::onAction,
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(view, this))
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(view))
		viewModel.onChaptersCleanedUp.observeEvent(viewLifecycleOwner, ::onChaptersCleanedUp)
	}

	private fun onAction(key: String) {
		when (key) {
			AppSettings.KEY_COOKIES_CLEAR -> clearCookies()
			AppSettings.KEY_SEARCH_HISTORY_CLEAR -> clearSearchHistory()
			AppSettings.KEY_PAGES_CACHE_CLEAR -> viewModel.clearCache(key, CacheDir.PAGES)
			AppSettings.KEY_THUMBS_CACHE_CLEAR -> viewModel.clearCache(key, CacheDir.THUMBS, CacheDir.FAVICONS)
			AppSettings.KEY_HTTP_CACHE_CLEAR -> viewModel.clearHttpCache()
			AppSettings.KEY_CHAPTERS_CLEAR -> cleanupChapters()
			AppSettings.KEY_WEBVIEW_CLEAR -> viewModel.clearBrowserData()
			AppSettings.KEY_CLEAR_MANGA_DATA -> viewModel.clearMangaData()
			AppSettings.KEY_UPDATES_FEED_CLEAR -> viewModel.clearUpdatesFeed()
		}
	}

	private fun onChaptersCleanedUp(result: Pair<Int, Long>) {
		val c = context ?: return
		val text = if (result.first == 0 && result.second == 0L) {
			c.getString(R.string.no_chapters_deleted)
		} else {
			c.getString(
				R.string.chapters_deleted_pattern,
				c.resources.getQuantityStringSafe(R.plurals.chapters, result.first, result.first),
				FileSize.BYTES.format(c, result.second),
			)
		}
		Snackbar.make(requireView(), text, Snackbar.LENGTH_SHORT).show()
	}

	private fun clearSearchHistory() {
		buildAlertDialog(context ?: return) {
			setTitle(R.string.clear_search_history)
			setMessage(R.string.text_clear_search_history_prompt)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.clear) { _, _ -> viewModel.clearSearchHistory() }
		}.show()
	}

	private fun clearCookies() {
		buildAlertDialog(context ?: return) {
			setTitle(R.string.clear_cookies)
			setMessage(R.string.text_clear_cookies_prompt)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.clear) { _, _ -> viewModel.clearCookies() }
		}.show()
	}

	private fun cleanupChapters() {
		buildAlertDialog(context ?: return) {
			setTitle(R.string.delete_read_chapters)
			setMessage(R.string.delete_read_chapters_prompt)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.delete) { _, _ -> viewModel.cleanupChapters() }
		}.show()
	}
}

@Composable
private fun DataCleanupScreen(
	searchHistoryCount: StateFlow<Int>,
	feedItemsCount: StateFlow<Int>,
	httpCacheSize: StateFlow<Long>,
	thumbsCacheSize: StateFlow<Long>,
	pagesCacheSize: StateFlow<Long>,
	loadingKeys: StateFlow<Set<String>>,
	isBrowserCleanupEnabled: Boolean,
	onAction: (String) -> Unit,
) {
	val ctx = LocalContext.current
	val searchCount by searchHistoryCount.collectAsState()
	val feedCount by feedItemsCount.collectAsState()
	val httpSize by httpCacheSize.collectAsState()
	val thumbsSize by thumbsCacheSize.collectAsState()
	val pagesSize by pagesCacheSize.collectAsState()
	val loading by loadingKeys.collectAsState()

	val computing = stringResource(R.string.computing_)
	val loadingStr = stringResource(R.string.loading_)

	fun sizeText(size: Long): String = if (size < 0) computing else FileSize.BYTES.format(ctx, size)
	fun countText(count: Int): String = if (count < 0) {
		loadingStr
	} else {
		ctx.resources.getQuantityStringSafe(R.plurals.items, count, count)
	}

	SettingsScaffold {
		item {
			SettingsGroup {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.clear_search_history),
						subtitle = countText(searchCount),
						icon = R.drawable.ic_history,
						shape = pos.shape,
						enabled = AppSettings.KEY_SEARCH_HISTORY_CLEAR !in loading,
						onClick = { onAction(AppSettings.KEY_SEARCH_HISTORY_CLEAR) },
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.clear_updates_feed),
						subtitle = countText(feedCount),
						icon = R.drawable.ic_feed,
						shape = pos.shape,
						enabled = AppSettings.KEY_UPDATES_FEED_CLEAR !in loading,
						onClick = { onAction(AppSettings.KEY_UPDATES_FEED_CLEAR) },
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.clear_thumbs_cache),
						subtitle = sizeText(thumbsSize),
						icon = R.drawable.ic_images,
						shape = pos.shape,
						enabled = AppSettings.KEY_THUMBS_CACHE_CLEAR !in loading,
						onClick = { onAction(AppSettings.KEY_THUMBS_CACHE_CLEAR) },
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.clear_pages_cache),
						subtitle = sizeText(pagesSize),
						icon = R.drawable.ic_book_page,
						shape = pos.shape,
						enabled = AppSettings.KEY_PAGES_CACHE_CLEAR !in loading,
						onClick = { onAction(AppSettings.KEY_PAGES_CACHE_CLEAR) },
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.clear_network_cache),
						subtitle = sizeText(httpSize),
						icon = R.drawable.ic_web,
						shape = pos.shape,
						enabled = AppSettings.KEY_HTTP_CACHE_CLEAR !in loading,
						onClick = { onAction(AppSettings.KEY_HTTP_CACHE_CLEAR) },
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.clear_database),
						subtitle = stringResource(R.string.clear_database_summary),
						icon = R.drawable.ic_delete_all,
						shape = pos.shape,
						enabled = AppSettings.KEY_CLEAR_MANGA_DATA !in loading,
						onClick = { onAction(AppSettings.KEY_CLEAR_MANGA_DATA) },
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.clear_cookies),
						subtitle = stringResource(R.string.clear_cookies_summary),
						icon = R.drawable.ic_data_privacy,
						shape = pos.shape,
						enabled = AppSettings.KEY_COOKIES_CLEAR !in loading,
						onClick = { onAction(AppSettings.KEY_COOKIES_CLEAR) },
					)
				}
				if (isBrowserCleanupEnabled) {
					item { pos ->
						ActionSettingsItem(
							title = stringResource(R.string.clear_browser_data),
							subtitle = stringResource(R.string.clear_browser_data_summary),
							icon = R.drawable.ic_open_external,
							shape = pos.shape,
							enabled = AppSettings.KEY_WEBVIEW_CLEAR !in loading,
							onClick = { onAction(AppSettings.KEY_WEBVIEW_CLEAR) },
						)
					}
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			// The safety margin only has any effect while the automatic cleanup is on, so it
			// lives right under that switch and greys out with it — hoisted here because the
			// SettingsGroup content lambda is not composable.
			var auto by rememberBooleanPref(AppSettings.KEY_CHAPTERS_CLEAR_AUTO, false)
			SettingsGroup(title = stringResource(R.string.delete_read_chapters)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.delete_read_chapters),
						subtitle = stringResource(R.string.delete_read_chapters_summary),
						icon = R.drawable.ic_delete,
						shape = pos.shape,
						enabled = AppSettings.KEY_CHAPTERS_CLEAR !in loading,
						onClick = { onAction(AppSettings.KEY_CHAPTERS_CLEAR) },
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.delete_read_chapters_auto),
						subtitle = stringResource(R.string.runs_on_app_start),
						checked = auto,
						onCheckedChange = { auto = it },
						icon = R.drawable.ic_timer_run,
						shape = pos.shape,
					)
				}
				item { pos ->
					var keep by rememberIntPref(AppSettings.KEY_CHAPTERS_CLEAR_KEEP, 2)
					SegmentedSettingsItem(
						title = stringResource(R.string.delete_read_chapters_keep),
						subtitle = if (keep <= 0) {
							stringResource(R.string.delete_read_chapters_keep_immediate)
						} else {
							pluralStringResource(R.plurals.delete_read_chapters_keep_summary, keep, keep)
						},
						labels = List(4) { it.toString() },
						selectedIndex = keep.coerceIn(0, 3),
						onSelected = { keep = it },
						icon = R.drawable.ic_chapter_stack,
						shape = pos.shape,
						enabled = auto,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}
