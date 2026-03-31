package org.haziffe.dropsauce.bookmarks.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.view.ActionMode
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.bookmarks.domain.Bookmark
import org.haziffe.dropsauce.bookmarks.ui.adapter.BookmarksAdapter
import org.haziffe.dropsauce.core.exceptions.resolve.SnackbarErrorObserver
import org.haziffe.dropsauce.core.nav.ReaderIntent
import org.haziffe.dropsauce.core.nav.router
import org.haziffe.dropsauce.core.prefs.AppSettings
import org.haziffe.dropsauce.core.ui.BaseFragment
import org.haziffe.dropsauce.core.ui.list.ListSelectionController
import org.haziffe.dropsauce.core.ui.list.OnListItemClickListener
import org.haziffe.dropsauce.core.ui.list.fastscroll.FastScroller
import org.haziffe.dropsauce.core.ui.util.ReversibleActionObserver
import org.haziffe.dropsauce.core.util.ext.consumeAllSystemBarsInsets
import org.haziffe.dropsauce.core.util.ext.findAppCompatDelegate
import org.haziffe.dropsauce.core.util.ext.observe
import org.haziffe.dropsauce.core.util.ext.observeEvent
import org.haziffe.dropsauce.core.util.ext.systemBarsInsets
import org.haziffe.dropsauce.databinding.FragmentListSimpleBinding
import org.haziffe.dropsauce.list.ui.GridSpanResolver
import org.haziffe.dropsauce.list.ui.adapter.ListHeaderClickListener
import org.haziffe.dropsauce.list.ui.adapter.ListItemType
import org.haziffe.dropsauce.list.ui.adapter.ListStateHolderListener
import org.haziffe.dropsauce.list.ui.adapter.TypedListSpacingDecoration
import org.haziffe.dropsauce.list.ui.model.ListHeader
import org.haziffe.dropsauce.main.ui.owners.AppBarOwner
import org.haziffe.dropsauce.parsers.model.Manga
import org.haziffe.dropsauce.reader.ui.PageSaveHelper
import javax.inject.Inject

@AndroidEntryPoint
class AllBookmarksFragment :
	BaseFragment<FragmentListSimpleBinding>(),
	ListStateHolderListener,
	OnListItemClickListener<Bookmark>,
	ListSelectionController.Callback,
	FastScroller.FastScrollListener, ListHeaderClickListener {

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var pageSaveHelperFactory: PageSaveHelper.Factory

	private lateinit var pageSaveHelper: PageSaveHelper
	private val viewModel by viewModels<AllBookmarksViewModel>()
	private var bookmarksAdapter: BookmarksAdapter? = null
	private var selectionController: ListSelectionController? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		pageSaveHelper = pageSaveHelperFactory.create(this)
	}

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	): FragmentListSimpleBinding {
		return FragmentListSimpleBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(
		binding: FragmentListSimpleBinding,
		savedInstanceState: Bundle?,
	) {
		super.onViewBindingCreated(binding, savedInstanceState)
		selectionController = ListSelectionController(
			appCompatDelegate = checkNotNull(findAppCompatDelegate()),
			decoration = BookmarksSelectionDecoration(binding.root.context),
			registryOwner = this,
			callback = this,
		)
		bookmarksAdapter = BookmarksAdapter(
			clickListener = this,
			headerClickListener = this,
		)
		val spanSizeLookup = SpanSizeLookup()
		with(binding.recyclerView) {
			setHasFixedSize(true)
			val spanResolver = GridSpanResolver(resources)
			addItemDecoration(TypedListSpacingDecoration(context, false))
			adapter = bookmarksAdapter
			addOnLayoutChangeListener(spanResolver)
			spanResolver.setGridSize(settings.gridSize / 100f, this)
			val lm = GridLayoutManager(context, spanResolver.spanCount)
			lm.spanSizeLookup = spanSizeLookup
			layoutManager = lm
			selectionController?.attachToRecyclerView(this)
		}
		viewModel.content.observe(viewLifecycleOwner) {
			bookmarksAdapter?.setItems(it, spanSizeLookup)
		}
		viewModel.onError.observeEvent(
			viewLifecycleOwner,
			SnackbarErrorObserver(binding.recyclerView, this),
		)
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.recyclerView))
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		val basePadding = resources.getDimensionPixelOffset(R.dimen.list_spacing_normal)
		viewBinding?.recyclerView?.setPadding(
			barsInsets.left + basePadding,
			barsInsets.top + basePadding,
			barsInsets.right + basePadding,
			barsInsets.bottom + basePadding,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onDestroyView() {
		super.onDestroyView()
		bookmarksAdapter = null
		selectionController = null
	}

	override fun onItemClick(item: Bookmark, view: View) {
		if (selectionController?.onItemClick(item.pageId) != true) {
			val intent = ReaderIntent.Builder(view.context)
				.bookmark(item)
				.incognito()
				.build()
			router.openReader(intent)
			Toast.makeText(view.context, R.string.incognito_mode, Toast.LENGTH_SHORT).show()
		}
	}

	override fun onListHeaderClick(item: ListHeader, view: View) {
		val manga = item.payload as? Manga ?: return
		router.openDetails(manga)
	}

	override fun onItemLongClick(item: Bookmark, view: View): Boolean {
		return selectionController?.onItemLongClick(view, item.pageId) == true
	}

	override fun onItemContextClick(item: Bookmark, view: View): Boolean {
		return selectionController?.onItemContextClick(view, item.pageId) == true
	}

	override fun onRetryClick(error: Throwable) = Unit

	override fun onEmptyActionClick() = Unit

	override fun onFastScrollStart(fastScroller: FastScroller) {
		(activity as? AppBarOwner)?.appBar?.setExpanded(false, true)
	}

	override fun onFastScrollStop(fastScroller: FastScroller) = Unit

	override fun onSelectionChanged(controller: ListSelectionController, count: Int) {
		requireViewBinding().recyclerView.invalidateItemDecorations()
	}

	override fun onCreateActionMode(
		controller: ListSelectionController,
		menuInflater: MenuInflater,
		menu: Menu,
	): Boolean {
		menuInflater.inflate(R.menu.mode_bookmarks, menu)
		return true
	}

	override fun onActionItemClicked(
		controller: ListSelectionController,
		mode: ActionMode?,
		item: MenuItem,
	): Boolean {
		return when (item.itemId) {
			R.id.action_remove -> {
				val ids = selectionController?.snapshot() ?: return false
				viewModel.removeBookmarks(ids)
				mode?.finish()
				true
			}

			R.id.action_save -> {
				viewModel.savePages(pageSaveHelper, selectionController?.snapshot() ?: return false)
				mode?.finish()
				true
			}

			else -> false
		}
	}

	private inner class SpanSizeLookup : GridLayoutManager.SpanSizeLookup(), Runnable {

		init {
			isSpanIndexCacheEnabled = true
			isSpanGroupIndexCacheEnabled = true
		}

		override fun getSpanSize(position: Int): Int {
			val total = (viewBinding?.recyclerView?.layoutManager as? GridLayoutManager)?.spanCount
				?: return 1
			return when (bookmarksAdapter?.getItemViewType(position)) {
				ListItemType.PAGE_THUMB.ordinal -> 1
				else -> total
			}
		}

		override fun run() {
			invalidateSpanGroupIndexCache()
			invalidateSpanIndexCache()
		}
	}
}
