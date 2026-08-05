package org.koitharu.kotatsu.explore.ui

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ActionMode
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseFragment
import org.koitharu.kotatsu.core.ui.dialog.BigButtonsAlertDialog
import org.koitharu.kotatsu.core.ui.list.ListSelectionController
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.util.ActionModeListener
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.ui.util.SpanSizeResolver
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.findAppCompatDelegate
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.setTabsEnabled
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.FragmentExploreBinding
import org.koitharu.kotatsu.explore.ui.adapter.ExploreAdapter
import org.koitharu.kotatsu.explore.ui.adapter.ExploreListEventListener
import org.koitharu.kotatsu.explore.ui.model.MangaSourceItem
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.list.ui.adapter.bindBadge
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.parsers.model.Manga

@AndroidEntryPoint
class ExploreFragment :
	BaseFragment<FragmentExploreBinding>(),
	ActionModeListener,
	ExploreListEventListener,
	OnListItemClickListener<MangaSourceItem>, ListSelectionController.Callback {

	private val viewModel by viewModels<ExploreViewModel>()
	private var sourceSelectionController: ListSelectionController? = null
	private var manageBadge: BadgeDrawable? = null

	/** Page lists, indexed by page position. Both are created up-front by the pager. */
	private val pages = arrayOfNulls<RecyclerView>(2)
	private var barsInsets: Insets = Insets.NONE

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentExploreBinding {
		return FragmentExploreBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: FragmentExploreBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		sourceSelectionController = ListSelectionController(
			appCompatDelegate = checkNotNull(findAppCompatDelegate()),
			decoration = SourceSelectionDecoration(binding.root.context),
			registryOwner = this,
			callback = this,
		)
		val header = binding.header
		val headerAdapter = ExploreAdapter(
			this,
			this,
			mangaClickListener = { manga, _ -> router.openDetails(manga) },
			onTipClose = { viewModel.dismissLanguageTip() },
		)
		with(header.recyclerViewHeader) {
			adapter = headerAdapter
			layoutManager = LinearLayoutManager(context)
			addItemDecoration(TypedListSpacingDecoration(context, false))
		}
		header.buttonManage.setOnClickListener { router.openSourcesCatalog(isExternalOnly = true) }

		binding.pager.adapter = ExploreSourcesPagerAdapter(::onPageCreated)
		binding.pager.offscreenPageLimit = 1
		// A zero-height pager lays out no pages at all, so nothing would ever be measured. Start at one
		// screen and let updatePagerHeight replace it with the real content height.
		binding.pager.updateLayoutParams { height = resources.displayMetrics.heightPixels }
		TabLayoutMediator(header.tabsKind, binding.pager) { tab, position ->
			tab.setText(if (position == 1) R.string.store_kind_novel else R.string.store_kind_manga)
		}.attach()
		actionModeDelegate.addListener(this)
		addMenuProvider(ExploreMenuProvider(router))
		viewModel.headerContent.observe(viewLifecycleOwner, headerAdapter)
		viewModel.hasExtensionUpdates.observe(viewLifecycleOwner) { hasUpdates ->
			manageBadge = header.buttonManage.bindBadge(manageBadge, if (hasUpdates) "" else null)
		}
		viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(binding.pager, this))
		viewModel.onOpenManga.observeEvent(viewLifecycleOwner, ::onOpenManga)
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.pager))
		viewModel.isGrid.observe(viewLifecycleOwner) { isGrid ->
			pages.forEach { it?.applyLayoutManager(isGrid) }
		}
		viewModel.onShowSuggestionsTip.observeEvent(viewLifecycleOwner) {
			showSuggestionsTip()
		}
	}

	private fun onPageCreated(recyclerView: RecyclerView, isNovel: Boolean) {
		val adapter = ExploreAdapter(
			this,
			this,
			mangaClickListener = { manga, _ -> router.openDetails(manga) },
			onTipClose = { viewModel.dismissLanguageTip() },
		)
		with(recyclerView) {
			this.adapter = adapter
			SpanSizeResolver(this, resources.getDimensionPixelSize(R.dimen.explore_grid_width)).attach()
			addItemDecoration(TypedListSpacingDecoration(context, false))
			checkNotNull(sourceSelectionController).attachToRecyclerView(this)
			applyLayoutManager(viewModel.isGrid.value)
		}
		pages[if (isNovel) 1 else 0] = recyclerView
		viewModel.sources.observe(viewLifecycleOwner) { content ->
			adapter.emit(content[isNovel])
			recyclerView.post(::updatePagerHeight)
		}
	}

	private fun RecyclerView.applyLayoutManager(isGrid: Boolean) {
		val adapter = adapter as? ExploreAdapter ?: return
		layoutManager = if (isGrid) {
			GridLayoutManager(context, 4).also { lm ->
				lm.spanSizeLookup = ExploreGridSpanSizeLookup(adapter, lm)
			}
		} else {
			LinearLayoutManager(context)
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		barsInsets = insets.systemBarsInsets
		val basePadding = v.resources.getDimensionPixelOffset(R.dimen.list_spacing_normal)
		viewBinding?.layoutContent?.setPadding(
			/* left = */ barsInsets.left + basePadding,
			/* top = */ basePadding,
			/* right = */ barsInsets.right + basePadding,
			/* bottom = */ barsInsets.bottom + basePadding,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	/**
	 * ViewPager2 cannot wrap its content, so the pager is given the height of the taller page. Both pages
	 * then keep that height, which is what makes switching tabs a no-op for the scroll position: the
	 * shorter list just ends in empty space. Measured with an unspecified height so the value is the real
	 * content height rather than an estimate.
	 */
	private fun updatePagerHeight() {
		val binding = viewBinding ?: return
		val width = binding.pager.width
		if (width == 0) {
			binding.pager.post(::updatePagerHeight)
			return
		}
		val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
		val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
		val height = pages.maxOf { page ->
			page?.let {
				it.measure(widthSpec, heightSpec)
				it.measuredHeight
			} ?: 0
		}
		if (height > 0 && binding.pager.layoutParams.height != height) {
			binding.pager.updateLayoutParams { this.height = height }
		}
	}

	override fun onDestroyView() {
		actionModeDelegate.removeListener(this)
		pages.fill(null)
		manageBadge = null
		sourceSelectionController = null
		super.onDestroyView()
	}

	override fun onActionModeStarted(mode: ActionMode) {
		viewBinding?.pager?.isUserInputEnabled = false
		viewBinding?.header?.tabsKind?.setTabsEnabled(false)
	}

	override fun onActionModeFinished(mode: ActionMode) {
		viewBinding?.pager?.isUserInputEnabled = true
		viewBinding?.header?.tabsKind?.setTabsEnabled(true)
	}

	override fun onListHeaderClick(item: ListHeader, view: View) {
		if (item.payload == R.id.nav_suggestions) {
			router.openSuggestions()
		} else {
			router.openSourcesCatalog(isExternalOnly = true)
		}
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_local -> router.openList(LocalMangaSource, null, null)
			R.id.button_bookmarks -> router.openBookmarks()
			R.id.button_downloads -> router.openDownloads()
		}
	}

	override fun onItemClick(item: MangaSourceItem, view: View) {
		if (sourceSelectionController?.onItemClick(item.id) == true) {
			return
		}
		router.openList(item.source, null, null)
	}

	override fun onItemLongClick(item: MangaSourceItem, view: View): Boolean {
		return sourceSelectionController?.onItemLongClick(view, item.id) == true
	}

	override fun onItemContextClick(item: MangaSourceItem, view: View): Boolean {
		return sourceSelectionController?.onItemContextClick(view, item.id) == true
	}

	override fun onRetryClick(error: Throwable) = Unit

	override fun onEmptyActionClick() {
		router.openSourcesCatalog(isExternalOnly = true)
	}

	override fun onSelectionChanged(controller: ListSelectionController, count: Int) {
		pages.forEach { it?.invalidateItemDecorations() }
	}

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu
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

	private fun onOpenManga(manga: Manga) {
		router.openDetails(manga)
	}

	private fun showSuggestionsTip() {
		val listener = DialogInterface.OnClickListener { _, which ->
			viewModel.respondSuggestionTip(which == DialogInterface.BUTTON_POSITIVE)
		}
		BigButtonsAlertDialog.Builder(requireContext())
			.setIcon(R.drawable.ic_suggestion)
			.setTitle(R.string.suggestions_enable_prompt)
			.setPositiveButton(R.string.enable, listener)
			.setNegativeButton(R.string.no_thanks, listener)
			.create()
			.show()
	}

}
