package org.koitharu.kotatsu.settings.sources.catalog

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.databinding.ItemExtensionCatalogPageBinding
import org.koitharu.kotatsu.list.ui.adapter.ListHeaderClickListener
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.list.ui.model.ListModel

internal fun dispatchRecyclerAdapterUpdate(
	isComputingLayout: Boolean,
	post: ((() -> Unit) -> Unit),
	update: () -> Unit,
) {
	if (isComputingLayout) post(update) else update()
}

class SourcesCatalogPagesAdapter(
	private val extensionActionListener: ExtensionActionListener,
	private val headerClickListener: ListHeaderClickListener,
	private val listener: Listener,
) : RecyclerView.Adapter<SourcesCatalogPagesAdapter.Holder>() {

	private var pages = listOf<ExtensionCatalogPage>()
	private val content = HashMap<String, List<ListModel>>()
	private val normalContent = HashMap<String, List<ListModel>>()
	private var isSearching = false
	private var refreshing = false
	private var insets = Insets.NONE
	private var recyclerView: RecyclerView? = null

	init {
		setHasStableIds(true)
	}

	fun pageAt(position: Int): ExtensionCatalogPage? = pages.getOrNull(position)

	fun indexOf(pageId: String): Int = pages.indexOfFirst { it.id == pageId }

	fun submitPages(value: List<ExtensionCatalogPage>) {
		dispatchUpdate {
			pages = value
			notifyDataSetChanged()
		}
	}

	fun submitContent(pageId: String, value: List<ListModel>) {
		dispatchUpdate {
			content[pageId] = value
			if (!isSearching) normalContent[pageId] = value
			val position = indexOf(pageId)
			if (position >= 0) notifyItemChanged(position, PAYLOAD_CONTENT)
		}
	}

	fun setSearching(value: Boolean) {
		if (isSearching == value) return
		dispatchUpdate {
			isSearching = value
			notifyItemRangeChanged(0, itemCount, PAYLOAD_CONTENT)
		}
	}

	fun setRefreshing(value: Boolean) {
		dispatchUpdate {
			refreshing = value
			notifyItemRangeChanged(0, itemCount, PAYLOAD_REFRESH)
		}
	}

	fun setInsets(value: Insets) {
		dispatchUpdate {
			insets = value
			notifyItemRangeChanged(0, itemCount, PAYLOAD_INSETS)
		}
	}

	override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
		this.recyclerView = recyclerView
	}

	override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
		if (this.recyclerView === recyclerView) this.recyclerView = null
	}

	override fun getItemId(position: Int): Long = pages[position].id.hashCode().toLong()

	override fun getItemCount(): Int = pages.size

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
		val binding = ItemExtensionCatalogPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
		return Holder(binding)
	}

	override fun onBindViewHolder(holder: Holder, position: Int) {
		holder.bind(pages[position])
	}

	override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
		if (payloads.isEmpty()) {
			onBindViewHolder(holder, position)
		} else {
			holder.update(pages[position])
		}
	}

	inner class Holder(
		val binding: ItemExtensionCatalogPageBinding,
	) : RecyclerView.ViewHolder(binding.root) {

		private val catalogAdapter = SourcesCatalogAdapter(
			extensionActionListener,
			headerClickListener,
			listener::onUpdateAll,
		)

		init {
			binding.recyclerView.apply {
				setHasFixedSize(true)
				addItemDecoration(TypedListSpacingDecoration(context, false))
				adapter = catalogAdapter
				fastScroller.setTrackTouchEnabled(false)
				addOnScrollListener(object : RecyclerView.OnScrollListener() {
					override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
						listener.onPageScrolled()
					}
				})
			}
			binding.swipeRefreshLayout.setOnRefreshListener(listener::onRefresh)
		}

		fun bind(page: ExtensionCatalogPage) {
			update(page)
		}

		fun update(page: ExtensionCatalogPage) {
			catalogAdapter.items = (if (isSearching) content else normalContent)[page.id].orEmpty()
			binding.swipeRefreshLayout.isRefreshing = refreshing
			binding.recyclerView.updatePadding(
				left = insets.left,
				right = insets.right,
				bottom = insets.bottom,
			)
		}
	}

	interface Listener {
		fun onRefresh()
		fun onPageScrolled()
		fun onUpdateAll()
	}

	private fun dispatchUpdate(update: () -> Unit) {
		val recyclerView = recyclerView
		dispatchRecyclerAdapterUpdate(
			isComputingLayout = recyclerView?.isComputingLayout == true,
			post = { deferred -> recyclerView?.post(deferred) ?: deferred() },
			update = update,
		)
	}

	private companion object {
		const val PAYLOAD_CONTENT = "content"
		const val PAYLOAD_REFRESH = "refresh"
		const val PAYLOAD_INSETS = "insets"
	}
}
