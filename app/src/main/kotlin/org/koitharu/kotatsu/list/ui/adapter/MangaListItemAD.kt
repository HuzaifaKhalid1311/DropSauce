package org.koitharu.kotatsu.list.ui.adapter

import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.core.ui.list.AdapterDelegateClickListenerAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.setTooltipCompat
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode
import org.koitharu.kotatsu.databinding.ItemMangaListBinding
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MangaCompactListModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import kotlin.math.roundToInt

fun mangaListItemAD(
	clickListener: OnListItemClickListener<MangaListModel>,
	titleClickListener: OnListItemClickListener<MangaListModel>? = null,
) = adapterDelegateViewBinding<MangaCompactListModel, ListModel, ItemMangaListBinding>(
	{ inflater, parent -> ItemMangaListBinding.inflate(inflater, parent, false) },
) {

	AdapterDelegateClickListenerAdapter(this, clickListener).attach(itemView)
	if (titleClickListener != null) {
		binding.textViewTitle.attachTitleClickToRead(itemView) { view ->
			titleClickListener.onItemClick(item, view)
		}
	}

	bind {
		itemView.setTooltipCompat(item.getSummary(context))
		binding.textViewTitle.text = item.title
		val progress = item.progress?.takeIf { it.isValid() }
		binding.layoutProgress.isVisible = progress != null
		if (progress != null) {
			binding.progressBar.setProgressCompat((progress.percent * 100f).roundToInt(), true)
			// Same label the cover badge uses, so both indicators read alike.
			binding.textViewProgress.text = if (progress.mode == ProgressIndicatorMode.CHAPTERS_READ) {
				val read = (progress.percent * progress.totalChapters).roundToInt()
				"$read/${progress.totalChapters}"
			} else {
				context.getString(R.string.percent_string_pattern, ReadingProgress.percentToString(progress.percent))
			}
		}
		binding.imageViewPin.isVisible = item.isPinned
		binding.imageViewCover.setImageAsync(item.coverUrl, item.manga)
		binding.badge.number = item.counter
		binding.badge.isVisible = item.counter > 0
	}
}
