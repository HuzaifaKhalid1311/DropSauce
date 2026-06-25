package org.koitharu.kotatsu.details.ui.adapter

import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.ItemMissingChaptersBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.MissingChapters

fun missingChaptersAD() = adapterDelegateViewBinding<MissingChapters, ListModel, ItemMissingChaptersBinding>(
	viewBinding = { inflater, parent -> ItemMissingChaptersBinding.inflate(inflater, parent, false) },
	on = { item, _, _ -> item is MissingChapters },
) {
	bind {
		binding.textViewMissing.text = context.resources.getQuantityString(
			R.plurals.missing_chapters,
			item.count,
			item.count
		)
	}
}
