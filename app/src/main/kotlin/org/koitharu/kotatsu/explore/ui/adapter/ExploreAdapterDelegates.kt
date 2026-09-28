package org.koitharu.kotatsu.explore.ui.adapter

import android.graphics.Outline
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import androidx.core.content.ContextCompat
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.view.isVisible
import org.koitharu.kotatsu.core.ui.widgets.SafeCarouselLayoutManager
import com.google.android.material.carousel.MultiBrowseCarouselStrategy
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getSummary
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isExternalSource
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.drawableStart
import org.koitharu.kotatsu.core.util.ext.setTooltipCompat
import org.koitharu.kotatsu.databinding.ItemExploreButtonsBinding
import org.koitharu.kotatsu.databinding.ItemExploreSourceGridBinding
import org.koitharu.kotatsu.databinding.ItemExploreSourceListBinding
import org.koitharu.kotatsu.databinding.ItemMangaCarouselBinding
import org.koitharu.kotatsu.databinding.ItemRecommendationBinding
import org.koitharu.kotatsu.explore.ui.ExploreViewModel
import org.koitharu.kotatsu.explore.ui.model.ExploreButtons
import org.koitharu.kotatsu.explore.ui.model.MangaSourceItem
import org.koitharu.kotatsu.explore.ui.model.RecommendationsItem
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaCompactListModel
import org.koitharu.kotatsu.parsers.model.Manga
import kotlin.math.roundToInt

fun exploreButtonsAD(
	clickListener: View.OnClickListener,
) = adapterDelegateViewBinding<ExploreButtons, ListModel, ItemExploreButtonsBinding>(
	{ layoutInflater, parent -> ItemExploreButtonsBinding.inflate(layoutInflater, parent, false) },
) {

	binding.buttonBookmarks.setOnClickListener(clickListener)
	binding.buttonDownloads.setOnClickListener(clickListener)
	binding.buttonLocal.setOnClickListener(clickListener)
}

fun exploreRecommendationItemAD(
	itemClickListener: OnListItemClickListener<Manga>,
) = adapterDelegateViewBinding<RecommendationsItem, ListModel, ItemRecommendationBinding>(
	{ layoutInflater, parent -> ItemRecommendationBinding.inflate(layoutInflater, parent, false) },
) {

	val adapter = BaseListAdapter<ListModel>()
		.addDelegate(ListItemType.MANGA_CAROUSEL, recommendationCarouselItemAD(itemClickListener))
		.addDelegate(ListItemType.STATE_LOADING, carouselSkeletonItemAD())
	with(binding.recyclerView) {
		this.adapter = adapter
		layoutManager = SafeCarouselLayoutManager(MultiBrowseCarouselStrategy())
	}

	bind {
		// Still loading: fill the real carousel with blank tiles, so the skeleton has exactly the
		// loaded carousel's tile count, sizes and shapes.
		adapter.items = item.manga.ifEmpty { carouselSkeleton }
	}
}

fun recommendationCarouselItemAD(
	itemClickListener: OnListItemClickListener<Manga>,
) = adapterDelegateViewBinding<MangaCompactListModel, ListModel, ItemMangaCarouselBinding>(
	{ layoutInflater, parent -> ItemMangaCarouselBinding.inflate(layoutInflater, parent, false) },
) {

	binding.root.setOnClickListener { v ->
		itemClickListener.onItemClick(item.manga, v)
	}
	binding.progressView.isVisible = false
	binding.badge.isVisible = false
	binding.clipCoverToMask()

	bind {
		binding.textViewTitle.text = item.manga.title
		binding.imageViewCover.setImageAsync(item.manga.coverUrl, item.manga.source)
	}
}

/** A blank suggestions tile shown while the carousel loads: the empty cover is the grey skeleton block. */
private fun carouselSkeletonItemAD() =
	adapterDelegateViewBinding<CarouselSkeletonItem, ListModel, ItemMangaCarouselBinding>(
		{ layoutInflater, parent -> ItemMangaCarouselBinding.inflate(layoutInflater, parent, false) },
	) {
		binding.progressView.isVisible = false
		binding.badge.isVisible = false
		binding.clipCoverToMask()
	}

