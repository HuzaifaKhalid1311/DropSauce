package org.koitharu.kotatsu.local.data.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
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

	suspend fun import(uri: Uri): LocalManga {
		val result = if (isDirectory(uri)) {
			importDirectory(uri)
		} else {
			importFile(uri)
		}
		localStorageChanges.emit(result)
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
				PdfRenderer(pfd).use { renderer ->
					if (renderer.pageCount <= 0) {
						throw IOException("PDF has no pages: $uri")
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
								page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
								val entryName = String.format(Locale.US, "%04d.png", index + 1)
								zip.putNextEntry(ZipEntry(entryName))
								bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
								zip.closeEntry()
								bitmap.recycle()
							}
						}
					}
				}
			} ?: throw IOException("Cannot open descriptor for uri: $uri")
			return outputFile
		} catch (e: Exception) {
			outputFile.delete()
			throw e
		}
	}

	private suspend fun importDirectory(uri: Uri): LocalManga {
		val root = requireNotNull(DocumentFile.fromTreeUri(context, uri)) {
			"Provided uri $uri is not a tree"
		}
		val dest = File(getOutputDir(), root.requireName())
		dest.mkdir()
		for (docFile in root.listFiles()) {
			docFile.copyTo(dest)
		}
		return LocalMangaParser(dest).getManga(withDetails = false)
	}

	private suspend fun DocumentFile.copyTo(destDir: File) {
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
			DocumentFile.fromTreeUri(context, uri)
		}.isSuccess
	}

	private companion object {
		private const val PDF_RENDER_SCALE = 2f
		private const val MAX_RENDER_DIMENSION = 4096
	}
}
