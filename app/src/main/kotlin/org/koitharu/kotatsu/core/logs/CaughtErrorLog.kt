package org.koitharu.kotatsu.core.logs

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

private const val LOGCAT_CHUNK = 3500 // logcat truncates a single entry at ~4KB

// Errors whose full trace is already logged; a repeat (e.g. every page of a failing chapter) gets one line.
private val loggedTraces = ConcurrentHashMap.newKeySet<String>()

/** Logs a caught error under the "DropSauce" tag: the full trace the first time, one line on repeats. */
internal fun Throwable.logCaughtError() {
	val signature = "${javaClass.name}: $message @ ${stackTrace.firstOrNull()}"
	if (!loggedTraces.add(signature)) {
		Log.w("DropSauce", "$this (same error again, full trace logged earlier)")
		return
	}
	// stackTraceToString, not Log's throwable overload: that one hides UnknownHostException traces.
	stackTraceToString().chunked(LOGCAT_CHUNK).forEach { Log.w("DropSauce", it) }
}
