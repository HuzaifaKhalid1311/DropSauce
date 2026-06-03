package org.koitharu.kotatsu.explore.ui.adapter

import android.content.Context
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.view.isVisible
import com.google.android.material.carousel.CarouselLayoutManager
import com.google.android.material.carousel.CarouselSnapHelper
import com.google.android.material.carousel.MaskableFrameLayout
import com.google.android.material.carousel.MultiBrowseCarouselStrategy
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getSummary
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isExternalSource
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.drawableStart
import org.koitharu.kotatsu.core.util.ext.setProgressIcon
import org.koitharu.kotatsu.core.util.ext.setTooltipCompat
import org.koitharu.kotatsu.databinding.ItemExploreButtonsBinding
import org.koitharu.kotatsu.databinding.ItemExploreSourceGridBinding
import org.koitharu.kotatsu.databinding.ItemExploreSourceListBinding
import org.koitharu.kotatsu.databinding.ItemMangaCarouselBinding
import org.koitharu.kotatsu.databinding.ItemRecommendationBinding
import org.koitharu.kotatsu.explore.ui.model.ExploreButtons
import org.koitharu.kotatsu.explore.ui.model.MangaSourceItem
import org.koitharu.kotatsu.explore.ui.model.RecommendationsItem
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaCompactListModel
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.Manga

fun exploreButtonsAD(
	clickListener: View.OnClickListener,
) = adapterDelegateViewBinding<ExploreButtons, ListModel, ItemExploreButtonsBinding>(
	{ layoutInflater, parent -> ItemExploreButtonsBinding.inflate(layoutInflater, parent, false) },
) {

	binding.buttonBookmarks.setOnClickListener(clickListener)
	binding.buttonDownloads.setOnClickListener(clickListener)
	binding.buttonLocal.setOnClickListener(clickListener)
	binding.buttonRandom.setOnClickListener(clickListener)

	bind {
		if (item.isRandomLoading) {
			binding.buttonRandom.setProgressIcon()
		} else {
			binding.buttonRandom.setIconResource(R.drawable.ic_dice)
		}
		binding.buttonRandom.isClickable = !item.isRandomLoading
	}
}

fun exploreRecommendationItemAD(
	itemClickListener: OnListItemClickListener<Manga>,
) = adapterDelegateViewBinding<RecommendationsItem, ListModel, ItemRecommendationBinding>(
	{ layoutInflater, parent -> ItemRecommendationBinding.inflate(layoutInflater, parent, false) },
) {

	val adapter = BaseListAdapter<MangaCompactListModel>()
		.addDelegate(ListItemType.MANGA_CAROUSEL, recommendationCarouselItemAD(itemClickListener))
	with(binding.recyclerView) {
		this.adapter = adapter
		layoutManager = CarouselLayoutManager(MultiBrowseCarouselStrategy())
		isNestedScrollingEnabled = false
		if (onFlingListener == null) {
			CarouselSnapHelper().attachToRecyclerView(this)
		}
	}

	bind {
		adapter.items = item.manga
	}
}

fun recommendationCarouselItemAD(
	itemClickListener: OnListItemClickListener<Manga>,
) = adapterDelegateViewBinding<MangaCompactListModel, MangaCompactListModel, ItemMangaCarouselBinding>(
	{ layoutInflater, parent -> ItemMangaCarouselBinding.inflate(layoutInflater, parent, false) },
) {

	binding.root.setOnClickListener { v ->
		itemClickListener.onItemClick(item.manga, v)
	}
	(itemView as? MaskableFrameLayout)?.setOnMaskChangedListener { maskRect ->
		val w = itemView.width.toFloat()
		val ratio = if (w > 0f) maskRect.width() / w else 1f
		binding.textViewTitle.alpha = ((ratio - 0.35f) / 0.4f).coerceIn(0f, 1f)
	}
	binding.progressView.isVisible = false
	binding.iconsView.isVisible = false
	binding.badge.isVisible = false

	bind {
		binding.textViewTitle.text = item.manga.title
		binding.imageViewCover.setImageAsync(item.manga.coverUrl, item.manga.source)
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
		binding.textViewTitle.text = item.source.getExploreTitle(context)
		binding.textViewTitle.drawableStart = if (item.source.isPinned) iconPinned else null
		binding.textViewSubtitle.text = item.source.getSummary(context)
		binding.imageViewIcon.applyExternalSourceStyle(item.source.mangaSource.isExternalSource())
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
		val title = item.source.getExploreTitle(context)
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
		binding.imageViewIcon.setImageAsync(item.source)
	}
}

private fun org.koitharu.kotatsu.core.model.MangaSourceInfo.getExploreTitle(context: Context): String {
	return when (val source = mangaSource.unwrap()) {
		is MihonMangaSource -> source.displayNameWithoutLanguage
		else -> getTitle(context)
	}
}
