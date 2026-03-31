package org.haziffe.dropsauce.search.ui.suggestion.adapter

import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.haziffe.dropsauce.core.ui.widgets.ChipsView
import org.haziffe.dropsauce.databinding.ItemSearchSuggestionTagsBinding
import org.haziffe.dropsauce.parsers.model.MangaTag
import org.haziffe.dropsauce.search.ui.suggestion.SearchSuggestionListener
import org.haziffe.dropsauce.search.ui.suggestion.model.SearchSuggestionItem

fun searchSuggestionTagsAD(
	listener: SearchSuggestionListener,
) = adapterDelegateViewBinding<SearchSuggestionItem.Tags, SearchSuggestionItem, ItemSearchSuggestionTagsBinding>(
	{ layoutInflater, parent -> ItemSearchSuggestionTagsBinding.inflate(layoutInflater, parent, false) },
) {

	binding.chipsGenres.onChipClickListener = ChipsView.OnChipClickListener { _, data ->
		listener.onTagClick(data as? MangaTag ?: return@OnChipClickListener)
	}

	bind {
		binding.chipsGenres.setChips(item.tags)
	}
}
