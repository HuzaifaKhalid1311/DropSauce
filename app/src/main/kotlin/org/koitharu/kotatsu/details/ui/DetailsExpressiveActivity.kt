package org.koitharu.kotatsu.details.ui

import android.app.assist.AssistContent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.widget.ActionMenuView
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.util.MenuInvalidator
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.toUriOrNull
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.databinding.ActivityDetailsExpressiveBinding
import org.koitharu.kotatsu.details.service.MangaPrefetchService
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.download.ui.worker.DownloadStartedObserver
import org.koitharu.kotatsu.parsers.model.ContentRating
import coil3.ImageLoader
import javax.inject.Inject

/**
 * Brand-new Material 3 Expressive details screen, rendered entirely with Jetpack Compose. It shares
 * [DetailsViewModel] and the [org.koitharu.kotatsu.details.ui.pager.ChaptersPagesSheet] bottom sheet
 * with the legacy [DetailsActivity], but lays everything out fresh: a frosted cover backdrop, an
 * oversized rounded poster, playful stat pills, and large tactile cards.
 */
@AndroidEntryPoint
class DetailsExpressiveActivity :
	BaseActivity<ActivityDetailsExpressiveBinding>() {

	@Inject lateinit var coil: ImageLoader
	@Inject lateinit var settings: AppSettings
	@Inject lateinit var shortcutManager: AppShortcutManager

	private val viewModel: DetailsViewModel by viewModels()
	private lateinit var menuProvider: DetailsMenuProvider

	private val topInset = mutableIntStateOf(0)
	private val bottomInset = mutableIntStateOf(0)
	private var isDarkTheme = false

	// Pull-to-refresh is only allowed when the content is scrolled to the top, so the gesture never
	// fires mid-scroll. The chapters list now lives in a modal sheet, so it can't interfere here.
	private var contentAtTop = true

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityDetailsExpressiveBinding.inflate(layoutInflater))
		WindowCompat.setDecorFitsSystemWindows(window, false)
		isDarkTheme = ColorUtils.calculateLuminance(getThemeColor(android.R.attr.colorBackground)) <= 0.5
		WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !isDarkTheme
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		supportActionBar?.setDisplayShowTitleEnabled(false)

		setupContent()
		setupSwipeRefresh()

		menuProvider = DetailsMenuProvider(
			activity = this,
			viewModel = viewModel,
			snackbarHost = viewBinding.composeView,
			appShortcutManager = shortcutManager,
		)
		addMenuProvider(menuProvider)

		val menuInvalidator = MenuInvalidator(this)
		viewModel.isStatsAvailable.observe(this, menuInvalidator)
		viewModel.remoteManga.observe(this, menuInvalidator)
		viewModel.mangaDetails.observe(this) {
			it?.let { d -> title = d.toManga().title }
			invalidateOptionsMenu()
		}
		viewModel.onActionDone
			.filterNot { router.isChapterPagesSheetShown() }
			.observeEvent(this, ReversibleActionObserver(viewBinding.composeView))
		viewModel.onError
			.filterNot { router.isChapterPagesSheetShown() }
			.observeEvent(
				this,
				DetailsErrorObserver(
					activity = this,
					snackbarHost = viewBinding.composeView,
					bottomSheet = null,
					viewModel = viewModel,
					resolver = exceptionResolver,
				),
			)
		viewModel.onMangaRemoved.observeEvent(this) { finishAfterTransition() }
		viewModel.onDownloadStarted
			.filterNot { router.isChapterPagesSheetShown() }
			.observeEvent(this, DownloadStartedObserver(viewBinding.composeView))
		viewModel.chapters.observe(this, PrefetchObserver(this))
	}

	override fun onProvideAssistContent(outContent: AssistContent) {
		super.onProvideAssistContent(outContent)
		viewModel.getMangaOrNull()?.publicUrl?.toUriOrNull()?.let { outContent.webUri = it }
	}

	override fun isNsfwContent(): Flow<Boolean> =
		viewModel.manga.map { it?.contentRating == ContentRating.ADULT }

	private fun setupContent() {
		val actions = DetailsExpressiveActions(
			onCoverClick = { manga ->
				val url = viewModel.coverUrl.value ?: return@DetailsExpressiveActions
				router.openImage(url = url, source = manga.source, manga = manga)
			},
			onTitleClick = { title -> showTitleDialog(title) },
			onSourceClick = { manga -> router.openList(manga.source, null, null) },
			onLocalClick = { manga -> router.showLocalInfoDialog(manga) },
			onFavoriteClick = { manga -> router.showFavoriteDialog(manga, null) },
			onAuthorClick = { author ->
				router.showAuthorDialog(author, viewModel.getMangaOrNull()?.source ?: return@DetailsExpressiveActions)
			},
			onTagClick = { tag -> router.showTagDialog(tag) },
			onScrobblingMore = {
				router.showScrobblingSelectorSheet(
					manga = viewModel.getMangaOrNull() ?: return@DetailsExpressiveActions,
					scrobblerService = viewModel.scrobblingInfo.value.firstOrNull()?.scrobbler,
				)
			},
			onScrobblingCardClick = { index -> router.showScrobblingInfoSheet(index) },
			onRelatedMore = { manga -> router.openRelated(manga) },
			onRelatedClick = { item -> router.openDetails(item.toMangaWithOverride()) },
			onReadClick = { openReader(isIncognitoMode = false) },
			onIncognitoClick = { openReader(isIncognitoMode = true) },
			onForgetHistoryClick = { viewModel.removeFromHistory() },
			onChaptersClick = { router.showChapterPagesSheet() },
			onRetry = { viewModel.reload() },
		)
		viewBinding.composeView.setViewCompositionStrategy(
			ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
		)
		viewBinding.composeView.setContent {
			org.koitharu.kotatsu.settings.compose.DropSauceTheme {
				val density = androidx.compose.ui.platform.LocalDensity.current
				val details by viewModel.mangaDetails.collectAsState()
				val history by viewModel.historyInfo.collectAsState()
				val loading by viewModel.isLoading.collectAsState()
				val favs by viewModel.favouriteCategories.collectAsState()
				val scrob by viewModel.scrobblingInfo.collectAsState()
				val related by viewModel.relatedManga.collectAsState()
				val localSize by viewModel.localSize.collectAsState()
				val srcTitle by viewModel.cachedSourceTitle.collectAsState()
				val coverUrl by viewModel.coverUrl.collectAsState()
				val backdropUrl by viewModel.backdropUrl.collectAsState()
				val favLabel = favs.takeIf { it.isNotEmpty() }?.joinToString { it.title }

				DetailsExpressiveScreen(
					details = details,
					historyInfo = history,
					isLoading = loading,
					favouriteCount = favs.size,
					favouriteLabel = favLabel,
					scrobblings = scrob,
					related = related,
					localSize = localSize,
					sourceTitle = srcTitle,
					imageLoader = coil,
					coverUrl = coverUrl,
					backdropUrl = backdropUrl,
					isBackdropEnabled = settings.isBackdropEnabled,
					style = settings.detailsUiMode,
					topInset = with(density) { topInset.intValue.toDp() },
					bottomContentPadding = with(density) { bottomInset.intValue.toDp() },
					onScroll = ::onContentScroll,
					actions = actions,
				)
			}
		}
	}

	private fun setupSwipeRefresh() {
		val swipeRefresh = viewBinding.swipeRefreshLayout
		swipeRefresh.setOnRefreshListener { viewModel.reload() }
		viewModel.isLoading.observe(this) { swipeRefresh.isRefreshing = it }
		updateSwipeRefreshEnabled()
	}

	private fun updateSwipeRefreshEnabled() {
		viewBinding.swipeRefreshLayout.isEnabled = contentAtTop
	}

	// Opens the reader for the current/first chapter, mirroring the read button's behaviour: it bails
	// out with a hint if the last-read chapter is no longer available, and surfaces a toast when an
	// incognito session is started so the mode change is obvious.
	private fun openReader(isIncognitoMode: Boolean) {
		val manga = viewModel.getMangaOrNull() ?: return
		if (viewModel.historyInfo.value.isChapterMissing) {
			Snackbar.make(viewBinding.composeView, R.string.chapter_is_missing, Snackbar.LENGTH_SHORT).show()
			return
		}
		val intentBuilder = ReaderIntent.Builder(this)
			.manga(manga)
			.branch(viewModel.selectedBranchValue)
		if (isIncognitoMode) {
			intentBuilder.incognito()
		}
		router.openReader(intentBuilder.build())
		if (isIncognitoMode) {
			Toast.makeText(this, R.string.incognito_mode, Toast.LENGTH_SHORT).show()
		}
	}

	// The back button stays pinned/floating at top-left at all times. The action (overflow) pill is
	// treated as part of the page: it slides up with the content and fades out as you scroll down, then
	// returns as you scroll back to the top. No surface scrim fades in and the toolbar title stays hidden,
	// so the bar never turns into a solid bar on scroll.
	private fun onContentScroll(scrollY: Int) {
		contentAtTop = scrollY <= 0
		updateSwipeRefreshEnabled()
		val actionMenu = viewBinding.toolbar.children.firstOrNull { it is ActionMenuView }
		if (actionMenu != null) {
			actionMenu.translationY = -scrollY.toFloat()
			val fadeDistance = actionMenu.height.takeIf { it > 0 } ?: 1
			actionMenu.alpha = (1f - scrollY.toFloat() / fadeDistance).coerceIn(0f, 1f)
			// Drop it from layout/touch handling once fully faded so it can't catch taps off-screen.
			actionMenu.isVisible = actionMenu.alpha > 0.01f
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		topInset.intValue = bars.top
		bottomInset.intValue = bars.bottom
		viewBinding.appbar.updatePadding(top = bars.top)
		// Match the rest of the app: rest the refresh indicator just under the status bar (the same
		// offset the old details screen used), not pushed down below the whole top bar.
		viewBinding.swipeRefreshLayout.setProgressViewOffset(false, bars.top, bars.top + 180)
		return insets
	}

	private fun showTitleDialog(title: String) {
		val text = title.nullIfEmpty() ?: return
		buildAlertDialog(this) {
			setMessage(text)
			setNegativeButton(R.string.close, null)
			setPositiveButton(androidx.preference.R.string.copy) { _, _ ->
				copyToClipboard(getString(R.string.content_type_manga), text)
			}
		}.show()
	}

	private class PrefetchObserver(
		private val context: android.content.Context,
	) : kotlinx.coroutines.flow.FlowCollector<List<ChapterListItem>?> {
		private var isCalled = false
		override suspend fun emit(value: List<ChapterListItem>?) {
			if (value.isNullOrEmpty() || isCalled) return
			isCalled = true
			val item = value.find { it.isCurrent } ?: value.first()
			MangaPrefetchService.prefetchPages(context, item.chapter)
		}
	}
}
