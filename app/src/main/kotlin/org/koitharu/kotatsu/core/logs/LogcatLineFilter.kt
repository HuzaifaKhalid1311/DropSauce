package org.koitharu.kotatsu.core.logs

/**
 * Turns one `logcat -v threadtime` line into the compact form a recorded log keeps
 * ("07:31:28.202 W Tag: message"), or null to drop it. Kept: everything from DropSauce and its
 * extensions, plus warnings and errors from Android. Dropped: Android's own chatter that never explains
 * an app bug but used to fill most of the log (graphics driver, framework info, known-harmless warnings).
 *
 * @param processTag the tag ART logs this process's runtime messages under: the last 15 characters of
 * the process name (e.g. "ziffe.dropsauce").
 */
internal class LogcatLineFilter(private val processTag: String) {

	fun compact(line: String): String? {
		val match = THREADTIME.matchEntire(line) ?: return line.takeUnless { it.startsWith("--------- beginning of") }
		val (time, level, tag, message) = match.destructured
		if (tag in SECRET_TAGS || NOISY_TAG_PREFIXES.any { tag.startsWith(it) }) return null
		if (NOISY_MESSAGES.any { message.contains(it) }) return null
		// Below warning level only the app's own lines matter; the runtime's GC/verification info doesn't.
		if (level[0] in "VDI" && (tag == processTag || tag in FRAMEWORK_INFO_TAGS)) return null
		return "$time $level $tag: $message"
	}

	private companion object {

		// "09-30 07:31:28.202 14284 14290 W Bundle  : message" (the message may be empty)
		val THREADTIME = Regex("""^\d\d-\d\d (\d\d:\d\d:\d\d\.\d{3})\s+\d+\s+\d+ ([VDIWEF]) (.*?)\s*:\s?(.*)$""")

		// Debug builds' CurlLoggingInterceptor dumps every request with its headers (cookies, tokens) and
		// body. RecordingHttpLogInterceptor already logs one safe line per request.
		val SECRET_TAGS = setOf("CURL")

		// Graphics drivers and the rendering pipeline: hundreds of lines a minute, vendor-specific names.
		val NOISY_TAG_PREFIXES = arrayOf(
			"Adreno", "mali_", "gralloc", "qdgralloc", "Gralloc", "libEGL", "OpenGLRenderer",
			"HWUI", "Surface", "BLASTBufferQueue", "Dawn", "vulkan", "SurfaceComposer",
		)

		// Known-harmless warnings. The extension class-loader warning is fired by ART for every class an
		// extension loads; hidden-API lines come from the menu-icon reflection in BaseActivity.
		val NOISY_MESSAGES = arrayOf(
			"locale list changing from",
			"Unsupported class loader",
			"hiddenapi: Accessing hidden",
			"The elegant text height cannot be turned off",
			"A resource failed to call",
			"onNetworkChanged()",
		)

		val FRAMEWORK_INFO_TAGS = setOf(
			"AconfigFlags", "DisplayManager", "ImeTracker", "InputMethodManager", "AutofillManager",
			"WindowOnBackDispatcher", "InsetsController", "ViewRootImpl", "VideoCapabilities",
			"AudioCapabilities", "CompatChangeReporter", "nativeloader", "ProfileInstaller",
			"cr_CombinedPProvider", "cr_VAUtil", "cr_LibraryLoader", "cr_WebViewApkApplication",
		)
	}
}
