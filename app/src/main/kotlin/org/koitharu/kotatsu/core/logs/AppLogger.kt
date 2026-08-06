package org.koitharu.kotatsu.core.logs

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_ENTRIES = 10_000
private const val MAX_FILE_BYTES = 5L * 1024 * 1024 // 5MB cap per file (10MB total across rolling files)

@Singleton
class AppLogger @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	private val logsDir: File
		get() = File(context.filesDir, "logs")
	private val sessionFile: File
		get() = File(logsDir, "session.log")
	private val oldSessionFile: File
		get() = File(logsDir, "session.log.old")

	@Volatile
	var isEnabled: Boolean = false
		private set

	private val buffer = ArrayBlockingQueue<String>(MAX_ENTRIES)
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val stateLock = Any()
	private var readerJob: Job? = null
	private var logcatProcess: Process? = null
	private var generation = 0

	fun getLogContent(): String = runCatching {
		val sb = StringBuilder()
		if (oldSessionFile.exists() && oldSessionFile.length() > 0) {
			sb.append(oldSessionFile.readText())
			if (!sb.endsWith("\n")) sb.append("\n")
		}
		if (sessionFile.exists() && sessionFile.length() > 0) {
			sb.append(sessionFile.readText())
		}
		sb.toString()
	}.getOrDefault("")

	fun setEnabled(enabled: Boolean) {
		synchronized(stateLock) {
			if (enabled == isEnabled) return
			isEnabled = enabled
			generation++
			if (enabled) {
				buffer.clear()
				startReadingLocked(generation)
			} else {
				stopReadingLocked()
			}
		}
	}

	suspend fun stopAndDrainToString(): String {
		val job = synchronized(stateLock) {
			if (isEnabled) {
				isEnabled = false
				generation++
			}
			stopReadingLocked()
		}
		job?.join()
		val diskLogs = getLogContent()
		return diskLogs.ifBlank { drainToString() }
	}

	private fun drainToString(): String {
		val lines = ArrayList<String>(buffer.size)
		buffer.drainTo(lines)
		return lines.joinToString("\n")
	}

	private fun startReadingLocked(readerGeneration: Int) {
		val job = scope.launch(start = CoroutineStart.LAZY) {
			var process: Process? = null
			var writer: BufferedWriter? = null
			var bytesWritten = sessionFile.length()
			try {
				val pid = android.os.Process.myPid().toString()
				val startedProcess = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "threadtime"))
				process = startedProcess
				synchronized(stateLock) {
					if (!isEnabled || generation != readerGeneration) {
						startedProcess.destroy()
						return@launch
					}
					logcatProcess = startedProcess
				}
				writer = runCatching {
					logsDir.mkdirs()
					// Cleanup deprecated previous_session.log if existing from older builds
					File(logsDir, "previous_session.log").delete()

					val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
					val bw = FileWriter(sessionFile, true).buffered()
					bw.write("\n================================================================================\n")
					bw.write("=== SESSION STARTED: $timestamp (PID: $pid) ===\n")
					bw.write("================================================================================\n")
					bw.flush()
					bytesWritten += 180L
					bw
				}.getOrNull()
				BufferedReader(InputStreamReader(startedProcess.inputStream)).use { reader ->
					while (isActive) {
						val line = reader.readLine() ?: break
						if (!isOwnPid(line, pid)) continue
						if (!buffer.offer(line)) {
							buffer.poll()
							buffer.offer(line)
						}
						writer?.let { w ->
							runCatching {
								if (bytesWritten >= MAX_FILE_BYTES) {
									w.flush()
									w.close()
									oldSessionFile.delete()
									sessionFile.renameTo(oldSessionFile)
									val newWriter = FileWriter(sessionFile, false).buffered()
									writer = newWriter
									bytesWritten = 0L
								}
								val out = writer ?: w
								out.write(line)
								out.newLine()
								out.flush() // per-line flush so logs survive a crash
								bytesWritten += line.length + 1
							}
						}
					}
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.e("AppLogger", "Failed to read logcat", e)
			} finally {
				runCatching { writer?.close() }
				process?.destroy()
				synchronized(stateLock) {
					if (generation == readerGeneration) {
						logcatProcess = null
						readerJob = null
					}
				}
			}
		}
		readerJob = job
		job.start()
	}

	/**
	 * threadtime format: `MM-DD HH:MM:SS.mmm  PID  TID L tag: message`.
	 * We spawn an unfiltered logcat (see comment at exec) so the pid check happens here.
	 */
	private fun isOwnPid(line: String, pid: String): Boolean {
		var i = 0
		var field = 0
		while (field < 2) { // skip date and time
			while (i < line.length && line[i] == ' ') i++
			while (i < line.length && line[i] != ' ') i++
			field++
		}
		while (i < line.length && line[i] == ' ') i++
		val start = i
		while (i < line.length && line[i] != ' ') i++
		return i - start == pid.length && line.regionMatches(start, pid, 0, pid.length)
	}

	private fun stopReadingLocked(): Job? {
		val job = readerJob
		readerJob = null
		job?.cancel()
		logcatProcess?.let { process ->
			runCatching { process.inputStream.close() }
			process.destroy()
		}
		logcatProcess = null
		return job
	}
}

