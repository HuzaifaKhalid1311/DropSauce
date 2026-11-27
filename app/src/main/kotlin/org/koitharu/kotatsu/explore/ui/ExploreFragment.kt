package org.koitharu.kotatsu.explore.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ActionMode
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import coil3.ImageLoader
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.homeUrl
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.dialog.DialogAction
import org.koitharu.kotatsu.core.ui.dialog.showActionChoiceDialog
import org.koitharu.kotatsu.core.ui.dialog.showComposeDialog
import org.koitharu.kotatsu.core.ui.dialog.showMultiChoiceDialog
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.ui.list.decor.AbstractSelectionItemDecoration
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.ui.menu.MenuPopup
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.findAppCompatDelegate
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.core.util.ext.toSet
import org.koitharu.kotatsu.databinding.FragmentExploreBinding
import org.koitharu.kotatsu.explore.ui.model.MangaSourceItem
import org.koitharu.kotatsu.list.ui.model.TipModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.settings.compose.ChoiceDialogCard
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import javax.inject.Inject

@AndroidEntryPoint
class ExploreFragment :
	BaseFragment<FragmentExploreBinding>(),
	ExploreActions,
	ListSelectionController.Callback {

	@Inject
	lateinit var imageLoader: ImageLoader

	private val viewModel by viewModels<ExploreViewModel>()
	private var selectionController: ListSelectionController? = null

	private val selectedIds = mutableStateOf(emptySet<Long>())

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentExploreBinding {
		return FragmentExploreBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: FragmentExploreBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		selectionController = ListSelectionController(
			appCompatDelegate = checkNotNull(findAppCompatDelegate()),
			// Only holds the checked ids: the Compose items draw their own selected look
			decoration = object : AbstractSelectionItemDecoration() {
				override fun getItemId(parent: androidx.recyclerview.widget.RecyclerView, child: View) =
					androidx.recyclerview.widget.RecyclerView.NO_ID
			},
			registryOwner = this,
			callback = this,
		)
		binding.composeView.clipChildren = false
		binding.composeView.clipToPadding = false
		binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		binding.composeView.setContent {
			val suggestions by viewModel.suggestions.collectAsState()
			val sources by viewModel.sources.collectAsState()
			val layout by viewModel.layout.collectAsState()
			val gridColumns by viewModel.gridColumns.collectAsState()
			val isNovelFirst by viewModel.novelTabFirst.collectAsState()
			val isSingleKind by viewModel.singleKind.collectAsState()
			val hasUpdates by viewModel.hasExtensionUpdates.collectAsState()
			DropSauceTheme {
				ExploreScreen(
					suggestions = suggestions,
					sources = sources,
					layout = layout,
					gridColumns = gridColumns,
					isNovelFirst = isNovelFirst,
					isSingleKind = isSingleKind,
					hasExtensionUpdates = hasUpdates,
					selectedIds = selectedIds.value,
					imageLoader = imageLoader,
					actions = this,
				)
			}
		}
		addMenuProvider(ExploreMenuProvider(router, ::showLanguageFilterDialog))
		var seenIds = emptySet<Long>()
		viewModel.sources.observe(viewLifecycleOwner) { content ->
			val validIds = buildSet {
				for (item in content.manga) if (item is MangaSourceItem) add(item.id)
				for (item in content.novel) if (item is MangaSourceItem) add(item.id)
			}
			// Only drops extensions that were listed and went away: a selection restored after the app was
			// closed in the background can arrive before the extensions finish loading.
			val controller = selectionController ?: return@observe
			controller.retainAll(validIds + controller.snapshot().filterNot { it in seenIds })
			seenIds = seenIds + validIds
		}
		viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(binding.composeView, this))
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.composeView))
		viewModel.onShowSuggestionsTip.observeEvent(viewLifecycleOwner) { showSuggestionsTip() }
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.systemBarsInsets
		val base = v.resources.getDimensionPixelOffset(R.dimen.list_spacing_normal)
		viewBinding?.composeView?.setPadding(bars.left + base, base, bars.right + base, bars.bottom + base)
		return insets.consumeAllSystemBarsInsets()
	}

	/** Back to the top of Explore, for reselecting its nav item. */
	fun scrollToTop(smooth: Boolean) {
		val scrollView = viewBinding?.scrollView ?: return
		if (smooth) scrollView.smoothScrollTo(0, 0) else scrollView.scrollTo(0, 0)
	}

	override fun onDestroyView() {
		selectionController = null
		super.onDestroyView()
	}

	// region ExploreActions

	override fun onLocalClick() {
		router.openList(LocalMangaSource, null, null)
	}

	override fun onBookmarksClick() {
		router.openBookmarks()
	}

	override fun onDownloadsClick() {
		router.openDownloads()
	}

	override fun onSuggestionsMoreClick() {
		router.openSuggestions()
	}

	override fun onMangaClick(manga: Manga) {
		router.openDetails(manga)
	}

	override fun onMangaLongClick(manga: Manga, view: View) {
		val popup = MenuPopup(view)
		popup.menu.add(R.string.dont_suggest_again).setIcon(R.drawable.ic_dont_suggest)
		popup.setForceShowIcon(true)
		popup.setOnMenuItemClickListener {
			viewModel.hideSuggestion(manga)
			true
		}
		popup.show()
	}

	override fun onManageClick() {
		actionModeDelegate.finishActionMode()
		router.openSourcesCatalog()
	}

	override fun onCycleLayoutClick() {
		view?.hapticFeedback(HapticEffect.LIGHT_CLICK)
		viewModel.cycleLayout()
	}

	override fun onSourceClick(item: MangaSourceItem) {
		val controller = selectionController ?: return
		if (controller.onItemClick(null, item.id)) {
			val isChecked = item.id in controller.peekCheckedIds()
			view?.hapticFeedback(if (isChecked) HapticEffect.LIGHT_CLICK else HapticEffect.DESELECT)
		} else {
			router.openList(item.source, null, null)
		}
	}

	override fun onSourceLongClick(item: MangaSourceItem) {
		if (selectionController?.onItemLongClick(null, item.id) == true) {
			view?.hapticFeedback(HapticEffect.LONG_PRESS)
		}
	}

	override fun onSourceSettingsClick(item: MangaSourceItem) {
		if ((selectionController?.count ?: 0) > 0) {
			onSourceClick(item)
			return
		}
		router.openSourceSettings(item.source)
	}

	override fun onSourceWebsiteClick(item: MangaSourceItem) {
		if ((selectionController?.count ?: 0) > 0) {
			onSourceClick(item)
			return
		}
		val url = item.source.homeUrl
		if (url == null) {
			Snackbar.make(requireViewBinding().composeView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
			return
		}
		router.openBrowser(url = url, source = item.source, title = item.source.getTitle(requireContext()))
	}

	override fun onTipClose(tip: TipModel) {
		viewModel.dismissLanguageTip()
	}

	/**
	 * Holding the Manga / Novels switch picks which kind leads (and so opens first), or shows just one.
	 * Both pages already exist, so this only swaps which list each page shows.
	 */
	override fun onKindLongClick() {
		view?.hapticFeedback(HapticEffect.LONG_PRESS)
		showComposeDialog(requireContext()) { dismiss ->
			ChoiceDialogCard(
				title = stringResource(R.string.explore_tabs),
				entries = listOf(
					stringResource(R.string.manga_first),
					stringResource(R.string.novels_first),
					stringResource(R.string.manga_only),
					stringResource(R.string.novels_only),
				),
				selectedIndex = (if (viewModel.isNovelTabFirst) 1 else 0) + (if (viewModel.isSingleKind) 2 else 0),
				onSelect = { index ->
					viewModel.isNovelTabFirst = index % 2 == 1
					viewModel.isSingleKind = index >= 2
				},
				onDismiss = dismiss,
				icon = R.drawable.ic_swap,
			)
		}
	}

	// endregion

	/**
	 * Multi-select of the languages spoken by the installed sources. Unchecking one hides every
	 * source in that language from Explore; the set is stored globally, so it survives installs.
	 */
	private fun showLanguageFilterDialog() {
		val languages = viewModel.sourceLanguages()
		if (languages.isEmpty()) {
			Snackbar.make(requireViewBinding().composeView, R.string.no_extensions_installed, Snackbar.LENGTH_SHORT).show()
			return
		}
		// The same tick-box picker as "merge scanlators"; the count is the number of sources in that language
		showMultiChoiceDialog(
			context = requireContext(),
			icon = R.drawable.ic_language_filter,
			title = getString(R.string.filter_by_language),
			options = languages.map { it.displayName },
			counts = languages.map { it.sourceCount.toString() },
			selectedIndices = languages.indices.filterTo(HashSet()) { languages[it].isEnabled },
			confirmLabel = getString(android.R.string.ok),
			onConfirm = { selected ->
				viewModel.setHiddenLanguages(
					languages.filterIndexed { index, _ -> index !in selected }
						.mapTo(HashSet()) { it.code },
				)
			},
		)
	}

	// region Selection

	override fun onSelectionChanged(controller: ListSelectionController, count: Int) {
		selectedIds.value = controller.peekCheckedIds().toSet()
	}

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu,
	): Boolean {
		menuInflater.inflate(R.menu.mode_source, menu)
		return true
	}

	override fun onPrepareActionMode(controller: ListSelectionController, mode: ActionMode?, menu: Menu): Boolean {
		val selectedSources = viewModel.sourcesSnapshot(controller.peekCheckedIds())
		val isSingleSelection = selectedSources.size == 1
		menu.findItem(R.id.action_settings)?.isVisible = isSingleSelection
		menu.findItem(R.id.action_shortcut)?.isVisible = isSingleSelection
		menu.findItem(R.id.action_pin)?.isVisible = selectedSources.all { !it.isPinned }
		menu.findItem(R.id.action_unpin)?.isVisible = selectedSources.all { it.isPinned }
		menu.findItem(R.id.action_disable)?.isVisible = false
		menu.findItem(R.id.action_delete)?.isVisible = false
		return super.onPrepareActionMode(controller, mode, menu)
	}

	override fun onActionItemClicked(controller: ListSelectionController, mode: ActionMode?, item: MenuItem): Boolean {
		val selectedSources = viewModel.sourcesSnapshot(controller.peekCheckedIds())
		if (selectedSources.isEmpty()) {
			return false
		}
		when (item.itemId) {
			R.id.action_settings -> {
				val source = selectedSources.singleOrNull() ?: return false
				router.openSourceSettings(source)
				mode?.finish()
			}

			R.id.action_shortcut -> {
				val source = selectedSources.singleOrNull() ?: return false
				viewModel.requestPinShortcut(source)
				mode?.finish()
			}

			R.id.action_pin -> {
				viewModel.setSourcesPinned(selectedSources, isPinned = true)
				mode?.finish()
			}

			R.id.action_unpin -> {
				viewModel.setSourcesPinned(selectedSources, isPinned = false)
				mode?.finish()
			}

			R.id.action_hide -> {
				viewModel.hideSources(selectedSources)
				mode?.finish()
			}

			else -> return false
		}
		return true
	}

	// endregion

	private fun showSuggestionsTip() {
		val context = requireContext()
		showActionChoiceDialog(
			context = context,
			icon = R.drawable.ic_suggestion,
			title = context.getString(R.string.suggestions),
			message = context.getString(R.string.suggestions_enable_prompt),
			actions = listOf(
				DialogAction(context.getString(R.string.enable)) {
					viewModel.respondSuggestionTip(true)
				},
				DialogAction(context.getString(R.string.no_thanks)) {
					viewModel.respondSuggestionTip(false)
				},
			),
			dismissLabel = context.getString(R.string.close),
		)
	}
}