private data class CarouselSkeletonItem(val index: Int) : ListModel {
	override fun areItemsTheSame(other: ListModel) = other is CarouselSkeletonItem && other.index == index
}

private val carouselSkeleton = List(ExploreViewModel.SUGGESTIONS_COUNT, ::CarouselSkeletonItem)

// The carousel mask spans cover + title, so its bottom corners sit under the title and are square:
// once a tile narrows, the mask's straight sides cut the cover's rounded bottom corners off. Clip the
// cover to a rounded rect at the mask's current edges so all four of its corners stay rounded.
private fun ItemMangaCarouselBinding.clipCoverToMask() {
	val cover = imageViewCover
	val coverRadius = cover.shapeAppearanceModel.bottomLeftCornerSize.getCornerSize(RectF())
	var maskLeft = 0f
	var maskRight = Float.MAX_VALUE
	cover.clipToOutline = true
	cover.outlineProvider = object : ViewOutlineProvider() {
		override fun getOutline(view: View, outline: Outline) {
			outline.setRoundRect(
				maskLeft.roundToInt().coerceAtLeast(0),
				0,
				maskRight.roundToInt().coerceAtMost(view.width),
				view.height,
				coverRadius,
			)
		}
	}
	carouselItem.setOnMaskChangedListener { maskRect ->
		maskLeft = maskRect.left
		maskRight = maskRect.right
		cover.invalidateOutline()
	}
}


fun exploreSourceListItemAD(
	listener: OnListItemClickListener<MangaSourceItem>,
) = adapterDelegateViewBinding<MangaSourceItem, ListModel, ItemExploreSourceListBinding>(
	{ layoutInflater, parent ->
		ItemExploreSourceListBinding.inflate(
			layoutInflater,
			parent,
			false,
		)
	},
	on = { item, _, _ -> item is MangaSourceItem && !item.isGrid },
) {

	AdapterDelegateClickListenerAdapter(this, listener).attach(itemView)
	val iconPinned = ContextCompat.getDrawable(context, R.drawable.ic_pin_small)

	bind {
		binding.textViewTitle.text = item.source.getTitle(context)
		binding.textViewTitle.drawableStart = if (item.source.isPinned) iconPinned else null
		binding.textViewSubtitle.text = item.source.getSummary(context)
		binding.imageViewIcon.applyExternalSourceStyle(item.source.mangaSource.isExternalSource())
		val inset = sourceIconInsetPx(
			binding.imageViewIcon.layoutParams.width,
			item.source.mangaSource.isNovelSource,
		)
		binding.imageViewIcon.setPadding(inset, inset, inset, inset)
		binding.imageViewIcon.setImageAsync(item.source)
	}
}

fun exploreSourceGridItemAD(
	listener: OnListItemClickListener<MangaSourceItem>,
) = adapterDelegateViewBinding<MangaSourceItem, ListModel, ItemExploreSourceGridBinding>(
	{ layoutInflater, parent ->
		ItemExploreSourceGridBinding.inflate(
			layoutInflater,
			parent,
			false,
		)
	},
	on = { item, _, _ -> item is MangaSourceItem && item.isGrid },
) {

	AdapterDelegateClickListenerAdapter(this, listener).attach(itemView)
	val iconPinned = ContextCompat.getDrawable(context, R.drawable.ic_pin_small)

	bind {
		val title = item.source.getTitle(context)
		itemView.setTooltipCompat(
			buildSpannedString {
				bold {
					append(title)
				}
				appendLine()
				append(item.source.getSummary(context))
			},
		)
		binding.textViewTitle.text = title
		binding.textViewTitle.drawableStart = if (item.source.isPinned) iconPinned else null
		binding.imageViewIcon.applyExternalSourceStyle(item.source.mangaSource.isExternalSource())
		val inset = sourceIconInsetPx(
			binding.imageViewIcon.layoutParams.width,
			item.source.mangaSource.isNovelSource,
		)
		binding.imageViewIcon.setPadding(inset, inset, inset, inset)
		binding.imageViewIcon.setImageAsync(item.source)
	}
}

internal fun sourceIconInsetPx(iconSizePx: Int, isNovel: Boolean): Int =
	if (isNovel) iconSizePx / 10 else 0
