package org.koitharu.kotatsu.search.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GlobalSearchScopeTest {

	@Test
	fun `scope toggle is a leading toolbar action with distinct states`() {
		val menu = resource("menu/opt_search_suggestion.xml")
		val provider = source("search/ui/suggestion/SearchSuggestionMenuProvider.kt")

		assertTrue(menu.contains("""android:id="@+id/action_search_scope""""))
		assertTrue(menu.contains("""android:orderInCategory="0""""))
		assertTrue(menu.contains("""app:showAsAction="always|withText""""))
		assertTrue(provider.contains("R.string.content_type_manga"))
		assertTrue(provider.contains("R.string.content_type_novel"))
	}

	@Test
	fun `scope choice is persisted and refreshes suggestions`() {
		val settings = source("core/prefs/AppSettings.kt")
		val suggestions = source("search/ui/suggestion/SearchSuggestionViewModel.kt")

		assertTrue(settings.contains("varisGlobalSearchNovelScope:Boolean"))
		assertTrue(settings.contains("KEY_GLOBAL_SEARCH_NOVEL_SCOPE"))
		assertTrue(suggestions.contains("observeAsFlow(AppSettings.KEY_GLOBAL_SEARCH_NOVEL_SCOPE)"))
		assertTrue(suggestions.contains("settings.isGlobalSearchNovelScope=!settings.isGlobalSearchNovelScope"))
	}

	@Test
	fun `global results use the Explore source classification everywhere`() {
		val search = source("search/ui/multi/SearchViewModel.kt")

		assertTrue(search.contains(".filter{it.isNovelSource==isNovelScope}"))
		assertTrue(search.contains(".filter{it.source.isNovelSource==isNovelScope}"))
		assertTrue(search.contains("searchLocal():SearchResultsListModel?=if(isNovelScope){null}"))
	}

	private fun resource(relativePath: String): String {
		return sequenceOf(
			File("src/main/res", relativePath),
			File("app/src/main/res", relativePath),
		).firstOrNull(File::isFile)?.readText()
			?: error("Cannot find production resource: $relativePath")
	}

	private fun source(relativePath: String): String {
		return sequenceOf(
			File("src/main/kotlin/org/koitharu/kotatsu", relativePath),
			File("app/src/main/kotlin/org/koitharu/kotatsu", relativePath),
		).firstOrNull(File::isFile)?.readText()
			?.replace(Regex("""//[^\r\n]*"""), "")
			?.replace(Regex("""\s+"""), "")
			?: error("Cannot find production source: $relativePath")
	}
}
