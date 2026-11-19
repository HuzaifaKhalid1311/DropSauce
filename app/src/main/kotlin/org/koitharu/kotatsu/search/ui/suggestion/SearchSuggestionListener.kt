package org.koitharu.kotatsu.search.ui.suggestion

import android.text.TextWatcher
import android.widget.TextView
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.search.domain.SearchKind

interface SearchSuggestionListener : TextWatcher, TextView.OnEditorActionListener {

	fun onMangaClick(manga: Manga)

	fun onQueryClick(query: String, kind: SearchKind, submit: Boolean)

	fun onRemoveQuery(query: String)

	fun onSourceClick(source: MangaSource)

	fun onSourceSettingsClick(source: MangaSource)

	fun onSourceHideClick(source: MangaSource)

	fun onTagClick(tag: String)

	fun onTagLongClick(tag: String)

	fun onAddTagClick()

	fun onSearchTagsClick()
}
