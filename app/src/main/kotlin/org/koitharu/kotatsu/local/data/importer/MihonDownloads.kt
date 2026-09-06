package org.koitharu.kotatsu.local.data.importer

import android.content.ContentResolver
import android.util.Xml
import androidx.documentfile.provider.DocumentFile
import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.util.AlphanumComparator
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.local.data.MangaIndex
import org.koitharu.kotatsu.local.data.hasImageExtension
import org.koitharu.kotatsu.local.data.hasZipExtension
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput.Companion.ENTRY_NAME_INDEX
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.util.longHashCode
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Mihon/Tachiyomi keep downloads as `downloads/<Source>/<Manga title>/<Chapter>/000.jpg`, or with
 * `<Chapter>.cbz` instead of the chapter directory. Every chapter also carries a `ComicInfo.xml`
 * describing both the chapter and the series.
 *
 * Nothing here changes how this app writes its own downloads: a picked folder is only treated as a
 * Mihon one when it actually looks like it, otherwise the plain "folder with images" import runs
 * unchanged.
 */
private const val COMIC_INFO_FILE = "ComicInfo.xml"

/** Mihon's unfinished downloads: `Chapter_tmp` directories and `Chapter.cbz_tmp` archives. */
private const val TMP_SUFFIX = "_tmp"

/** `downloads/<source>/<manga>` — two levels above the manga folder is as deep as we look. */
private const val CONTAINER_DEPTH = 2

fun isImportJunk(name: String): Boolean =
	name == ".nomedia" || name.endsWith(TMP_SUFFIX) || name.endsWith(".tmp")

/**
 * The manga folders inside a picked directory, when that directory is a Mihon source folder or the
 * whole downloads root. Empty when the folder is itself a single title — the caller then imports it
 * as one manga, exactly as before.
 *
 * A folder only counts as a title of its own when it is *marked*: it carries this app's own
 * `index.json`, or a chapter of it carries Mihon's `ComicInfo.xml`. Without that proof a nested
 * folder is just a volume or a chapter of the picked title, so nothing is split up.
 */
fun DocumentFile.findMangaDirs(contentResolver: ContentResolver): List<DocumentFile> {
	if (looksLikeMangaDir()) {
		return emptyList()
	}
	return collectMangaDirs(contentResolver, CONTAINER_DEPTH)
}

private fun DocumentFile.collectMangaDirs(
	contentResolver: ContentResolver,
	depth: Int,
): List<DocumentFile> {
	if (depth <= 0) {
		return emptyList()
	}
	val children = listFiles().filter { it.isDirectory && !isImportJunk(it.name.orEmpty()) }
	val result = ArrayList<DocumentFile>()
	for (child in children) {
		if (child.isMarkedMangaDir(contentResolver)) {
			result.add(child)
		} else {
			result.addAll(child.collectMangaDirs(contentResolver, depth - 1))
		}
	}
	return result.sortedWith(compareBy(AlphanumComparator()) { it.name.orEmpty() })
}

/** A downloaded title, in either format: this app writes an index, Mihon writes a ComicInfo. */
private fun DocumentFile.isMarkedMangaDir(contentResolver: ContentResolver): Boolean {
	val children = listFiles()
	if (children.any { it.isFile && it.name == ENTRY_NAME_INDEX }) {
		return true
	}
	if (children.any { it.isDirectory && it.listFiles().any { file -> file.name == COMIC_INFO_FILE } }) {
		return true
	}
	// An archived Mihon chapter keeps its ComicInfo inside the cbz; one archive is enough to tell
	val archive = children.firstOrNull { it.isFile && hasZipExtension(it.name.orEmpty()) } ?: return false
	return runCatching {
		contentResolver.openInputStream(archive.uri)?.use { input ->
			ZipInputStream(input.buffered()).use { zip ->
				generateSequence { zip.nextEntry }.any { it.name.substringAfterLast('/') == COMIC_INFO_FILE }
			}
		}
	}.getOrNull() == true
}

/** A manga folder holds chapters: archives, or directories of images. */
private fun DocumentFile.looksLikeMangaDir(): Boolean = listFiles().any { it.looksLikeChapter() }

private fun DocumentFile.looksLikeChapter(): Boolean {
	val name = name.orEmpty()
	if (isImportJunk(name)) {
		return false
	}
	return if (isDirectory) {
		listFiles().any { it.isFile && (hasImageExtension(it.name.orEmpty()) || it.name == COMIC_INFO_FILE) }
	} else {
		hasZipExtension(name)
	}
}

/**
 * Writes an `index.json` for a just-copied Mihon download so the title, author, tags, description
 * and the real chapter numbers/names survive the import instead of being guessed from file names.
 * Returns false when the folder carries no `ComicInfo.xml` at all, leaving the folder untouched for
 * the regular file-name based parsing.
 */
