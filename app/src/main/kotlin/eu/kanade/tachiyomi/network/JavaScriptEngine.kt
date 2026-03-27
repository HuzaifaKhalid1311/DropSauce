package eu.kanade.tachiyomi.network

import android.content.Context

/**
 * Util for evaluating JavaScript in sources.
 *
 * Minimal implementation for extension-lib 1.4+ compatibility.
 * Extensions that rely on JS evaluation will get an UnsupportedOperationException.
 */
class JavaScriptEngine(context: Context) {

	/**
	 * Evaluate arbitrary JavaScript code and get the result as a primitive type
	 * (e.g., String, Int).
	 *
	 * @since extensions-lib 1.4
	 * @param script JavaScript to execute.
	 * @return Result of JavaScript code as a primitive type.
	 */
	@Suppress("UNCHECKED_CAST")
	suspend fun <T> evaluate(script: String): T =
		throw UnsupportedOperationException("JavaScriptEngine is not supported in DropSauce")
}
