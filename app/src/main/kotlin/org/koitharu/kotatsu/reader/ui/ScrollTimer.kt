package org.koitharu.kotatsu.reader.ui

import android.content.res.Resources
import android.os.SystemClock
import android.view.MotionEvent
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.util.ext.resolveDp

private const val MIN_DELAY = 1L
private const val MAX_TICK_STRETCH = 32L
private const val INTERACTION_SKIP_MS = 2_000L
private const val SPEED_FACTOR_DELTA = 0.02f

/**
 * Distance covered per [MIN_DELAY] tick at 100%, in dp. Scroll rate is px/ms, so holding the tick
 * length fixed and scaling only the step makes the speed exactly proportional to the slider
 * position — the old curve derived the *delay* from the slider, which made rate scale as
 * 1/(1 - position) and left everything below ~90% feeling equally slow.
 */
private const val MAX_STEP_DP = 1.587f

/** Below this, stretch the tick rather than waking up 1000x/s to move a fraction of a pixel. */
private const val MIN_STEP_PX = 0.5f

private const val MIN_SPEED = 0.01f

class ScrollTimer @AssistedInject constructor(
	@Assisted resources: Resources,
	@Assisted private val listener: ReaderControlDelegate.OnInteractionListener,
	@Assisted lifecycleOwner: LifecycleOwner,
	settings: AppSettings,
) {

	private val coroutineScope = lifecycleOwner.lifecycleScope
	private var job: Job? = null
	private var delayMs: Long = 0L
	private var stepPx: Float = 0f
	var pageSwitchDelay: Long = 0L
		private set
	private var resumeAt = 0L
	private var isTouchDown = MutableStateFlow(false)
	private val isRunning = MutableStateFlow(false)
	private val maxStepPx = resources.resolveDp(MAX_STEP_DP)

	val isActive: StateFlow<Boolean>
		get() = isRunning

	init {
		settings.observeAsFlow(AppSettings.KEY_READER_AUTOSCROLL_SPEED) {
			readerAutoscrollSpeed
		}.flowOn(Dispatchers.Default)
			.onEach {
				onSpeedChanged(it)
			}.launchIn(coroutineScope)
		settings.observeAsFlow(AppSettings.KEY_READER_AUTOSCROLL_PAGE_DELAY) {
			readerAutoscrollPageDelay
		}.flowOn(Dispatchers.Default)
			.onEach {
				pageSwitchDelay = it * 1000L
			}.launchIn(coroutineScope)
	}

	fun setActive(value: Boolean) {
		if (isRunning.value != value) {
			isRunning.value = value
			restartJob()
		}
	}

	fun onUserInteraction() {
		resumeAt = SystemClock.elapsedRealtime() + INTERACTION_SKIP_MS
	}

	fun onTouchEvent(event: MotionEvent) {
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				isTouchDown.value = true
			}

			MotionEvent.ACTION_UP,
			MotionEvent.ACTION_CANCEL -> {
				isTouchDown.value = false
			}
		}
	}

	// The running job reads delayMs/stepPx every tick, so a speed change takes effect without
	// restarting anything.
	private fun onSpeedChanged(speed: Float) {
		val fraction = speed.coerceIn(MIN_SPEED, 1f)
		val perTick = maxStepPx * fraction
		val ticks = (MIN_STEP_PX / perTick).toLong().coerceIn(1L, MAX_TICK_STRETCH)
		delayMs = MIN_DELAY * ticks
		stepPx = perTick * ticks
	}

	private fun restartJob() {
		job?.cancel()
		resumeAt = 0L
		if (!isRunning.value) {
			job = null
			return
		}
		job = coroutineScope.launch {
			var speedFactor = 1f
			// stepPx is fractional; carry the leftover between ticks so a sub-pixel step still
			// scrolls at the right average rate on every screen density.
			var pixelCarry = 0f
			var lastProgressAt = SystemClock.elapsedRealtime()
			while (isActive) {
				if (isPaused()) {
					speedFactor = (speedFactor - SPEED_FACTOR_DELTA).coerceAtLeast(0f)
				} else if (speedFactor < 1f) {
					speedFactor = (speedFactor + SPEED_FACTOR_DELTA).coerceAtMost(1f)
				}
				if (speedFactor == 1f) {
					delay(delayMs)
				} else if (speedFactor == 0f) {
					delayUntilResumed()
					// The pause is not dwell time on the page, so don't let it count.
					lastProgressAt = SystemClock.elapsedRealtime()
					continue
				} else {
					delay((delayMs * (1f + speedFactor * 2)).toLong())
				}
				if (!listener.isReaderResumed()) {
					continue
				}
				pixelCarry += stepPx
				val step = pixelCarry.toInt()
				var didScroll = false
				if (step > 0) {
					pixelCarry -= step
					didScroll = listener.scrollBy(step, false)
				}
				// Measure real elapsed time. Summing the nominal tick delays undercounted badly —
				// each iteration costs more than it asks to sleep — so a nominal 4s page actually
				// stayed up for 6-7s.
				val now = SystemClock.elapsedRealtime()
				if (didScroll) {
					lastProgressAt = now
				} else if (now - lastProgressAt >= pageSwitchDelay) {
					listener.switchPageBy(1)
					lastProgressAt = SystemClock.elapsedRealtime()
				}
			}
		}
	}

	private fun isPaused(): Boolean {
		return isTouchDown.value || resumeAt > SystemClock.elapsedRealtime()
	}

	private suspend fun delayUntilResumed() {
		while (isPaused()) {
			val delayTime = resumeAt - SystemClock.elapsedRealtime()
			if (delayTime > 0) {
				delay(delayTime)
			} else {
				yield()
			}
			isTouchDown.first { !it }
		}
	}

	@AssistedFactory
	interface Factory {

		fun create(
			resources: Resources,
			lifecycleOwner: LifecycleOwner,
			listener: ReaderControlDelegate.OnInteractionListener,
		): ScrollTimer
	}
}
