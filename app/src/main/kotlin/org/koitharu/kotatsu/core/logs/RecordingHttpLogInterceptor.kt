package org.koitharu.kotatsu.core.logs

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

private const val TAG = "HTTP"

/**
 * One line per request (method, url, status, time) while a log is being recorded, so extension and
 * network issues show up in a sent log. Headers and bodies are never logged: they carry cookies/tokens.
 */
class RecordingHttpLogInterceptor : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		if (!AppLogger.isRecording) {
			return chain.proceed(request)
		}
		val start = System.nanoTime()
		val response = try {
			chain.proceed(request)
		} catch (e: Exception) {
			Log.w(TAG, "${request.method} ${request.url} failed after ${elapsedMs(start)}ms: $e")
			throw e
		}
		Log.i(TAG, "${request.method} ${request.url} -> ${response.code} (${elapsedMs(start)}ms)")
		return response
	}

	private fun elapsedMs(start: Long) = (System.nanoTime() - start) / 1_000_000
}
