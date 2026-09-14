package org.koitharu.kotatsu.tracker.ui.feed.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.databinding.ItemFeedBinding
import org.koitharu.kotatsu.list.ui.adapter.MangaDetailsClickListener
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem
import com.google.android.material.R as materialR

fun feedItemAD(
	clickListener: OnListItemClickListener<FeedItem>,
	detailsClickListener: MangaDetailsClickListener,
	onExpandClick: (FeedItem, View) -> Unit,
) = adapterDelegateViewBinding<FeedItem, ListModel, ItemFeedBinding>(
	{ inflater, parent -> ItemFeedBinding.inflate(inflater, parent, false) },
) {
	val density = context.resources.displayMetrics.density
	val cornerLarge = context.resources.getDimension(R.dimen.corner_large)
	val cornerSmall = 4f * density
	val gapSeam = (3f * density).toInt()
	val gapGroup = (12f * density).toInt()
	val containerColor = context.getThemeColor(materialR.attr.colorSurfaceContainer, Color.DKGRAY)
	val rippleColor = context.getThemeColor(android.R.attr.colorControlHighlight, Color.GRAY)

	fun backgroundFor(position: FeedItem.GroupPosition): RippleDrawable {
		val shape = ShapeAppearanceModel.builder().apply {
			when (position) {
				FeedItem.GroupPosition.SINGLE -> setAllCornerSizes(cornerLarge)
				FeedItem.GroupPosition.MIDDLE -> setAllCornerSizes(cornerSmall)
				FeedItem.GroupPosition.FIRST -> {
					setTopLeftCornerSize(cornerLarge)
					setTopRightCornerSize(cornerLarge)
					setBottomLeftCornerSize(cornerSmall)
					setBottomRightCornerSize(cornerSmall)
				}

				FeedItem.GroupPosition.LAST -> {
					setTopLeftCornerSize(cornerSmall)
					setTopRightCornerSize(cornerSmall)
					setBottomLeftCornerSize(cornerLarge)
					setBottomRightCornerSize(cornerLarge)
				}
			}
		}.build()
		val fill = MaterialShapeDrawable(shape).apply { fillColor = ColorStateList.valueOf(containerColor) }
		val mask = MaterialShapeDrawable(shape).apply { fillColor = ColorStateList.valueOf(Color.WHITE) }
		return RippleDrawable(ColorStateList.valueOf(rippleColor), fill, mask)
	}

	itemView.setOnClickListener {
		clickListener.onItemClick(item, it)
	}

	itemView.setOnLongClickListener {
		clickListener.onItemLongClick(item, it)
	}

	binding.buttonRead.setOnClickListener {
		detailsClickListener.onReadClick(item.toMangaWithOverride(), it)
	}

	bind {
		itemView.background = backgroundFor(item.groupPosition)
		itemView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
			bottomMargin = when (item.groupPosition) {
				FeedItem.GroupPosition.FIRST, FeedItem.GroupPosition.MIDDLE -> gapSeam
				else -> gapGroup
			}
		}
		binding.imageViewCover.setImageAsync(item.imageUrl, item.manga.source)
		binding.textViewTitle.text = item.title
		val chapters = item.chapters
		// a single chapter always shows its name; multiple collapse into a "N new chapters" summary
		// that expands to the full list on click, and re-collapses when the feed screen is left
		val isCollapsedSummary = chapters.size > 1 && !item.isExpanded
		binding.textViewSummary.isVisible = chapters.isEmpty() || isCollapsedSummary
		val indicatorRes = if (item.isNew) R.drawable.ic_new else 0
		binding.textViewSummary.setCompoundDrawablesRelativeWithIntrinsicBounds(
			if (binding.textViewSummary.isVisible) indicatorRes else 0, 0, 0, 0,
		)
		if (binding.textViewSummary.isVisible) {
			binding.textViewSummary.text = context.resources.getQuantityStringSafe(
				R.plurals.new_chapters,
				item.count,
				item.count,
			)
		}
		if (isCollapsedSummary) {
			binding.textViewSummary.setOnClickListener { v -> onExpandClick(item, v) }
		} else {
			binding.textViewSummary.setOnClickListener(null)
		}
		binding.layoutChapters.isVisible = chapters.isNotEmpty() && !isCollapsedSummary
		// an expanded multi-chapter entry collapses back on tap; single-chapter lists stay inert
		if (chapters.size > 1 && item.isExpanded) {
			binding.layoutChapters.setOnClickListener { v -> onExpandClick(item, v) }
		} else {
			binding.layoutChapters.setOnClickListener(null)
			binding.layoutChapters.isClickable = false
		}
		binding.layoutChapters.removeAllViews()
		if (binding.layoutChapters.isVisible) {
			val inflater = LayoutInflater.from(context)
			val transparentIndicator = if (item.isNew) {
				ContextCompat.getDrawable(context, R.drawable.ic_new)?.mutate()?.apply {
					setTint(Color.TRANSPARENT)
				}
			} else {
				null
			}
			for (chapter in chapters) {
				val textView = inflater.inflate(
					R.layout.item_feed_chapter,
					binding.layoutChapters,
					false,
				) as TextView
				textView.text = chapter.name
				val drawable = if (item.isNew && chapter.isNew) {
					ContextCompat.getDrawable(context, R.drawable.ic_new)
				} else {
					transparentIndicator
				}
				textView.setCompoundDrawablesRelativeWithIntrinsicBounds(
					drawable, null, null, null
				)
				binding.layoutChapters.addView(textView)
			}
		}
	}
}
