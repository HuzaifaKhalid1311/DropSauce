package org.koitharu.kotatsu.core.util.ext

import org.koitharu.kotatsu.core.logs.AppLogger
import org.koitharu.kotatsu.core.logs.logCaughtError

// Release builds stay quiet about caught errors unless the user is recording a log to send.
fun Throwable.printStackTraceDebug() {
	if (AppLogger.isRecording) logCaughtError()
}

fun assertNotInMainThread() = Unit
