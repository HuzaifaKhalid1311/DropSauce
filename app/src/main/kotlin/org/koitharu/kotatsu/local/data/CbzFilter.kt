package org.koitharu.kotatsu.local.data

import org.koitharu.kotatsu.core.model.isNovelSource
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
