package org.koitharu.kotatsu.search.ui.suggestion.adapter

import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.databinding.ItemSearchSuggestionTagsBinding
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.search.ui.suggestion.SearchSuggestionListener
import org.koitharu.kotatsu.search.ui.suggestion.model.SearchSuggestionItem

fun searchSuggestionTagsAD(
	listener: SearchSuggestionListener,
) = adapterDelegateViewBinding<SearchSuggestionItem.Tags, SearchSuggestionItem, ItemSearchSuggestionTagsBinding>(
	{ layoutInflater, parent -> ItemSearchSuggestionTagsBinding.inflate(layoutInflater, parent, false) },
) {

	// A tap ticks the genre; the button searches every ticked one.
	binding.chipsGenres.onChipClickListener = ChipsView.OnChipClickListener { _, data ->
		listener.onTagClick(data as? MangaTag ?: return@OnChipClickListener)
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
