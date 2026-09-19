package org.koitharu.kotatsu.search.ui.suggestion.adapter

import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
import org.koitharu.kotatsu.databinding.ItemSearchSuggestionScopeBinding
import org.koitharu.kotatsu.search.ui.suggestion.SearchSuggestionListener
import org.koitharu.kotatsu.search.ui.suggestion.model.SearchSuggestionItem

fun searchSuggestionScopeAD(
	listener: SearchSuggestionListener,
) = adapterDelegateViewBinding<SearchSuggestionItem.Scope, SearchSuggestionItem, ItemSearchSuggestionScopeBinding>(
	{ layoutInflater, parent -> ItemSearchSuggestionScopeBinding.inflate(layoutInflater, parent, false) },
) {

	// Restoring the checked button on bind fires the listener too; this keeps that from looping back
	// into the view model as if the user had tapped.
	var isBinding = false

	binding.groupScope.addOnButtonCheckedListener { group, checkedId, isChecked ->
		if (!isChecked || isBinding) {
			return@addOnButtonCheckedListener
		}
		group.hapticFeedback(HapticEffect.CLICK)
		listener.onSearchScopeChanged(checkedId == R.id.button_novel)
	}

	bind {
		val targetId = if (item.isNovel) R.id.button_novel else R.id.button_manga
		if (binding.groupScope.checkedButtonId != targetId) {
			isBinding = true
			binding.groupScope.check(targetId)
			isBinding = false
		}
	}
}
