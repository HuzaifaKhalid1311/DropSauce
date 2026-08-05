package org.koitharu.kotatsu.local.data

import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.local.data.output.LocalMangaOutput.Companion.ENTRY_NAME_INDEX
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File

private fun isZipExtension(ext: String?): Boolean {
	return ext.equals("cbz", ignoreCase = true) || ext.equals("zip", ignoreCase = true)
}

private fun isPdfExtension(ext: String?): Boolean {
	return ext.equals("pdf", ignoreCase = true)
}

private fun isEpubExtension(ext: String?): Boolean {
	return ext.equals("epub", ignoreCase = true)
}

fun hasZipExtension(string: String): Boolean {
	val ext = string.substringAfterLast('.', "")
	return isZipExtension(ext)
}

fun hasPdfExtension(string: String): Boolean {
	val ext = string.substringAfterLast('.', "")
	return isPdfExtension(ext)
}

fun hasEpubExtension(string: String): Boolean {
	val ext = string.substringAfterLast('.', "")
	return isEpubExtension(ext)
}

fun isSupportedArchive(string: String): Boolean {
	val ext = string.substringAfterLast('.', "")
	return isZipExtension(ext) || isPdfExtension(ext) || isEpubExtension(ext)
}

/** Deep enough for `book/volume/chapter/page.jpg`, shallow enough not to walk a whole memory card. */
private const val CONTENT_SCAN_DEPTH = 3

/**
 * True when a folder actually holds manga: an index, an archive, or an image somewhere near the top.
 * Any other folder that happens to sit in a storage dir — fonts, novel plugins, translations,
 * backups — would otherwise be listed as a manga with no cover and no chapters.
 */
@Blocking
fun File.hasMangaContent(depth: Int = CONTENT_SCAN_DEPTH): Boolean {
	val children = listFiles() ?: return false
	if (children.any { it.isFile && (it.name == ENTRY_NAME_INDEX || it.isMangaContentFile) }) {
		return true
	}
	return depth > 0 && children.any { it.isDirectory && it.hasMangaContent(depth - 1) }
}

// ponytail: an extension set rather than MimeTypes — the scan above visits every file in every
// storage dir, and the platform mime lookup is slower per file. Add a format here if the reader
// learns to decode a new one.
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp", "heic", "heif", "jxl")

private val File.isMangaContentFile: Boolean
	get() = isSupportedArchive(name) || extension.lowercase() in IMAGE_EXTENSIONS

val File.isZipArchive: Boolean
	get() = isFile && isZipExtension(extension)

val File.isEpubFile: Boolean
	get() = isFile && isEpubExtension(extension)

/**
 * True for anything the text reader handles: a local EPUB book (the manga is a single .epub file or
 * its chapters point inside one) or a novel source, whose "pages" are prose fetched over the network
 * rather than images.
 */
val Manga.isEpub: Boolean
	get() = source.isNovelSource ||
		hasEpubExtension(url.substringBefore('#')) ||
		chapters?.firstOrNull()?.let { hasEpubExtension(it.url.substringBefore('#')) } == true
