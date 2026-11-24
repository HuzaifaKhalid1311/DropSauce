package org.koitharu.kotatsu.explore.ui.adapter

import androidx.core.view.isInvisible
import com.google.android.material.carousel.MultiBrowseCarouselStrategy
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
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

/**
 * Shared by Explore's suggestions and the details page's related titles, so both look the same.
 * An empty list shows blank skeleton tiles, so the skeleton has exactly the loaded carousel's tile
 * count, sizes and shapes.
 */
fun CarouselRecyclerView.setUpMangaCarousel(
	itemClickListener: OnListItemClickListener<Manga>,
): BaseListAdapter<ListModel> {
	val adapter = BaseListAdapter<ListModel>()
		.addDelegate(ListItemType.MANGA_CAROUSEL, carouselItemAD(itemClickListener))
		.addDelegate(ListItemType.STATE_LOADING, carouselSkeletonItemAD())
	this.adapter = adapter
	layoutManager = SafeCarouselLayoutManager(MultiBrowseCarouselStrategy())
	return adapter
}

/** Items for [setUpMangaCarousel]'s adapter: the skeleton while [items] is still empty. */
fun carouselItems(items: List<ListModel>): List<ListModel> = items.ifEmpty { carouselSkeleton }

private fun carouselItemAD(
	itemClickListener: OnListItemClickListener<Manga>,
) = adapterDelegateViewBinding<MangaListModel, ListModel, ItemMangaCarouselBinding>(
	{ layoutInflater, parent -> ItemMangaCarouselBinding.inflate(layoutInflater, parent, false) },
) {

	binding.root.setOnClickListener { v ->
		itemClickListener.onItemClick(item.toMangaWithOverride(), v)
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
