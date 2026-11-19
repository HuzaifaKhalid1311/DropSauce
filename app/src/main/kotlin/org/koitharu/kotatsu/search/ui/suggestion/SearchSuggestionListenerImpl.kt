package org.koitharu.kotatsu.search.ui.suggestion

import android.text.Editable
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.widget.TextView
import androidx.core.net.toUri
import com.google.android.material.search.SearchView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.MangaLinkResolver
import org.koitharu.kotatsu.core.ui.dialog.showConfirmDialog
import org.koitharu.kotatsu.core.ui.dialog.showTextInputDialog
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.domain.joinTagQuery

class SearchSuggestionListenerImpl(
	private val router: AppRouter,
	private val searchView: SearchView,
	private val viewModel: SearchSuggestionViewModel,
) : SearchSuggestionListener {

	override fun onMangaClick(manga: Manga) {
		router.openDetails(manga)
	}

	override fun onQueryClick(query: String, kind: SearchKind, submit: Boolean) {
		if (submit && query.isNotEmpty()) {
			if (kind == SearchKind.SIMPLE && MangaLinkResolver.isValidLink(query)) {
				router.openDetails(query.toUri())
			} else {
				router.openSearch(query, kind)
				if (kind != SearchKind.TAG) {
					viewModel.saveQuery(query)
				}
			}
			// Deliberately left open: coming back from the results lands on the search bar again.
		} else {
			searchView.setText(query)
			searchView.editText.setSelection(searchView.editText.length())
		}
	}

	override fun onRemoveQuery(query: String) {
		viewModel.deleteQuery(query)
	}

	override fun onTagClick(tag: String) {
		// Ticking genres means a genre search: drop the keyboard so tapping the bar again (back to text) is noticed.
		searchView.clearFocusAndHideKeyboard()
		viewModel.toggleTag(tag)
	}

	override fun onTagLongClick(tag: String) {
		searchView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
		val context = searchView.context
		showConfirmDialog(
			context = context,
			icon = R.drawable.ic_delete,
			title = tag,
			message = context.getString(R.string.remove_quick_genre_message),
			confirmLabel = context.getString(R.string.remove),
			destructive = true,
		) {
			viewModel.removeQuickTag(tag)
		}
	}

	override fun onAddTagClick() {
		val context = searchView.context
		showTextInputDialog(
			context = context,
			icon = R.drawable.ic_quick_tag_add,
			title = context.getString(R.string.add_quick_genre),
			hint = context.getString(R.string.genre),
			onConfirm = viewModel::addQuickTag,
		)
	}

	override fun onSearchTagsClick() {
		val genres = viewModel.selectedTags.value
		if (genres.isNotEmpty()) {
			router.openSearch(joinTagQuery(genres), SearchKind.TAG)
		}
	}

	override fun onSourceClick(source: MangaSource) {
		router.openList(source, null, null)
	}

	override fun onSourceSettingsClick(source: MangaSource) {
		router.openSourceSettings(source)
	}

	override fun onSourceHideClick(source: MangaSource) {
		viewModel.hideSourceSuggestion(source)
	}

	override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

	override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

	override fun afterTextChanged(s: Editable?) {
		viewModel.onQueryChanged(s?.toString().orEmpty())
	}

	override fun onEditorAction(
		v: TextView?,
		actionId: Int,
		event: KeyEvent?
	): Boolean {
		val query = v?.text?.toString()
		if (query.isNullOrEmpty()) {
			return false
		}
		onQueryClick(query, SearchKind.SIMPLE, true)
		return true
	}
}
