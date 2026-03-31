package org.haziffe.dropsauce.details.ui.pager.bookmarks

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ActionMode
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.bookmarks.domain.Bookmark
import org.haziffe.dropsauce.bookmarks.ui.BookmarksSelectionDecoration
import org.haziffe.dropsauce.bookmarks.ui.adapter.BookmarksAdapter
import org.haziffe.dropsauce.core.exceptions.resolve.SnackbarErrorObserver
import org.haziffe.dropsauce.core.nav.ReaderIntent
import org.haziffe.dropsauce.core.nav.dismissParentDialog
import org.haziffe.dropsauce.core.nav.router
import org.haziffe.dropsauce.core.prefs.AppSettings
import org.haziffe.dropsauce.core.ui.BaseFragment
import org.haziffe.dropsauce.core.ui.list.ListSelectionController
import org.haziffe.dropsauce.core.ui.list.OnListItemClickListener
import org.haziffe.dropsauce.core.ui.util.PagerNestedScrollHelper
import org.haziffe.dropsauce.core.ui.util.RecyclerViewOwner
import org.haziffe.dropsauce.core.ui.util.ReversibleActionObserver
import org.haziffe.dropsauce.core.util.ext.consumeAllSystemBarsInsets
import org.haziffe.dropsauce.core.util.ext.findAppCompatDelegate
import org.haziffe.dropsauce.core.util.ext.findParentCallback
import org.haziffe.dropsauce.core.util.ext.observe
import org.haziffe.dropsauce.core.util.ext.observeEvent
import org.haziffe.dropsauce.core.util.ext.systemBarsInsets
import org.haziffe.dropsauce.databinding.FragmentMangaBookmarksBinding
import org.haziffe.dropsauce.details.ui.pager.ChaptersPagesViewModel
import org.haziffe.dropsauce.list.ui.GridSpanResolver
import org.haziffe.dropsauce.list.ui.adapter.ListItemType
import org.haziffe.dropsauce.list.ui.adapter.TypedListSpacingDecoration
import org.haziffe.dropsauce.reader.ui.PageSaveHelper
import org.haziffe.dropsauce.reader.ui.ReaderNavigationCallback
import javax.inject.Inject

@AndroidEntryPoint
class BookmarksFragment : BaseFragment<FragmentMangaBookmarksBinding>(),
	OnListItemClickListener<Bookmark>,
	RecyclerViewOwner,
	ListSelectionController.Callback {

	private val activityViewModel by ChaptersPagesViewModel.ActivityVMLazy(this)
	private val viewModel by viewModels<BookmarksViewModel>()

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var pageSaveHelperFactory: PageSaveHelper.Factory

	override val recyclerView: RecyclerView?
		get() = viewBinding?.recyclerView

	private lateinit var pageSaveHelper: PageSaveHelper
	private var bookmarksAdapter: BookmarksAdapter? = null
	private var spanResolver: GridSpanResolver? = null
	private var selectionController: ListSelectionController? = null

	private val spanSizeLookup = SpanSizeLookup()
	private val listCommitCallback = Runnable {
		spanSizeLookup.invalidateCache()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		pageSaveHelper = pageSaveHelperFactory.create(this)
		activityViewModel.mangaDetails.observe(this, viewModel)
	}

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentMangaBookmarksBinding {
		return FragmentMangaBookmarksBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: FragmentMangaBookmarksBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		spanResolver = GridSpanResolver(binding.root.resources)
		selectionController = ListSelectionController(
			appCompatDelegate = checkNotNull(findAppCompatDelegate()),
			decoration = BookmarksSelectionDecoration(binding.root.context),
			registryOwner = this,
			callback = this,
		)
		bookmarksAdapter = BookmarksAdapter(
			clickListener = this@BookmarksFragment,
			headerClickListener = null,
		)
		viewModel.gridScale.observe(viewLifecycleOwner, ::onGridScaleChanged) // before rv initialization
		with(binding.recyclerView) {
			addItemDecoration(TypedListSpacingDecoration(context, false))
			setHasFixedSize(true)
			PagerNestedScrollHelper(this).bind(viewLifecycleOwner)
			adapter = bookmarksAdapter
			addOnLayoutChangeListener(spanResolver)
			(layoutManager as GridLayoutManager).let {
				it.spanSizeLookup = spanSizeLookup
				it.spanCount = checkNotNull(spanResolver).spanCount
			}
			selectionController?.attachToRecyclerView(this)
		}
		viewModel.content.observe(viewLifecycleOwner) { bookmarksAdapter?.setItems(it, listCommitCallback) }

		viewModel.onError.observeEvent(
			viewLifecycleOwner,
			SnackbarErrorObserver(binding.recyclerView, this),
		)
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.recyclerView))
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding?.recyclerView?.setPadding(
			barsInsets.left,
			barsInsets.top,
			barsInsets.right,
			barsInsets.bottom,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onDestroyView() {
		spanResolver = null
		bookmarksAdapter = null
		selectionController = null
		spanSizeLookup.invalidateCache()
		super.onDestroyView()
	}

	override fun onItemClick(item: Bookmark, view: View) {
		if (selectionController?.onItemClick(item.pageId) == true) {
			return
		}
		val listener = findParentCallback(ReaderNavigationCallback::class.java)
		if (listener != null && listener.onBookmarkSelected(item)) {
			dismissParentDialog()
		} else {
			val intent = ReaderIntent.Builder(view.context)
				.manga(activityViewModel.getMangaOrNull() ?: return)
				.bookmark(item)
				.incognito()
				.build()
			router.openReader(intent)
		}
	}

	override fun onItemLongClick(item: Bookmark, view: View): Boolean {
		return selectionController?.onItemLongClick(view, item.pageId) == true
	}

	override fun onItemContextClick(item: Bookmark, view: View): Boolean {
		return selectionController?.onItemContextClick(view, item.pageId) == true
	}

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

	private fun onGridScaleChanged(scale: Float) {
		spanSizeLookup.invalidateCache()
		spanResolver?.setGridSize(scale, requireViewBinding().recyclerView)
	}

	private inner class SpanSizeLookup : GridLayoutManager.SpanSizeLookup() {

		init {
			isSpanIndexCacheEnabled = true
			isSpanGroupIndexCacheEnabled = true
		}

		override fun getSpanSize(position: Int): Int {
			val total = (viewBinding?.recyclerView?.layoutManager as? GridLayoutManager)?.spanCount ?: return 1
			return when (bookmarksAdapter?.getItemViewType(position)) {
				ListItemType.PAGE_THUMB.ordinal -> 1
				else -> total
			}
		}

		fun invalidateCache() {
			invalidateSpanGroupIndexCache()
			invalidateSpanIndexCache()
		}
	}
}

