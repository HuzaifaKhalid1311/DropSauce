package org.koitharu.kotatsu.core.util

import kotlinx.coroutines.CoroutineExceptionHandler
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class AcraCoroutineErrorHandler : AbstractCoroutineContextElement(CoroutineExceptionHandler),
	CoroutineExceptionHandler {

	override fun handleException(context: CoroutineContext, exception: Throwable) {
		exception.printStackTraceDebug()
	}
}
