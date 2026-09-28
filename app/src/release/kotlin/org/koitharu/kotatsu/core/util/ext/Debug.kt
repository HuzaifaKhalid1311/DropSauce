package org.koitharu.kotatsu.core.util.ext

import android.util.Log
import org.koitharu.kotatsu.core.logs.AppLogger

private const val LOGCAT_CHUNK = 3500 // logcat truncates a single entry at ~4KB

// Release builds stay quiet about caught errors unless the user is recording a log to send.
fun Throwable.printStackTraceDebug() {
	if (AppLogger.isRecording) {
		// stackTraceToString, not Log's throwable overload: that one hides UnknownHostException traces.
		stackTraceToString().chunked(LOGCAT_CHUNK).forEach { Log.w("DropSauce", it) }
	}
}

fun assertNotInMainThread() = Unit
