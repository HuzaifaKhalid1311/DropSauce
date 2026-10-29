package org.koitharu.kotatsu.core.util.ext

import android.content.BroadcastReceiver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.lifecycle.RetainedLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.core.util.AcraCoroutineErrorHandler
import org.koitharu.kotatsu.core.util.RetainedLifecycleCoroutineScope
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

val processLifecycleScope: CoroutineScope
	get() = ProcessLifecycleOwner.get().lifecycleScope + AcraCoroutineErrorHandler()

val RetainedLifecycle.lifecycleScope: RetainedLifecycleCoroutineScope
	inline get() = RetainedLifecycleCoroutineScope(this)

fun <T> Deferred<T>.getCompletionResultOrNull(): Result<T>? = if (isCompleted) {
	getCompletionExceptionOrNull()?.let { error ->
		Result.failure(error)
	} ?: Result.success(getCompleted())
} else {
	null
}

fun BroadcastReceiver.goAsync(context: CoroutineContext = EmptyCoroutineContext, block: suspend () -> Unit) {
	val pendingResult = goAsync()
	processLifecycleScope.launch(context) {
		try {
			block()
		} finally {
			pendingResult.finish()
		}
	}
}