@Blocking
fun writeMihonIndex(dir: File): Boolean = runCatching {
	if (File(dir, ENTRY_NAME_INDEX).exists()) {
		// The folder came from this app (or a previous import) and already describes itself
		return@runCatching false
	}
	val index = MangaIndex(null)
	val chapterFiles = dir.listFiles()
		?.filterNot { isImportJunk(it.name) }
		?.filter { (it.isDirectory && it.hasPages()) || hasZipExtension(it.name) }
		?.sortedWith(compareBy(AlphanumComparator()) { it.name })
		.orEmpty()
	val series = chapterFiles.firstNotNullOfOrNull { it.readComicInfo() } ?: return@runCatching false
	index.setMangaInfo(
		Manga(
			id = dir.absolutePath.longHashCode(),
			title = series.series ?: dir.name,
			altTitles = emptySet(),
			url = dir.toURI().toString(),
			publicUrl = "",
			rating = -1f,
			contentRating = null,
			coverUrl = null,
			largeCoverUrl = null,
			tags = series.genres.mapTo(LinkedHashSet()) { tag ->
				MangaTag(title = tag.toTitleCase(), key = tag.lowercase(), source = UnknownMangaSource)
			},
			state = series.state,
			authors = setOfNotNull(series.writer, series.penciller).flatMapTo(LinkedHashSet()) { value ->
				value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
			},
			description = series.summary,
			chapters = emptyList(),
			source = UnknownMangaSource,
		),
	)
	chapterFiles.forEachIndexed { i, file ->
		// A chapter downloaded by an older Tachiyomi has no ComicInfo; keep it, named after its
		// folder, instead of dropping it from the index and losing it entirely
		val info = file.readComicInfo()
		index.addChapter(
			IndexedValue(
				i,
				MangaChapter(
					id = "${dir.name}/${file.name}".longHashCode(),
					title = info?.title ?: file.chapterNameFromFileName(),
					number = info?.number ?: (i + 1).toFloat(),
					volume = 0,
					url = file.name,
					scanlator = info?.translator,
					uploadDate = file.lastModified(),
					branch = null,
					source = UnknownMangaSource,
				),
			),
			filename = file.name,
		)
	}
	File(dir, ENTRY_NAME_INDEX).writeText(index.toString())
	true
}.onFailure { e ->
	e.printStackTraceDebug()
}.getOrDefault(false)

/** Mihon names a chapter folder `<Scanlator>_<Chapter name>_<6 hex of the chapter url>`. */
private fun File.chapterNameFromFileName(): String = name.substringBeforeLast('.')
	.replace(REGEX_CHAPTER_HASH_SUFFIX, "")

private val REGEX_CHAPTER_HASH_SUFFIX = Regex("_[0-9a-f]{6}$")

private fun File.hasPages(): Boolean = listFiles()?.any { it.isFile && hasImageExtension(it.name) } == true

private fun File.readComicInfo(): ComicInfo? = runCatching {
	when {
		isDirectory -> File(this, COMIC_INFO_FILE).takeIf { it.isFile }?.inputStream()?.use { parseComicInfo(it) }

		hasZipExtension(name) -> ZipFile(this).use { zip ->
			zip.getEntry(COMIC_INFO_FILE)?.let { entry ->
				zip.getInputStream(entry).use { parseComicInfo(it) }
			}
		}

		else -> null
	}
}.getOrNull()

private class ComicInfo(
	val series: String?,
	val title: String?,
	val number: Float?,
	val summary: String?,
	val writer: String?,
	val penciller: String?,
	val translator: String?,
	val web: String?,
	val genres: Set<String>,
	val state: MangaState?,
)

private fun parseComicInfo(input: InputStream): ComicInfo {
	val values = HashMap<String, String>()
	val parser = Xml.newPullParser()
	parser.setInput(input, null)
	var tag: String? = null
	val text = StringBuilder()
	while (parser.next() != XmlPullParser.END_DOCUMENT) {
		when (parser.eventType) {
			XmlPullParser.START_TAG -> {
				// Mihon writes its own fields under a `ty:`/`mh:` prefix
				tag = parser.name.substringAfterLast(':')
				text.setLength(0)
			}

			XmlPullParser.TEXT -> text.append(parser.text)
			XmlPullParser.END_TAG -> {
				if (tag != null && parser.name.substringAfterLast(':') == tag) {
					text.toString().trim().takeIf { it.isNotEmpty() }?.let { values[tag] = it }
					tag = null
				}
			}
		}
	}
	return ComicInfo(
		series = values["Series"],
		title = values["Title"],
		number = values["Number"]?.toFloatOrNull(),
		summary = values["Summary"],
		writer = values["Writer"],
		penciller = values["Penciller"],
		translator = values["Translator"],
		web = values["Web"]?.substringBefore(' '),
		genres = listOfNotNull(values["Genre"], values["Tags"], values["Categories"])
			.flatMap { it.split(',') }
			.mapNotNullTo(LinkedHashSet()) { it.trim().takeIf(String::isNotEmpty) },
		state = when (values["PublishingStatusTachiyomi"]) {
			"Ongoing" -> MangaState.ONGOING
			"Completed", "Publishing finished" -> MangaState.FINISHED
			"Cancelled" -> MangaState.ABANDONED
			"On hiatus" -> MangaState.PAUSED
			"Licensed" -> MangaState.RESTRICTED
			else -> null
		},
	)
}
