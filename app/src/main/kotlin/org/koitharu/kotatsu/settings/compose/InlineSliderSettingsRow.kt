package org.koitharu.kotatsu.settings.compose

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect
import org.koitharu.kotatsu.core.ui.util.syncedSliderState

/**
 * A settings row that hosts a Slider directly inline (no dialog). The slider sits below
 * the title line; the current value shows in a pill at the end of it.
 */
@Composable
internal fun InlineSliderSettingsRow(
	title: String,
	value: Int,
	valueFrom: Int,
	valueTo: Int,
	stepSize: Int,
	unitSuffix: String,
	onValueChange: (Int) -> Unit,
	modifier: Modifier = Modifier,
	valueLabel: ((Int) -> String)? = null,
	@DrawableRes icon: Int? = null,
	iconColors: CategoryIconColors? = null,
	shape: Shape = MaterialTheme.shapes.medium,
	enabled: Boolean = true,
) {
	var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
	val steps = if (stepSize > 0) ((valueTo - valueFrom) / stepSize - 1).coerceAtLeast(0) else 0
	val haptic = rememberHapticEffect()
	// Track the last stepped value so a tick fires only when the slider snaps to a new step,
	// not on every sub-pixel drag frame.
	var lastStep by remember(value) { mutableFloatStateOf(value.toFloat()) }

	val cs = MaterialTheme.colorScheme
	SettingsItem(
		title = title,
		modifier = modifier,
		icon = icon,
		iconColors = iconColors,
		shape = shape,
		enabled = enabled,
		trailing = {
			Surface(
				shape = CircleShape,
				color = cs.secondaryContainer.copy(alpha = if (enabled) 1f else 0.38f),
				contentColor = cs.onSecondaryContainer.copy(alpha = if (enabled) 1f else 0.38f),
			) {
				Text(
					text = valueLabel?.invoke(current.roundToInt()) ?: "${current.roundToInt()}$unitSuffix",
					style = MaterialTheme.typography.labelLarge,
					modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
				)
			}
		},
		below = {
			Slider(
				state = syncedSliderState(current, valueFrom.toFloat()..valueTo.toFloat(), steps),
				onValueChange = {
					current = it
					val snapped = it.roundToInt().toFloat()
					if (snapped != lastStep) {
						lastStep = snapped
						haptic(HapticEffect.TICK)
					}
				},
				onValueChangeFinished = {
					haptic(HapticEffect.GESTURE_END)
					onValueChange(current.roundToInt())
				},
				enabled = enabled,
				modifier = Modifier
					.fillMaxWidth()
					.padding(start = if (icon != null) 58.dp else 0.dp),
			)
		},
	)
}
