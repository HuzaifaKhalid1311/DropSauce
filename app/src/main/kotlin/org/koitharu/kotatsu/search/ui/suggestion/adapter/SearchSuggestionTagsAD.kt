package org.koitharu.kotatsu.search.ui.suggestion.adapter

import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.databinding.ItemSearchSuggestionTagsBinding
import org.koitharu.kotatsu.search.ui.suggestion.SearchSuggestionListener
import org.koitharu.kotatsu.search.ui.suggestion.model.SearchSuggestionItem

fun searchSuggestionTagsAD(
	listener: SearchSuggestionListener,
) = adapterDelegateViewBinding<SearchSuggestionItem.Tags, SearchSuggestionItem, ItemSearchSuggestionTagsBinding>(
	{ layoutInflater, parent -> ItemSearchSuggestionTagsBinding.inflate(layoutInflater, parent, false) },
) {

	// A tap ticks the genre, the button searches every ticked one; hold removes the chip.
	binding.chipsGenres.onChipClickListener = ChipsView.OnChipClickListener { _, data ->
		when (data) {
			is String -> listener.onTagClick(data)
			SearchSuggestionItem.Tags.AddChip -> listener.onAddTagClick()
		}
	}
	binding.chipsGenres.onChipLongClickListener = ChipsView.OnChipLongClickListener { _, data ->
		if (data is String) {
			listener.onTagLongClick(data)
			true
		} else {
			false
		}
	}
	binding.buttonSearchGenres.setOnClickListener {
		listener.onSearchTagsClick()
	}

	bind {
		binding.chipsGenres.setChips(item.tags)
		binding.buttonSearchGenres.isVisible = item.selectedCount > 0
		binding.buttonSearchGenres.text = context.resources.getQuantityString(
			R.plurals.search_genres,
			item.selectedCount,
			item.selectedCount,
		)
	}
}
