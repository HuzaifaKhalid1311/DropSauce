package org.koitharu.kotatsu.list.ui.adapter

import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.google.android.material.badge.BadgeDrawable
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemHeaderBinding
import org.koitharu.kotatsu.explore.ui.SourceFilterMode
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
		val filterMode = currentItem.filterMode
		if (currentItem.buttonTextRes == 0) {
			binding.buttonMore.isGone = true
			binding.buttonMore.text = null
			binding.buttonMore.clearBadge(badge)
		} else {
			if (filterMode != null) {
				binding.buttonMore.text = null
				binding.buttonMore.setIconResource(R.drawable.ic_add)
				binding.buttonMore.contentDescription = context.getString(currentItem.buttonTextRes)
			} else {
				binding.buttonMore.icon = null
				binding.buttonMore.setText(currentItem.buttonTextRes)
				binding.buttonMore.contentDescription = context.getString(currentItem.buttonTextRes)
			}
			binding.buttonMore.isVisible = true
			badge = itemView.bindBadge(badge, currentItem.badge)
		}

		if (filterMode != null) {
			binding.toggleGroupFilter.isVisible = true
			binding.toggleGroupFilter.clearOnButtonCheckedListeners()
			binding.toggleGroupFilter.check(R.id.button_filter_mihon)
			binding.toggleGroupFilter.addOnButtonCheckedListener { _, checkedId, isChecked ->
				if (isChecked) {
					val newMode = when (checkedId) {
						R.id.button_filter_mihon -> SourceFilterMode.EXTERNAL
						else -> SourceFilterMode.EXTERNAL
					}
					listener?.onListHeaderFilterModeChanged(currentItem, newMode)
				}
			}
		} else {
			binding.toggleGroupFilter.isVisible = false
		}
	}
}
