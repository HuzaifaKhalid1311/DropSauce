package org.haziffe.dropsauce.tracker.ui.feed

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.drop
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.core.exceptions.resolve.SnackbarErrorObserver
import org.haziffe.dropsauce.core.nav.router
import org.haziffe.dropsauce.core.ui.BaseFragment
import org.haziffe.dropsauce.core.ui.list.PaginationScrollListener
import org.haziffe.dropsauce.core.ui.list.RecyclerScrollKeeper
import org.haziffe.dropsauce.core.ui.util.MenuInvalidator
import org.haziffe.dropsauce.core.ui.util.RecyclerViewOwner
import org.haziffe.dropsauce.core.ui.util.ReversibleActionObserver
import org.haziffe.dropsauce.core.ui.widgets.TipView
import org.haziffe.dropsauce.core.util.ext.addMenuProvider
import org.haziffe.dropsauce.core.util.ext.consumeAll
import org.haziffe.dropsauce.core.util.ext.observe
import org.haziffe.dropsauce.core.util.ext.observeEvent
import org.haziffe.dropsauce.databinding.FragmentListBinding
import org.haziffe.dropsauce.list.domain.ListFilterOption
import org.haziffe.dropsauce.list.ui.adapter.MangaListListener
import org.haziffe.dropsauce.list.ui.adapter.TypedListSpacingDecoration
import org.haziffe.dropsauce.list.ui.model.ListHeader
import org.haziffe.dropsauce.list.ui.model.MangaListModel
import org.haziffe.dropsauce.list.ui.size.StaticItemSizeResolver
import org.haziffe.dropsauce.parsers.model.Manga
import org.haziffe.dropsauce.parsers.model.MangaTag
import org.haziffe.dropsauce.tracker.ui.feed.adapter.FeedAdapter
import javax.inject.Inject

@AndroidEntryPoint
class FeedFragment :
	BaseFragment<FragmentListBinding>(),
	PaginationScrollListener.Callback,
	RecyclerViewOwner,
	MangaListListener,
	SwipeRefreshLayout.OnRefreshListener {

	@Inject
	lateinit var coil: ImageLoader

	private val viewModel by viewModels<FeedViewModel>()

	override val recyclerView: RecyclerView?
		get() = viewBinding?.recyclerView

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentListBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: FragmentListBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val sizeResolver = StaticItemSizeResolver(resources.getDimensionPixelSize(R.dimen.smaller_grid_width))
		val feedAdapter = FeedAdapter(this, sizeResolver) { item, v ->
			viewModel.onItemClick(item)
			router.openDetails(item.toMangaWithOverride())
		}
		with(binding.recyclerView) {
			val paddingVertical = resources.getDimensionPixelSize(R.dimen.list_spacing_normal)
			setPadding(0, paddingVertical, 0, paddingVertical)
			layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
			adapter = feedAdapter
			setHasFixedSize(true)
			addOnScrollListener(PaginationScrollListener(4, this@FeedFragment))
			addItemDecoration(TypedListSpacingDecoration(context, true))
			RecyclerScrollKeeper(this).attach()
		}
		binding.swipeRefreshLayout.setOnRefreshListener(this)
		addMenuProvider(FeedMenuProvider(binding.recyclerView, viewModel))

		viewModel.isHeaderEnabled.drop(1).observe(viewLifecycleOwner, MenuInvalidator(requireActivity()))
		viewModel.content.observe(viewLifecycleOwner, feedAdapter)
		viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(binding.recyclerView, this))
		viewModel.onActionDone.observeEvent(viewLifecycleOwner, ReversibleActionObserver(binding.recyclerView))
		viewModel.isRunning.observe(viewLifecycleOwner, this::onIsTrackerRunningChanged)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		val paddingVertical = resources.getDimensionPixelSize(R.dimen.list_spacing_normal)
		viewBinding?.recyclerView?.setPadding(
			left = barsInsets.left,
			top = paddingVertical,
			right = barsInsets.right,
			bottom = barsInsets.bottom + paddingVertical,
		)
		return insets.consumeAll(typeMask)
	}

	override fun onRefresh() {
		viewModel.update()
	}

	override fun onFilterOptionClick(option: ListFilterOption) = viewModel.toggleFilterOption(option)

	override fun onRetryClick(error: Throwable) = Unit

	override fun onFilterClick(view: View?) = Unit

	override fun onEmptyActionClick() = Unit

	override fun onPrimaryButtonClick(tipView: TipView) = Unit

	override fun onSecondaryButtonClick(tipView: TipView) = Unit

	override fun onListHeaderClick(item: ListHeader, view: View) {
		router.openMangaUpdates()
	}

	private fun onIsTrackerRunningChanged(isRunning: Boolean) {
		requireViewBinding().swipeRefreshLayout.isRefreshing = isRunning
	}

	override fun onScrolledToEnd() {
		viewModel.requestMoreItems()
	}

	override fun onItemClick(item: MangaListModel, view: View) {
		router.openDetails(item.toMangaWithOverride())
	}

	override fun onReadClick(manga: Manga, view: View) = Unit

	override fun onTagClick(manga: Manga, tag: MangaTag, view: View) = Unit
}
