package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * An OkHttp interceptor that handles rate limiting.
 *
 * Examples:
 *
 * permits = 5,  period = 1, unit = seconds  =>  5 requests per second
 * permits = 10, period = 2, unit = minutes  =>  10 requests per 2 minutes
 *
 * @since extension-lib 1.3
 *
 * @param permits  Number of requests allowed within a period of units.
 * @param period   The limiting duration. Defaults to 1.
 * @param unit     The unit of time for the period. Defaults to seconds.
 */
fun OkHttpClient.Builder.rateLimit(
	permits: Int,
	period: Long = 1,
	unit: TimeUnit = TimeUnit.SECONDS,
): OkHttpClient.Builder = addInterceptor(RateLimitInterceptor(permits, period, unit))

internal class RateLimitInterceptor(
	permits: Int,
	private val period: Long,
	private val unit: TimeUnit,
) : Interceptor {

	private val semaphore = Semaphore(permits)

	@Throws(IOException::class)
	override fun intercept(chain: Interceptor.Chain): Response {
		semaphore.acquire()
		try {
			return chain.proceed(chain.request())
		} finally {
			// Release the permit after the specified time period
			Thread {
				try {
					Thread.sleep(unit.toMillis(period))
				} catch (_: InterruptedException) {
					// ignored
				}
				semaphore.release()
			}.start()
		}
	}
}
