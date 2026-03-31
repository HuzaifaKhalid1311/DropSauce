package org.haziffe.dropsauce.core.util

import kotlinx.coroutines.CoroutineExceptionHandler
import org.haziffe.dropsauce.core.util.ext.printStackTraceDebug
import org.haziffe.dropsauce.core.util.ext.report
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class AcraCoroutineErrorHandler : AbstractCoroutineContextElement(CoroutineExceptionHandler),
	CoroutineExceptionHandler {

	override fun handleException(context: CoroutineContext, exception: Throwable) {
		exception.printStackTraceDebug()
		exception.report()
	}
}
