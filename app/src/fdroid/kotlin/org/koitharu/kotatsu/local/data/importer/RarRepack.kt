package org.koitharu.kotatsu.local.data.importer

import org.koitharu.kotatsu.core.exceptions.UnsupportedFileException
import java.io.File

/**
 * F-Droid version: the only RAR reader (junrar) is not free software, so real RAR archives can't be
 * imported. CBR files that are ZIPs under another name still import (renamed before this is called).
 */
internal fun repackRar(rar: File, cbz: File): Unit = throw UnsupportedFileException("RAR archives are not supported: ${rar.name}")
