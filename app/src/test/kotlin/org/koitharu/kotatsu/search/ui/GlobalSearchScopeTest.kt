package org.koitharu.kotatsu.search.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GlobalSearchScopeTest {

	@Test
	fun `global search uses a transparent selection handle window`() {
		val layouts = listOf(
			"layout/activity_main.xml",
			"layout-w600dp-land/activity_main.xml",
			"layout-w840dp/activity_main.xml",
		)

		assertTrue(layouts.all { resource(it).contains("@style/ThemeOverlay.Kotatsu.SearchText") })
		assertTrue(resource("values/styles.xml").contains("""android:popupBackground">@android:color/transparent"""))
		assertTrue(resource("values/styles.xml").contains("""android:popupElevation">0dp"""))
	}

	@Test
	fun `scope choice on the results screen is persisted`() {
		val settings = source("core/prefs/AppSettings.kt")
		val search = source("search/ui/multi/SearchViewModel.kt")

		assertTrue(settings.contains("varisGlobalSearchNovelScope:Boolean"))
		assertTrue(settings.contains("KEY_GLOBAL_SEARCH_NOVEL_SCOPE"))
		assertTrue(search.contains("MutableStateFlow(settings.isGlobalSearchNovelScope)"))
		assertTrue(search.contains("settings.isGlobalSearchNovelScope=value"))
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
