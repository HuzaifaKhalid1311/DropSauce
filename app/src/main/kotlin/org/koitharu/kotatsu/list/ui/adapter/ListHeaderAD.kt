package org.koitharu.kotatsu.list.ui.adapter

import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.isGone
import com.google.android.material.color.MaterialColors
import com.google.android.material.badge.BadgeDrawable
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemHeaderBinding
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel

fun listHeaderAD(
	listener: ListHeaderClickListener?,
) = adapterDelegateViewBinding<ListHeader, ListModel, ItemHeaderBinding>(
	{ inflater, parent -> ItemHeaderBinding.inflate(inflater, parent, false) },
) {
	var badge: BadgeDrawable? = null

	if (listener != null) {
		binding.buttonMore.setOnClickListener {
			listener.onListHeaderClick(item, it)
		}
	}

	bind {
		val currentItem = item
		binding.textViewTitle.text = currentItem.getText(context)
		if (currentItem.buttonTextRes == 0) {
			binding.buttonMore.isGone = true
			binding.buttonMore.text = null
			binding.buttonMore.clearBadge(badge)
		} else {
			binding.buttonMore.icon = null
			binding.buttonMore.setText(currentItem.buttonTextRes)
			binding.buttonMore.contentDescription = context.getString(currentItem.buttonTextRes)
			if (currentItem.buttonTextRes == R.string.update_all) {
				val primary = MaterialColors.getColor(binding.buttonMore, com.google.android.material.R.attr.colorPrimary)
				val onPrimary = MaterialColors.getColor(binding.buttonMore, com.google.android.material.R.attr.colorOnPrimary)
				binding.buttonMore.backgroundTintList = android.content.res.ColorStateList.valueOf(primary)
				binding.buttonMore.setTextColor(onPrimary)
			} else {
				binding.buttonMore.backgroundTintList = null
				binding.buttonMore.setTextColor(
					MaterialColors.getColor(binding.buttonMore, com.google.android.material.R.attr.colorPrimary),
				)
			}
			binding.buttonMore.isGone = false
			badge = itemView.bindBadge(badge, currentItem.badge)
		}
	}
}
