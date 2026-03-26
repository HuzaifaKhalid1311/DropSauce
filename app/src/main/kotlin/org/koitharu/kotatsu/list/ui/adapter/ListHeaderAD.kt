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
		binding.textViewTitle.text = item.getText(context)
		if (item.buttonTextRes == 0) {
			binding.buttonMore.isGone = true
			binding.buttonMore.text = null
			binding.buttonMore.icon = null
			binding.buttonMore.clearBadge(badge)
		} else {
			if (item.filterMode != null) {
				binding.buttonMore.text = null
				binding.buttonMore.setIconResource(R.drawable.ic_settings)
				binding.buttonMore.contentDescription = context.getString(R.string.settings)
			} else {
				binding.buttonMore.icon = null
				binding.buttonMore.setText(item.buttonTextRes)
				binding.buttonMore.contentDescription = null
			}
			binding.buttonMore.isVisible = true
			badge = itemView.bindBadge(badge, item.badge)
		}

		if (item.filterMode != null) {
			val filterMode = item.filterMode!!
			binding.toggleGroupFilter.isVisible = true
			binding.toggleGroupFilter.clearOnButtonCheckedListeners()
			when (filterMode) {
				SourceFilterMode.LOCAL -> binding.toggleGroupFilter.check(R.id.button_filter_builtin)
				SourceFilterMode.EXTERNAL -> binding.toggleGroupFilter.check(R.id.button_filter_mihon)
			}
			binding.toggleGroupFilter.addOnButtonCheckedListener { _, checkedId, isChecked ->
				if (isChecked) {
					val newMode = when (checkedId) {
						R.id.button_filter_builtin -> SourceFilterMode.LOCAL
						R.id.button_filter_mihon -> SourceFilterMode.EXTERNAL
						else -> SourceFilterMode.LOCAL
					}
					listener?.onListHeaderFilterModeChanged(item, newMode)
				}
			}
		} else {
			binding.toggleGroupFilter.isVisible = false
		}
	}
}
