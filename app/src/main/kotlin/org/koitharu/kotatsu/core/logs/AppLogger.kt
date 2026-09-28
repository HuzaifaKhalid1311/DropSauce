package org.koitharu.kotatsu.core.logs

import android.content.Context
import android.os.Build
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
import org.koitharu.kotatsu.BuildConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// 1.5MB per file, 3MB across the two rolling files: hours of normal use, and small enough to attach anywhere.
private const val MAX_FILE_BYTES = 1536L * 1024
private const val TAG = "AppLogger"

/**
 * "Verbose logging": records this process's logcat to disk so a user can reproduce an issue and send
 * the log. The recording spans app restarts and crashes — it is resumed on every launch while the
 * setting is on, and an uncaught exception is written straight to the file before the process dies
 * (the logcat reader thread dies with it, so it would miss the crash itself).
 */
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

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val stateLock = Any()
	private var readerJob: Job? = null
	private var logcatProcess: Process? = null
	private var generation = 0
	private var isCrashHandlerInstalled = false

	// Shared by the logcat reader and the crash handler; guarded by fileLock.
	private val fileLock = Any()
	private var writer: BufferedWriter? = null
	private var bytesWritten = 0L

	val isEnabled: Boolean
		get() = isRecording

	/** Resumes (or stops) recording, appending to the current log. Called on every app start. */
	fun setEnabled(enabled: Boolean) {
		synchronized(stateLock) {
			if (enabled == isRecording) return
			isRecording = enabled
			generation++
			if (enabled) {
				installCrashHandlerLocked()
				startReadingLocked(generation)
			} else {
				stopReadingLocked()
			}
		}
	}

	/** Starts a fresh recording: whatever an earlier, unsent recording left behind is dropped. */
	suspend fun startNewRecording() {
		stopAndJoin()
		clear()
		setEnabled(true)
	}

	/** Stops recording and returns the whole log (all sessions since it was turned on), or "" if empty. */
	suspend fun stopAndExport(): String {
		stopAndJoin()
		val log = runCatching {
			buildString {
				for (file in arrayOf(oldSessionFile, sessionFile)) {
					if (file.exists() && file.length() > 0) {
						append(file.readText())
						if (!endsWith('\n')) append('\n')
					}
				}
			}
		}.getOrDefault("")
		return if (log.isBlank()) "" else deviceInfo() + log
	}

	/** Deletes the recorded log, e.g. once it has been saved. */
	fun clear() {
		synchronized(fileLock) {
			oldSessionFile.delete()
			sessionFile.delete()
			bytesWritten = 0L
		}
	}

	// Waits for the reader to close its writer, so it can't race a following clear() or new reader.
	private suspend fun stopAndJoin() {
		val job = synchronized(stateLock) {
			if (isRecording) {
				isRecording = false
				generation++
			}
			stopReadingLocked()
		}
		job?.join()
	}

	private fun startReadingLocked(readerGeneration: Int) {
		val job = scope.launch(start = CoroutineStart.LAZY) {
			var process: Process? = null
			try {
				val pid = android.os.Process.myPid()
				// --pid makes logcat itself drop other processes (incl. this app's own side processes).
				// It first replays what the ring buffer still holds for this process, so a recording
				// resumed at app start also covers the launch that happened before this point.
				val startedProcess = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "threadtime", "--pid=$pid"))
				process = startedProcess
				synchronized(stateLock) {
					if (!isRecording || generation != readerGeneration) {
						startedProcess.destroy()
						return@launch
					}
					logcatProcess = startedProcess
				}
				synchronized(fileLock) {
					openWriterLocked()
					val separator = "=".repeat(80)
					writeLocked("\n$separator\n=== SESSION STARTED: ${timestamp()} (PID: $pid) ===\n$separator")
					writer?.flush()
				}
				startedProcess.inputStream.bufferedReader().use { reader ->
					while (isActive) {
						val line = reader.readLine() ?: break
						synchronized(fileLock) {
							writeLocked(line)
							// Flush whenever logcat has nothing more queued: cheap under bursts, and
							// nothing sits in the buffer while the app is idle.
							if (!reader.ready()) writer?.flush()
						}
					}
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Log.e(TAG, "Failed to read logcat", e)
			} finally {
				synchronized(fileLock) {
					runCatching { writer?.close() }
					writer = null
				}
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

	// Wraps the existing handler (ACRA's), so crash reports and the crash dialog keep working.
	private fun installCrashHandlerLocked() {
		if (isCrashHandlerInstalled) return
		isCrashHandlerInstalled = true
		val previous = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
			if (isRecording) {
				runCatching {
					synchronized(fileLock) {
						openWriterLocked()
						writeLocked("=== CRASH: ${timestamp()} in thread \"${thread.name}\" ===\n${throwable.stackTraceToString()}")
						writer?.flush()
					}
				}
			}
			previous?.uncaughtException(thread, throwable)
		}
	}

	private fun openWriterLocked() {
		if (writer != null) return
		logsDir.mkdirs()
		bytesWritten = sessionFile.length()
		writer = FileWriter(sessionFile, true).buffered()
	}

	private fun writeLocked(text: String) {
		if (bytesWritten >= MAX_FILE_BYTES) {
			runCatching { writer?.close() }
			oldSessionFile.delete()
			sessionFile.renameTo(oldSessionFile)
			writer = FileWriter(sessionFile, false).buffered()
			bytesWritten = 0L
		}
		val out = writer ?: return
		out.write(text)
		out.newLine()
		bytesWritten += text.length + 1 // ponytail: chars, not bytes; the cap is approximate
	}

	private fun deviceInfo() = buildString {
		appendLine("DropSauce ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
		appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ${Build.SUPPORTED_ABIS.firstOrNull()}")
		appendLine("Exported: ${timestamp()}")
	}

	private fun timestamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

	companion object {

		/** Whether a log is being recorded right now; lets release builds log extra detail only then. */
		@Volatile
		@JvmStatic
		var isRecording: Boolean = false
			private set
	}
}
