package org.koitharu.kotatsu.local.data.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import org.koitharu.kotatsu.core.exceptions.UnsupportedFileException
import org.koitharu.kotatsu.core.util.ext.openSource
import org.koitharu.kotatsu.core.util.ext.resolveName
import org.koitharu.kotatsu.core.util.ext.writeAllCancellable
import org.koitharu.kotatsu.local.data.hasPdfExtension
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.isSupportedArchive
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.domain.model.LocalManga
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt
import javax.inject.Inject

@Reusable
class SingleMangaImporter @Inject constructor(
	@ApplicationContext private val context: Context,
	private val storageManager: LocalStorageManager,
	@LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalManga?>,
) {

	private val contentResolver = context.contentResolver

	suspend fun import(uri: Uri): List<LocalManga> {
		val result = if (isDirectory(uri)) {
			importDirectory(uri)
		} else {
			listOf(importFile(uri))
		}
		for (manga in result) {
			localStorageChanges.emit(manga)
		}
		return result
	}

	private suspend fun importFile(uri: Uri): LocalManga = withContext(Dispatchers.IO) {
		val contentResolver = storageManager.contentResolver
		val name = contentResolver.resolveName(uri) ?: throw IOException("Cannot fetch name from uri: $uri")
		if (!isSupportedArchive(name)) {
			throw UnsupportedFileException("Unsupported file $name on $uri")
		}
		val dest = if (hasPdfExtension(name)) {
			importPdfAsCbz(uri, name)
		} else {
			File(getOutputDir(), name).also { outputFile ->
				if (outputFile.isDirectory && outputFile.listFiles()?.isEmpty() == true && !outputFile.delete()) {
					throw IOException("Cannot remove empty import directory: $outputFile")
				}
				runInterruptible {
					contentResolver.openSource(uri)
				}.use { source ->
					outputFile.sink().buffer().use { output ->
						output.writeAllCancellable(source)
					}
				}
			}
		}
		LocalMangaParser(dest).getManga(withDetails = false)
	}

	private suspend fun importPdfAsCbz(uri: Uri, sourceName: String): File {
		val outputName = sourceName.substringBeforeLast('.', sourceName) + ".cbz"
		val outputFile = File(getOutputDir(), outputName)
		try {
			contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
				renderPdfToCbz(pfd, outputFile)
			} ?: throw IOException("Cannot open descriptor for uri: $uri")
			return outputFile
		} catch (e: Exception) {
			outputFile.delete()
			throw e
		}
	}

	private fun renderPdfToCbz(pfd: ParcelFileDescriptor, outputFile: File) {
		PdfRenderer(pfd).use { renderer ->
			if (renderer.pageCount <= 0) {
				throw IOException("PDF has no pages")
			}
			ZipOutputStream(outputFile.outputStream().buffered()).use { zip ->
				for (index in 0 until renderer.pageCount) {
					renderer.openPage(index).use { page ->
						val maxPageSize = maxOf(page.width, page.height).coerceAtLeast(1)
						val scale = minOf(PDF_RENDER_SCALE, MAX_RENDER_DIMENSION / maxPageSize.toFloat())
						val matrix = Matrix().apply { setScale(scale, scale) }
						val width = (page.width * scale).roundToInt().coerceAtLeast(1)
						val height = (page.height * scale).roundToInt().coerceAtLeast(1)
						val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
						bitmap.eraseColor(Color.WHITE)
						page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
						val entryName = String.format(Locale.US, "%04d.png", index + 1)
						zip.putNextEntry(ZipEntry(entryName))
						bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
						zip.closeEntry()
						bitmap.recycle()
					}
				}
			}
		}
	}

	private suspend fun importDirectory(uri: Uri): List<LocalManga> = withContext(Dispatchers.IO) {
		val root = requireNotNull(DocumentFile.fromTreeUri(context, uri)) {
			"Provided uri $uri is not a tree"
		}
		// A Mihon downloads root or source folder holds many titles, each of which becomes its own manga
		val mangaDirs = root.findMangaDirs(contentResolver)
		if (mangaDirs.isNotEmpty()) {
			// Two sources can hold the same title, so each one needs a folder of its own here
			mangaDirs.map { importMangaDirectory(it, unique = true) }
		} else {
			listOf(importMangaDirectory(root, unique = false))
		}
	}

	private suspend fun importMangaDirectory(root: DocumentFile, unique: Boolean): LocalManga {
		val allFiles = root.listFiles().filterNot { isImportJunk(it.name.orEmpty()) }
		val pdfFiles = allFiles
			.filter { it.isFile && hasPdfExtension(it.name ?: "") }
			.sortedBy { it.name }

		if (pdfFiles.isNotEmpty()) {
			val dest = destinationDir(root.requireName(), unique)
			dest.mkdir()
			for (pdfFile in pdfFiles) {
				val chapterName = pdfFile.name!!.substringBeforeLast('.')
				val cbzFile = File(dest, "$chapterName.cbz")
				try {
					contentResolver.openFileDescriptor(pdfFile.uri, "r")?.use { pfd ->
						renderPdfToCbz(pfd, cbzFile)
					} ?: throw IOException("Cannot open PDF: ${pdfFile.name}")
				} catch (e: Exception) {
					cbzFile.delete()
					throw e
				}
			}
			return LocalMangaParser(dest).getManga(withDetails = false)
		}

		val dest = destinationDir(root.requireName(), unique)
		dest.mkdir()
		for (docFile in allFiles) {
			docFile.copyTo(dest)
		}
		// No-op unless the folder came from Mihon and carries ComicInfo.xml
		runInterruptible { writeMihonIndex(dest) }
		return LocalMangaParser(dest).getManga(withDetails = false)
	}

	private suspend fun DocumentFile.copyTo(destDir: File) {
		if (isImportJunk(name.orEmpty())) {
			return
		}
		if (isDirectory) {
			val subDir = File(destDir, requireName())
			subDir.mkdir()
			for (docFile in listFiles()) {
				docFile.copyTo(subDir)
			}
		} else {
			source().use { input ->
				File(destDir, requireName()).sink().buffer().use { output ->
					output.writeAllCancellable(input)
				}
			}
		}
	}

	/**
	 * Re-importing the same folder keeps merging into it, as it always has. Only a batch import
	 * sidesteps a folder that is already taken, so two same-named titles do not end up merged.
	 */
	private suspend fun destinationDir(name: String, unique: Boolean): File {
		val outputDir = getOutputDir()
		val dest = File(outputDir, name)
		if (!unique || !dest.exists()) {
			return dest
		}
		var i = 1
		while (true) {
			val candidate = File(outputDir, "${name}_$i")
			if (!candidate.exists()) {
				return candidate
			}
			i++
		}
	}

	private suspend fun getOutputDir(): File {
		return storageManager.getDefaultWriteableDir() ?: throw IOException("External files dir unavailable")
	}

	private suspend fun DocumentFile.source() = runInterruptible(Dispatchers.IO) {
		contentResolver.openSource(uri)
	}

	private fun DocumentFile.requireName(): String {
		return name ?: throw IOException("Cannot fetch name from uri: $uri")
	}

	private fun isDirectory(uri: Uri): Boolean {
		return runCatching {
			DocumentFile.fromTreeUri(context, uri)?.isDirectory == true
		}.getOrDefault(false)
	}

	private companion object {
		private const val PDF_RENDER_SCALE = 4f
		private const val MAX_RENDER_DIMENSION = 4096
	}
}
