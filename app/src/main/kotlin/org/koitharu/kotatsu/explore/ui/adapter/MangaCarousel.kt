package org.koitharu.kotatsu.explore.ui.adapter

import android.widget.ImageView
import androidx.core.view.isInvisible
import com.google.android.material.carousel.MultiBrowseCarouselStrategy
import com.hannesdorfmann.adapterdelegates4.ListDelegationAdapter
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.widgets.CarouselRecyclerView
import org.koitharu.kotatsu.core.ui.widgets.SafeCarouselLayoutManager
import org.koitharu.kotatsu.databinding.ItemMangaCarouselBinding
import org.koitharu.kotatsu.explore.ui.ExploreViewModel
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.parsers.model.Manga

/** The details page's related titles: the same tiles as Explore's suggestions, so both look the same. */
fun CarouselRecyclerView.setUpMangaCarousel(
	itemClickListener: OnListItemClickListener<Manga>,
): BaseListAdapter<ListModel> {
	val adapter = BaseListAdapter<ListModel>()
		.addDelegate(ListItemType.MANGA_CAROUSEL, carouselItemAD(itemClickListener))
	this.adapter = adapter
	layoutManager = SafeCarouselLayoutManager(MultiBrowseCarouselStrategy())
	return adapter
}

/**
 * Explore's suggestions: the same carousel, but it wraps around endlessly. An empty list shows blank
 * skeleton tiles with exactly the loaded carousel's tile count, sizes and shapes. The titles repeat in
 * cycles with a loop icon marking each restart, over a long but finite list (CarouselLayoutManager
 * keeps its scroll offset in an Int) that opens in the middle, so neither end is reachable in practice.
 */
fun CarouselRecyclerView.setUpLoopingMangaCarousel(
	itemClickListener: OnListItemClickListener<Manga>,
): LoopingCarouselAdapter {
	val adapter = LoopingCarouselAdapter(itemClickListener)
	this.adapter = adapter
	layoutManager = SafeCarouselLayoutManager(MultiBrowseCarouselStrategy())
	return adapter
}

class LoopingCarouselAdapter(
	itemClickListener: OnListItemClickListener<Manga>,
) : ListDelegationAdapter<List<ListModel>>() {

	private var models: List<ListModel>? = null
	private var cycle: List<ListModel> = emptyList()

	init {
		delegatesManager
			.addDelegate(carouselItemAD(itemClickListener))
			.addDelegate(carouselSkeletonItemAD())
			.addDelegate(carouselLoopItemAD())
	}

	/**
	 * Shows [newModels] (the skeleton while empty) and returns the position to scroll to, or -1 when
	 * nothing changed, so recompositions don't reset the scroll. When the old and new titles overlap
	 * (one was hidden), the title at [anchorPosition] stays where it was; otherwise it opens at the start.
	 * Titles that all fit in [maxVisibleTiles] at once don't loop, or they'd show up twice side by side.
	 * A fresh adapter (after rotation) opens on [restoreIndex], a [cycleIndexOf] saved from the old one.
	 */
	fun submit(newModels: List<ListModel>, anchorPosition: Int, maxVisibleTiles: Int, restoreIndex: Int = -1): Int {
		val oldModels = models
		val oldCycle = cycle
		if (newModels == oldModels) return -1
		models = newModels
		val isLooping = newModels.size >= maxVisibleTiles
		cycle = if (isLooping) newModels + CarouselLoopItem else newModels
		val looped = if (newModels.isEmpty()) null else LoopedList(cycle, if (isLooping) LOOP_CYCLES else 1)
		items = looped ?: carouselSkeleton
		@Suppress("NotifyDataSetChanged") // the whole cycle is replaced and repositioned
		notifyDataSetChanged()
		looped ?: return 0
		if (oldModels.isNullOrEmpty() && restoreIndex >= 0) {
			return looped.startPosition + restoreIndex.coerceAtMost(cycle.size - 1)
		}
		if (oldModels.isNullOrEmpty() || anchorPosition < 0) return looped.startPosition
		val anchorIndex = anchorPosition % oldCycle.size
		val anchor = oldCycle[anchorIndex]
		val newIndex = newModels.indexOfFirst { it.areItemsTheSame(anchor) }
		return when {
			anchor == CarouselLoopItem -> looped.startPosition - 1
			newIndex >= 0 -> looped.startPosition + newIndex
			// The anchor itself was removed: the next title slides into its place.
			newModels.none { new -> oldModels.any { it.areItemsTheSame(new) } } -> looped.startPosition
			else -> looped.startPosition + anchorIndex.coerceAtMost(newModels.size)
		}.coerceIn(0, looped.size - 1)
	}

	/** Where [position] sits within one cycle of titles, or -1 while the skeleton shows. */
	fun cycleIndexOf(position: Int): Int = if (models.isNullOrEmpty() || position < 0) -1 else position % cycle.size
}

