package org.koitharu.kotatsu.search.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class MangaListActivityInitializationTest {

	@Test
	fun `nsfw check does not depend on source initialized after super onCreate`() {
		val isNsfwContent = source()
			.substringAfter("overridefunisNsfwContent():Flow<Boolean>=")
			.substringBefore("overridefunonApplyWindowInsets(")

		assertEquals(
			"flowOf(MangaSource(intent.getStringExtra(AppRouter.KEY_SOURCE)).isNsfw())",
			isNsfwContent,
		)
	}

	private fun source(): String {
		val relativePath = "org/koitharu/kotatsu/search/ui/MangaListActivity.kt"
		return (
			sequenceOf(
				File("src/main/kotlin", relativePath),
				File("app/src/main/kotlin", relativePath),
			).firstOrNull(File::isFile)?.readText()
				?: error("Cannot find production source: $relativePath")
			)
			.replace(Regex("""//[^\r\n]*"""), "")
			.replace(Regex("""\s+"""), "")
	}
}
