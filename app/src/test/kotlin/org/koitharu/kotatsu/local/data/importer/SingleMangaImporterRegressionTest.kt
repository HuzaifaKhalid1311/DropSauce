package org.koitharu.kotatsu.local.data.importer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SingleMangaImporterRegressionTest {

	@Test
	fun `ordinary document uri is not assumed to be a directory`() {
		val source = source()
		val isDirectory = source
			.substringAfter("private fun isDirectory(uri: Uri): Boolean")
			.substringBefore("\n\t}")
			.replace(Regex("\\s+"), "")

		assertTrue(isDirectory.contains("?.isDirectory==true"))
		assertFalse(isDirectory.contains("}.isSuccess"))
	}

	@Test
	fun `file import removes only an empty directory left by the old bug`() {
		val source = source().replace(Regex("\\s+"), "")

		assertTrue(
			source.contains(
				"outputFile.isDirectory&&outputFile.listFiles()?.isEmpty()==true&&!outputFile.delete()",
			),
		)
		assertFalse(source.contains("outputFile.deleteRecursively()"))
	}

	private fun source(): String {
		val relativePath =
			"org/koitharu/kotatsu/local/data/importer/SingleMangaImporter.kt"
		return sequenceOf(
			File("src/main/kotlin", relativePath),
			File("app/src/main/kotlin", relativePath),
		)
			.firstOrNull(File::isFile)
			?.readText()
			?: error("Cannot find production source: $relativePath")
	}
}
