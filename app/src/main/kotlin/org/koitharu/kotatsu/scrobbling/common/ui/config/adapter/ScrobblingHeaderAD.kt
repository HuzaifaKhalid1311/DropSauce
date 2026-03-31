package org.haziffe.dropsauce.scrobbling.common.ui.config.adapter

import androidx.core.view.isInvisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.databinding.ItemHeaderBinding
import org.haziffe.dropsauce.list.ui.model.ListModel
import org.haziffe.dropsauce.scrobbling.common.domain.model.ScrobblingStatus

fun scrobblingHeaderAD() = adapterDelegateViewBinding<ScrobblingStatus, ListModel, ItemHeaderBinding>(
	{ inflater, parent -> ItemHeaderBinding.inflate(inflater, parent, false) },
) {

	binding.buttonMore.isInvisible = true
	val strings = context.resources.getStringArray(R.array.scrobbling_statuses)

	bind {
		binding.textViewTitle.text = strings.getOrNull(item.ordinal)
	}
}
