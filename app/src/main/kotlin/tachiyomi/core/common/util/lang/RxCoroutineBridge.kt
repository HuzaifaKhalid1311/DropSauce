package tachiyomi.core.common.util.lang

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import rx.Observable
import rx.Subscriber
import rx.Subscription

suspend fun <T> Observable<T>.awaitSingle(): T = single().awaitOne()

@OptIn(InternalCoroutinesApi::class)
private suspend fun <T> Observable<T>.awaitOne(): T = suspendCancellableCoroutine { cont ->
	cont.unsubscribeOnCancellation(
		subscribe(
			object : Subscriber<T>() {
				override fun onStart() {
					request(1)
				}

				override fun onNext(t: T) {
					if (cont.isActive) {
						cont.resume(t)
					}
					unsubscribe()
				}

				override fun onCompleted() {
					if (cont.isActive) {
						cont.resumeWithException(IllegalStateException("Should have invoked onNext"))
					}
				}

				override fun onError(e: Throwable) {
					if (cont.isActive) {
						cont.resumeWithException(e)
					}
				}
			},
		),
	)
}

private fun <T> CancellableContinuation<T>.unsubscribeOnCancellation(sub: Subscription) =
	invokeOnCancellation { sub.unsubscribe() }
