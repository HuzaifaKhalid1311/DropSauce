package org.koitharu.kotatsu.local.data

import java.io.File

private fun isZipExtension(ext: String?): Boolean {
	return ext.equals("cbz", ignoreCase = true) || ext.equals("zip", ignoreCase = true)
}

private fun isPdfExtension(ext: String?): Boolean {
	return ext.equals("pdf", ignoreCase = true)
}

fun hasZipExtension(string: String): Boolean {
	val ext = string.substringAfterLast('.', "")
	return isZipExtension(ext)
}

fun hasPdfExtension(string: String): Boolean {
	val ext = string.substringAfterLast('.', "")
	return isPdfExtension(ext)
}

fun isSupportedArchive(string: String): Boolean {
	val ext = string.substringAfterLast('.', "")
	return isZipExtension(ext) || isPdfExtension(ext)
}

val File.isZipArchive: Boolean
	get() = isFile && isZipExtension(extension)

val File.isPdfFile: Boolean
	get() = isFile && isPdfExtension(extension)

val File.isSupportedMangaArchive: Boolean
	get() = isFile && (isZipExtension(extension) || isPdfExtension(extension))
