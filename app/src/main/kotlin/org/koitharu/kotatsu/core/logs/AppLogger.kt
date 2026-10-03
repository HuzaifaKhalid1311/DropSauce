package org.koitharu.kotatsu.core.logs

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import android.webkit.WebView
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.preference.PreferenceManager
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.util.system.WebViewUtil
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

// 512KB per file, ~1MB across the two rolling files: about an hour of active use with Android's noise
// filtered out, and small enough for an LLM to read in a pass or two.
private const val MAX_FILE_BYTES = 512L * 1024
private const val TAG = "AppLogger"
private const val MAX_SETTING_VALUE_CHARS = 120
private const val MAX_SUMMARY_ERRORS = 40

// Settings whose values must never leave the device.
private val SENSITIVE_SETTING = Regex(
	"password|passwd|salt|token|secret|cookie|login|auth|account|email|proxy_address",
	RegexOption.IGNORE_CASE,
)
private val COMPACT_LINE = Regex("""^(\d\d:\d\d:\d\d\.\d{3}) ([VDIWEF]) (.*?): (.*)$""")

/**
 * One breadcrumb line about what the app is doing (which manga/chapter loaded, from where), written
 * only while a log is being recorded: [message] isn't even built otherwise. Never pass headers,
 * cookies, tokens or bodies, same as [RecordingHttpLogInterceptor].
 */
inline fun breadcrumb(tag: String, message: () -> String) {
	if (AppLogger.isRecording) Log.i(tag, message())
}

/**
 * "Verbose logging": records this process's logcat to disk so a user can reproduce an issue and send
 * the log. The recording spans app restarts and crashes — it is resumed on every launch while the
 * setting is on, and an uncaught exception is written straight to the file before the process dies
 * (the logcat reader thread dies with it, so it would miss the crash itself).
 */
