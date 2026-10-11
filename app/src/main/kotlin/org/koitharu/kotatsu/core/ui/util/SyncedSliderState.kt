package org.koitharu.kotatsu.core.ui.util

import androidx.compose.material3.RangeSliderState
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Slider state that keeps following [value] from the caller, as the old value-based Slider overload did. */
@Composable
fun syncedSliderState(
	value: Float,
	valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
	steps: Int = 0,
): SliderState {
	val state = remember(steps, valueRange) { SliderState(value, steps, valueRange) }
	state.value = value
	return state
}

/** Range slider counterpart of [syncedSliderState]. */
@Composable
fun syncedRangeSliderState(
	value: ClosedFloatingPointRange<Float>,
	valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
	steps: Int = 0,
): RangeSliderState {
	val state = remember(steps, valueRange) { RangeSliderState(value.start, value.endInclusive, steps, valueRange) }
	state.startValue = value.start
	state.endValue = value.endInclusive
	return state
}
