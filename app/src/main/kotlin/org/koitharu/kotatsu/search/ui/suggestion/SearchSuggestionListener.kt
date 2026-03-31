package org.haziffe.dropsauce.search.ui.suggestion

import android.text.TextWatcher
import android.widget.TextView
import org.haziffe.dropsauce.parsers.model.Manga
import org.haziffe.dropsauce.parsers.model.MangaSource
import org.haziffe.dropsauce.parsers.model.MangaTag
import org.haziffe.dropsauce.search.domain.SearchKind

interface SearchSuggestionListener : TextWatcher, TextView.OnEditorActionListener {

	fun onMangaClick(manga: Manga)

	fun onQueryClick(query: String, kind: SearchKind, submit: Boolean)

	fun onSourceToggle(source: MangaSource, isEnabled: Boolean)

	fun onSourceClick(source: MangaSource)

	fun onTagClick(tag: MangaTag)
}
