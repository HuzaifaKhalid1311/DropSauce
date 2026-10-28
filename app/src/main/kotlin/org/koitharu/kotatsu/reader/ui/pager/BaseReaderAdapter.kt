package org.koitharu.kotatsu.reader.ui.pager

import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.util.ext.resetTransformations
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Suppress("LeakingThis")
abstract class BaseReaderAdapter<H : BasePageHolder<*>>(
	private val loader: PageLoader,
	private val readerSettingsProducer: ReaderSettings.Producer,
	private val networkState: NetworkState,
	private val exceptionResolver: ExceptionResolver,
) : RecyclerView.Adapter<H>() {

	private val differ = AsyncListDiffer(this, DiffCallback())

	/** The pages as the reader gave them, without the second halves of split wide pages. */
	private var sourceItems: List<ReaderPage> = emptyList()
	private val splitPages = HashSet<ReaderPage>()

	/** Right-to-left lists put a split page's second half before it, so it reads next. */
	protected open val isReversed: Boolean
		get() = false

	val hasItems: Boolean
		get() = itemCount != 0

	val sourceItemCount: Int
		get() = sourceItems.size

	init {
		stateRestorationPolicy = StateRestorationPolicy.PREVENT
	}

	override fun onBindViewHolder(holder: H, position: Int) {
		holder.bind(differ.currentList[position])
	}

	override fun onViewRecycled(holder: H) {
		holder.onRecycled()
		holder.itemView.resetTransformations()
		super.onViewRecycled(holder)
	}

	override fun onViewAttachedToWindow(holder: H) {
		super.onViewAttachedToWindow(holder)
		holder.onAttachedToWindow()
	}

	override fun onViewDetachedFromWindow(holder: H) {
		holder.onDetachedFromWindow()
		super.onViewDetachedFromWindow(holder)
	}

	open fun getItem(position: Int): ReaderPage = differ.currentList[position]

	open fun getItemOrNull(position: Int) = differ.currentList.getOrNull(position)

	final override fun getItemCount() = differ.currentList.size

	final override fun onCreateViewHolder(
		parent: ViewGroup,
		viewType: Int,
	): H = onCreateViewHolder(parent, loader, readerSettingsProducer, networkState, exceptionResolver)

	suspend fun setItems(items: List<ReaderPage>) = suspendCoroutine { cont ->
		sourceItems = items
		differ.submitList(withSplits(items)) {
			cont.resume(Unit)
		}
	}

	/** A wide page turned out to be split: add the pager item for its second half (the Mihon way). */
	fun onPageSplit(page: ReaderPage) {
		if (splitPages.add(page)) {
			differ.submitList(withSplits(sourceItems))
		}
	}

	fun clearSplits() {
		if (splitPages.isNotEmpty()) {
			splitPages.clear()
			differ.submitList(sourceItems)
		}
	}

	fun toSourcePosition(position: Int): Int {
		val page = getItemOrNull(position)?.copy(isSecondHalf = false) ?: return position
		return sourceItems.indexOf(page).takeIf { it >= 0 } ?: position
	}

	fun toAdapterPosition(sourcePosition: Int): Int {
		val page = sourceItems.getOrNull(sourcePosition) ?: return sourcePosition
		return differ.currentList.indexOf(page).takeIf { it >= 0 } ?: sourcePosition
	}

	private fun withSplits(items: List<ReaderPage>): List<ReaderPage> {
		if (splitPages.isEmpty()) return items
		val result = ArrayList<ReaderPage>(items.size + splitPages.size)
		for (page in items) {
			if (page !in splitPages) {
				result.add(page)
				continue
			}
			val secondHalf = page.copy(isSecondHalf = true)
			if (isReversed) {
				result.add(secondHalf)
				result.add(page)
			} else {
				result.add(page)
				result.add(secondHalf)
			}
		}
		return result
	}

	protected abstract fun onCreateViewHolder(
		parent: ViewGroup,
		loader: PageLoader,
		readerSettingsProducer: ReaderSettings.Producer,
		networkState: NetworkState,
		exceptionResolver: ExceptionResolver,
	): H

	private class DiffCallback : DiffUtil.ItemCallback<ReaderPage>() {

		override fun areItemsTheSame(oldItem: ReaderPage, newItem: ReaderPage): Boolean {
			return oldItem.id == newItem.id && oldItem.chapterId == newItem.chapterId &&
				oldItem.isSecondHalf == newItem.isSecondHalf
		}

		override fun areContentsTheSame(oldItem: ReaderPage, newItem: ReaderPage): Boolean {
			return oldItem == newItem
		}
	}
}