@Singleton
class AppLogger @Inject constructor(
	@ApplicationContext private val context: Context,
	private val mihonExtensionManager: Provider<MihonExtensionManager>,
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
	private val lineFilter = LogcatLineFilter(processTag = context.packageName.takeLast(15))
	private var lastLineBody: String? = null
	private var repeatCount = 0

	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

	// Registered only while recording; guarded by stateLock.
	private var networkCallback: ConnectivityManager.NetworkCallback? = null

	// Held in a field: SharedPreferences only keeps listeners weakly.
	private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
		if (key != null) breadcrumb("Setting") { "$key = ${settingValue(key, prefs.all[key])}" }
	}

	init {
		prefs.registerOnSharedPreferenceChangeListener(settingsListener)
	}

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
				startNetworkWatchLocked()
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
		return if (log.isBlank()) "" else summary(log) + "\n== TIMELINE ==\n" + log
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
					val lastWrittenAt = if (sessionFile.exists()) sessionFile.lastModified() else 0L
					openWriterLocked()
					copyCrashBufferLocked(since = lastWrittenAt)
					val separator = "=".repeat(80)
					writeLocked(
						"\n$separator\n=== SESSION STARTED: ${timestamp()} (PID: $pid) ===\n" +
							"=== Lines timestamped before this are replayed from Android's log buffer: they were\n" +
							"=== logged before recording started, so they lack DropSauce's extra detail.\n$separator",
					)
					writer?.flush()
				}
				startedProcess.inputStream.bufferedReader().use { reader ->
					while (isActive) {
						val line = reader.readLine() ?: break
						val compact = lineFilter.compact(line) ?: continue
						synchronized(fileLock) {
							writeCollapsingLocked(compact)
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
					runCatching { flushRepeatsLocked() }
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
		stopNetworkWatchLocked()
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
						flushRepeatsLocked()
						writeLocked("=== CRASH: ${timestamp()} in thread \"${thread.name}\": $throwable ===\n${throwable.stackTraceToString()}")
						writer?.flush()
					}
				}
			}
			previous?.uncaughtException(thread, throwable)
		}
	}

	/**
	 * A native crash (an extension's native library, an image decoder, WebView) bypasses the Java crash
	 * handler and kills the logcat reader with the process, so its backtrace never reaches the file.
	 * Android keeps it in the crash buffer: on resume, copy what arrived there since the last write.
	 * A Java crash shows up here a second time; ponytail: harmless duplicate, filter if it gets noisy.
	 */
	private fun copyCrashBufferLocked(since: Long) {
		if (since <= 0L) return
		runCatching {
			val sinceArg = String.format(Locale.US, "%d.%03d", since / 1000, since % 1000)
			val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-b", "crash", "-v", "threadtime", "-T", sinceArg))
			val lines = try {
				process.inputStream.bufferedReader().use { reader ->
					reader.readLines().mapNotNull(lineFilter::compact)
				}
			} finally {
				process.destroy()
			}
			if (lines.isNotEmpty()) {
				writeLocked("=== Android crash buffer since this log was last written (${lines.size} lines) ===")
				lines.forEach(::writeLocked)
			}
		}.onFailure { Log.e(TAG, "Failed to read the crash buffer", it) }
	}

	/**
	 * Consecutive identical lines (ignoring the time) are written once and then summarized by a count,
	 * written when a different line arrives or the recording stops.
	 */
	private fun writeCollapsingLocked(line: String) {
		val body = line.substringAfter(' ')
		if (body == lastLineBody) {
			repeatCount++
			return
		}
		flushRepeatsLocked()
		lastLineBody = body
		writeLocked(line)
	}

	private fun flushRepeatsLocked() {
		if (repeatCount > 0) {
			writeLocked("    (previous line repeated $repeatCount more times)")
			repeatCount = 0
		}
		lastLineBody = null
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

	/** What a reader (human or LLM) needs before the timeline: where, what was installed, what went wrong. */
	private fun summary(log: String) = buildString {
		appendLine("== DROPSAUCE BUG REPORT LOG ==")
		appendLine("App: DropSauce ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
		appendLine("Device: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ${Build.SUPPORTED_ABIS.firstOrNull()}")
		appendLine("WebView: ${webViewInfo()}")
		appendLine("Network (at export, changes are in the timeline): ${networkInfo()}")
		appendLine("Exported: ${timestamp()}")
		appendLine("Timeline lines are \"time level tag: message\". Tags: Screen = screen shown, Tap/Menu = user input,")
		appendLine("Setting = setting changed, Search = search run, Reader/DetailsLoad/MangaRepository = what loaded and")
		appendLine("from where, HTTP = network request, Network = connection changed, UserError = error message the user")
		appendLine("saw, DropSauce = caught error.")
		appendLine()
		appendLine("== ERRORS (first seen, times seen) ==")
		val errors = collectErrors(log)
		if (errors.isEmpty()) appendLine("none")
		errors.entries.take(MAX_SUMMARY_ERRORS).forEach { (message, seen) ->
			appendLine("${seen.first} (x${seen.second}) $message")
		}
		if (errors.size > MAX_SUMMARY_ERRORS) {
			appendLine("... ${errors.size - MAX_SUMMARY_ERRORS} more distinct errors in the timeline")
		}
		appendLine()
		appendLine("== EXTENSIONS ==")
		runCatching {
			val manager = mihonExtensionManager.get()
			manager.installedExtensions.value.sortedBy { it.pkgName }.forEach { ext ->
				val sources = ext.sources.joinToString { source ->
					(source as? CatalogueSource)?.let { "${it.name} [${it.lang}] id=${it.id}" } ?: "id=${source.id}"
				}
				appendLine("${ext.pkgName} v${ext.versionName} (lib ${ext.libVersion}): $sources")
			}
			manager.failedExtensions.value.forEach { appendLine("FAILED ${it.pkgName}: ${it.message}") }
		}.onFailure { appendLine("unavailable: $it") }
		appendLine()
		appendLine("== SETTINGS ==")
		runCatching {
			prefs.all.toSortedMap().forEach { (key, value) -> appendLine("$key = ${settingValue(key, value)}") }
		}.onFailure { appendLine("unavailable: $it") }
	}

	// Cloudflare/Turnstile solving and JS-based extensions run in the system WebView; an old or vendor one
	// is a common cause of failures there.
	private fun webViewInfo(): String = runCatching {
		val webView = WebView.getCurrentWebViewPackage() ?: return@runCatching "none"
		val version = webView.versionName.orEmpty()
		val major = version.substringBefore('.').toIntOrNull()
		val outdated = if (major != null && major < WebViewUtil.MINIMUM_WEBVIEW_VERSION) {
			" (OUTDATED, needs ${WebViewUtil.MINIMUM_WEBVIEW_VERSION}+)"
		} else {
			""
		}
		"${webView.packageName} $version$outdated"
	}.getOrElse { "unknown ($it)" }

	/**
	 * One timeline line per connection change (Wi-Fi to cellular, VPN on/off, private DNS switched), so the
	 * network at the moment of the bug is known, not just at export. Capability callbacks also fire for
	 * signal/bandwidth changes, hence logging only when the description actually differs.
	 */
	private fun startNetworkWatchLocked() {
		if (networkCallback != null) return
		runCatching {
			val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
			val callback = object : ConnectivityManager.NetworkCallback() {
				private var last: String? = null

				private fun check() {
					val current = networkInfo()
					if (current != last) {
						last = current
						breadcrumb("Network") { current }
					}
				}

				override fun onAvailable(network: Network) = check()
				override fun onLost(network: Network) = check()
				override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = check()
				override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) = check()
			}
			cm.registerDefaultNetworkCallback(callback)
			networkCallback = callback
		}.onFailure { Log.e(TAG, "Failed to watch the network", it) }
	}

	private fun stopNetworkWatchLocked() {
		val callback = networkCallback ?: return
		networkCallback = null
		runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
	}

	// Ad-blocking private DNS breaks image CDNs; VPN exit IPs are often blocked by Cloudflare.
	private fun networkInfo(): String = runCatching {
		val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching "unknown"
		val network = cm.activeNetwork ?: return@runCatching "offline"
		val caps = cm.getNetworkCapabilities(network)
		val transports = listOfNotNull(
			"Wi-Fi".takeIf { caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true },
			"cellular".takeIf { caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true },
			"ethernet".takeIf { caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true },
			"VPN".takeIf { caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true },
		).joinToString("+").ifEmpty { "other" }
		val metered = if (cm.isActiveNetworkMetered) "metered" else "unmetered"
		val dns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			val link = cm.getLinkProperties(network)
			when {
				link == null -> "private DNS unknown"
				link.isPrivateDnsActive -> "private DNS ${link.privateDnsServerName ?: "automatic"}"
				else -> "no private DNS"
			}
		} else {
			"private DNS n/a"
		}
		"$transports, $metered, $dns"
	}.getOrElse { "unknown ($it)" }

	private fun settingValue(key: String, value: Any?): String = if (SENSITIVE_SETTING.containsMatchIn(key)) {
		"<hidden>"
	} else {
		value.toString().let { if (it.length > MAX_SETTING_VALUE_CHARS) it.take(MAX_SETTING_VALUE_CHARS) + "…" else it }
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

/**
 * Errors a recorded log mentions, keyed by their first line, with when each was first seen and how often,
 * in order of appearance. Stack-trace frames are skipped: only the line naming the error counts.
 */
internal fun collectErrors(log: String): Map<String, Pair<String, Int>> {
	val errors = LinkedHashMap<String, Pair<String, Int>>()
	log.lineSequence().forEach { line ->
		val key = if (line.startsWith("=== CRASH")) {
			line
		} else {
			val match = COMPACT_LINE.matchEntire(line) ?: return@forEach
			val (_, level, tag, message) = match.destructured
			val trimmed = message.trim()
			val isTraceLine = trimmed.startsWith("at ") || trimmed.startsWith("...") ||
				trimmed.startsWith("Caused by") || trimmed.startsWith("Suppressed")
			val isError = level == "E" || level == "F" || tag == "UserError" || tag == "DropSauce"
			if (!isError || isTraceLine || trimmed.isEmpty()) return@forEach
			"$level $tag: ${trimmed.take(200)}"
		}
		val time = if (line.startsWith("=== CRASH")) line.substringAfter("CRASH: ").take(23) else line.substringBefore(' ')
		val seen = errors[key]
		errors[key] = if (seen == null) time to 1 else seen.first to seen.second + 1
	}
	return errors
}
