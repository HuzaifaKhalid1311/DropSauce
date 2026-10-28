package org.koitharu.kotatsu.list.ui.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import coil3.size.Size
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.setTooltipCompat
import org.koitharu.kotatsu.databinding.ItemMangaGridBinding
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback.Companion.PAYLOAD_PROGRESS_CHANGED
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.list.ui.size.ItemSizeResolver
import androidx.appcompat.R as appcompatR

fun mangaGridItemAD(
	sizeResolver: ItemSizeResolver,
	clickListener: OnListItemClickListener<MangaListModel>,
	titleClickListener: OnListItemClickListener<MangaListModel>? = null,
) = adapterDelegateViewBinding<MangaGridModel, ListModel, ItemMangaGridBinding>(
	{ inflater, parent -> ItemMangaGridBinding.inflate(inflater, parent, false) },
) {

	AdapterDelegateClickListenerAdapter(this, clickListener).attach(itemView)
	if (titleClickListener != null) {
		val onTitleClick: (View) -> Unit = { view -> titleClickListener.onItemClick(item, view) }
		binding.textViewTitleOverlay.attachTitleClickToRead(itemView, onTitleClick)
		binding.textViewTitle.attachTitleClickToRead(itemView, onTitleClick)
	}
	// The overlay title is the one that adapts to the grid size (white, on the scrim).
	sizeResolver.attachToView(itemView, binding.textViewTitleOverlay, binding.corners.progressView, binding.corners.iconsView)

	val gridMargin = context.resources.getDimensionPixelOffset(R.dimen.grid_spacing_outer)
	val gridMarginIncreased = context.resources.getDimensionPixelOffset(R.dimen.grid_spacing_outer_large)
	// Title scrim: a dark shade of the theme accent, always tall enough for two lines of title (so
	// every card looks the same), eased out towards the top so it has no visible starting edge,
	// with its bottom corners matching the cover's rounding.
	val darkAccent = ColorUtils.blendARGB(context.getThemeColor(appcompatR.attr.colorPrimary), Color.BLACK, 0.78f)
	binding.viewScrim.background = GradientDrawable(
		GradientDrawable.Orientation.BOTTOM_TOP,
		SCRIM_ALPHAS.map { ColorUtils.setAlphaComponent(darkAccent, it) }.toIntArray(),
	).apply {
		val r = context.resources.getDimension(R.dimen.cover_corner_large)
		cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
	}

	bind { payloads ->
		itemView.setTooltipCompat(item.getSummary(context))
		val coverMargin = if (item.isGridSpacingIncreased) gridMarginIncreased else gridMargin
		itemView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
			if (
				leftMargin != coverMargin ||
				topMargin != coverMargin ||
				rightMargin != coverMargin ||
				bottomMargin != coverMargin
			) {
				setMargins(coverMargin, coverMargin, coverMargin, coverMargin)
			}
		}
		val isTitleOverCover = item.isTitleOverCover && !item.isTitleHidden
		binding.textViewTitleOverlay.text = item.title
		binding.textViewTitle.text = item.title
		binding.textViewTitleOverlay.isVisible = isTitleOverCover
		binding.viewScrim.isVisible = isTitleOverCover
		binding.textViewTitle.isVisible = !item.isTitleHidden && !isTitleOverCover
		binding.corners.progressView.setProgress(item.progress, PAYLOAD_PROGRESS_CHANGED in payloads)
		binding.corners.imageViewPin.isVisible = item.isPinned
		binding.corners.iconsView.setMangaBadges(item.isSaved, item.isFavorite)
		// Load at a stable size derived from the grid cell width (not the transient measured view
		// size), so covers in the ViewPager2-hosted grids stay sharp after rotation/settling.
		val coverWidth = sizeResolver.cellWidth - coverMargin * 2
		binding.imageViewCover.exactImageSize = if (coverWidth > 0) {
			Size(coverWidth, coverWidth * 18 / 13)
		} else {
			null
		}
		binding.imageViewCover.setImageAsync(item.coverUrl, item.manga)
		binding.corners.badge.number = item.counter
		binding.corners.badge.isVisible = item.counter > 0
	}
}

/** Scrim opacity from bottom to top: near-solid behind the text, then an eased fade to nothing. */
private val SCRIM_ALPHAS = intArrayOf(0xF2, 0xF0, 0xE8, 0xDA, 0xC4, 0xA4, 0x7C, 0x50, 0x26, 0x0A, 0x00)
