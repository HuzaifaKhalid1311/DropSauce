package org.koitharu.kotatsu.local.data.importer

import com.github.junrar.Archive
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Copies every page of a real RAR archive into a new CBZ. GitHub version only: junrar is not free software. */
internal fun repackRar(rar: File, cbz: File) {
	Archive(rar).use { archive ->
		ZipOutputStream(cbz.outputStream().buffered()).use { zip ->
			// Pages are already compressed images, deflating them again only costs time
			zip.setLevel(Deflater.NO_COMPRESSION)
			for (header in archive) {
				if (header.isDirectory) {
					continue
				}
				zip.putNextEntry(ZipEntry(header.fileName.replace('\\', '/')))
				archive.extractFile(header, zip)
				zip.closeEntry()
			}
		}
	}
}