private class LoopedList(private val cycle: List<ListModel>, cycles: Int) : AbstractList<ListModel>() {

	override val size = cycle.size * cycles

	/** The first title of a cycle in the middle of the list. */
	val startPosition = cycle.size * (cycles / 2)

	override fun get(index: Int) = cycle[index % cycle.size]
}

private const val LOOP_CYCLES = 1000

private fun carouselItemAD(
	itemClickListener: OnListItemClickListener<Manga>,
) = adapterDelegateViewBinding<MangaListModel, ListModel, ItemMangaCarouselBinding>(
	{ layoutInflater, parent -> ItemMangaCarouselBinding.inflate(layoutInflater, parent, false) },
) {

	binding.root.setOnClickListener { v ->
		itemClickListener.onItemClick(item.toMangaWithOverride(), v)
	}
	binding.root.setOnLongClickListener { v ->
		itemClickListener.onItemLongClick(item.toMangaWithOverride(), v)
	}
	binding.fadeTitleWithMask()

	bind {
		binding.textViewTitle.text = item.title
		// Same cache key as the library lists, so covers saved ahead of time (suggestions) are found.
		binding.imageViewCover.setImageAsync(item.coverUrl, item.manga)
	}
}

/** A blank suggestions tile shown while the carousel loads: the empty cover is the grey skeleton block. */
private fun carouselSkeletonItemAD() =
	adapterDelegateViewBinding<CarouselSkeletonItem, ListModel, ItemMangaCarouselBinding>(
		{ layoutInflater, parent -> ItemMangaCarouselBinding.inflate(layoutInflater, parent, false) },
	) {
		binding.textViewTitle.isInvisible = true
	}

/**
 * The spot between the last suggestion and the first one again: just the loop icon in an empty slot.
 * The carousel only masks the slot from its edges, so the centred icon keeps its size as it scrolls.
 */
private fun carouselLoopItemAD() =
	adapterDelegateViewBinding<CarouselLoopItem, ListModel, ItemMangaCarouselBinding>(
		{ layoutInflater, parent -> ItemMangaCarouselBinding.inflate(layoutInflater, parent, false) },
	) {
		binding.textViewTitle.isInvisible = true
		binding.root.foreground = null
		binding.root.contentDescription = context.getString(R.string.suggestions_loop)
		binding.imageViewCover.background = null
		binding.imageViewCover.scaleType = ImageView.ScaleType.CENTER
		binding.imageViewCover.setImageResource(R.drawable.ic_carousel_loop)
	}

private data object CarouselLoopItem : ListModel {
	override fun areItemsTheSame(other: ListModel) = other === this
}

private data class CarouselSkeletonItem(val index: Int) : ListModel {
	override fun areItemsTheSame(other: ListModel) = other is CarouselSkeletonItem && other.index == index
}

private val carouselSkeleton = List(ExploreViewModel.SUGGESTIONS_COUNT, ::CarouselSkeletonItem)

// The title sits over the cover's full width, so a narrowed tile would cut it at both edges: fade it
// out before the mask reaches the text's side padding (gone well before the medium/small peek tiles),
// and evaluate on layout too so initial peek tiles don't stay opaque before the first scroll.
private fun ItemMangaCarouselBinding.fadeTitleWithMask() {
	fun updateAlpha(maskWidth: Float) {
		val width = carouselItem.width
		if (width > 0) {
			textViewTitle.alpha = ((maskWidth / width - 0.74f) / 0.16f).coerceIn(0f, 1f)
		}
	}
	carouselItem.setOnMaskChangedListener { maskRect ->
		updateAlpha(maskRect.width())
	}
	carouselItem.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
		updateAlpha(carouselItem.maskRectF.width())
	}
}
