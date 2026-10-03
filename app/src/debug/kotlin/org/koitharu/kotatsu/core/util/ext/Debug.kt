package org.koitharu.kotatsu.core.util.ext

import android.os.Looper
import org.koitharu.kotatsu.core.logs.logCaughtError

// Debug builds always log caught errors, in the same form a recorded log expects.
fun Throwable.printStackTraceDebug() = logCaughtError()

fun assertNotInMainThread() = check(Looper.myLooper() != Looper.getMainLooper()) {
	"Calling this from the main thread is prohibited"
}
