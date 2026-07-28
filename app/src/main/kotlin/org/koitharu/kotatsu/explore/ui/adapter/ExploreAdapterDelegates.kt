package org.koitharu.kotatsu.explore.ui.adapter

import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.view.isVisible
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.carousel.CarouselLayoutManager
import com.google.android.material.carousel.MultiBrowseCarouselStrategy
import com.google.android.material.tabs.TabLayout
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
import org.koitharu.kotatsu.core.util.ext.setTooltipCompat
import org.koitharu.kotatsu.databinding.ItemExploreButtonsBinding
import org.koitharu.kotatsu.databinding.ItemExploreSourceGridBinding
import org.koitharu.kotatsu.databinding.ItemExploreSourceListBinding
import org.koitharu.kotatsu.databinding.ItemMangaCarouselBinding
import org.koitharu.kotatsu.databinding.ItemExploreExtensionsHeaderBinding
import org.koitharu.kotatsu.databinding.ItemRecommendationBinding
import org.koitharu.kotatsu.explore.ui.model.ExploreButtons
import org.koitharu.kotatsu.explore.ui.model.ExtensionsHeaderItem
import org.koitharu.kotatsu.explore.ui.model.MangaSourceItem
import org.koitharu.kotatsu.explore.ui.model.RecommendationsItem
import org.koitharu.kotatsu.lnreader.model.LnMangaSource
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.bindBadge
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaCompactListModel
import org.koitharu.kotatsu.parsers.model.Manga

fun exploreButtonsAD(
	clickListener: View.OnClickListener,
) = adapterDelegateViewBinding<ExploreButtons, ListModel, ItemExploreButtonsBinding>(
	{ layoutInflater, parent -> ItemExploreButtonsBinding.inflate(layoutInflater, parent, false) },
) {

	binding.buttonBookmarks.setOnClickListener(clickListener)
	binding.buttonDownloads.setOnClickListener(clickListener)
	binding.buttonLocal.setOnClickListener(clickListener)
}

fun extensionsHeaderAD(
	onManageClick: () -> Unit,
	onKindSelected: (isNovel: Boolean) -> Unit,
) = adapterDelegateViewBinding<ExtensionsHeaderItem, ListModel, ItemExploreExtensionsHeaderBinding>(
	{ layoutInflater, parent -> ItemExploreExtensionsHeaderBinding.inflate(layoutInflater, parent, false) },
) {

	var badge: BadgeDrawable? = null
	var isBinding = false
	binding.buttonMore.setOnClickListener { onManageClick() }
	binding.tabsKind.apply {
		addTab(newTab().setText(R.string.store_kind_manga))
		addTab(newTab().setText(R.string.store_kind_novel))
		addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
			override fun onTabSelected(tab: TabLayout.Tab) {
				if (!isBinding) {
					onKindSelected(tab.position == 1)
				}
			}

			override fun onTabUnselected(tab: TabLayout.Tab) = Unit

			override fun onTabReselected(tab: TabLayout.Tab) = Unit
		})
	}

	bind {
		isBinding = true
		binding.tabsKind.getTabAt(if (item.isNovel) 1 else 0)?.select()
		isBinding = false
		// Mirrors the update indicator the shared ListHeader used to draw on its button.
		badge = binding.buttonMore.bindBadge(badge, if (item.hasUpdates) "" else null)
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
		clipChildren = false
		clipToPadding = false
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
		binding.textViewTitle.text = item.source.getTitle(context)
		binding.textViewTitle.drawableStart = if (item.source.isPinned) iconPinned else null
		binding.textViewSubtitle.text = item.source.getSummary(context)
		binding.imageViewIcon.applyExternalSourceStyle(item.source.mangaSource.isExternalSource())
		val inset = sourceIconInsetPx(
			binding.imageViewIcon.layoutParams.width,
			item.source.mangaSource.unwrap() is LnMangaSource,
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
			item.source.mangaSource.unwrap() is LnMangaSource,
		)
		binding.imageViewIcon.setPadding(inset, inset, inset, inset)
		binding.imageViewIcon.setImageAsync(item.source)
	}
}

internal fun sourceIconInsetPx(iconSizePx: Int, isNovel: Boolean): Int =
	if (isNovel) iconSizePx / 10 else 0
