package org.koitharu.kotatsu.settings.compose

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect
import org.koitharu.kotatsu.main.ui.nav.rememberAnyDrawablePainter
import org.koitharu.kotatsu.core.ui.util.syncedSliderState

/**
 * A settings row that hosts a Slider directly inline (no dialog). The slider sits below
 * the title/value line. Lives outside SettingsItem.kt so it can use the package-private
 * icon helpers without exposing them publicly.
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

	Surface(
		modifier = modifier,
		shape = shape,
		color = MaterialTheme.colorScheme.surfaceContainer,
	) {
		Column(
			modifier = Modifier
				.heightIn(min = 72.dp)
				.padding(horizontal = 12.dp, vertical = 10.dp),
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Row(
				verticalAlignment = Alignment.CenterVertically,
			) {
				if (icon != null) {
					SettingsIconForInline(icon, iconColors, enabled)
					Spacer(Modifier.width(14.dp))
				}
				Text(
					text = title,
					style = MaterialTheme.typography.titleMedium,
					color = textColor(enabled),
					modifier = Modifier.weight(1f),
				)
				Spacer(Modifier.width(8.dp))
				Text(
					text = valueLabel?.invoke(current.roundToInt()) ?: "${current.roundToInt()}$unitSuffix",
					style = MaterialTheme.typography.labelLarge,
					color = if (enabled) MaterialTheme.colorScheme.primary
					else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
				)
			}
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
		}
	}
}

@Composable
private fun textColor(enabled: Boolean): Color {
	val base = LocalContentColor.current
	return if (enabled) base else base.copy(alpha = 0.38f)
}

@Composable
private fun SettingsIconForInline(
	@DrawableRes iconRes: Int,
	colors: CategoryIconColors?,
	enabled: Boolean,
) {
	if (colors != null) {
		Box(
			modifier = Modifier
				.size(44.dp)
				.background(
					color = colors.container.copy(alpha = if (enabled) 1f else 0.4f),
					shape = CircleShape,
				),
			contentAlignment = Alignment.Center,
		) {
			Image(
				painter = rememberAnyDrawablePainter(iconRes),
				contentDescription = null,
				modifier = Modifier.size(22.dp),
				colorFilter = ColorFilter.tint(
					colors.onContainer.copy(alpha = if (enabled) 1f else 0.5f),
				),
			)
		}
	} else {
		Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
			Image(
				painter = rememberAnyDrawablePainter(iconRes),
				contentDescription = null,
				modifier = Modifier.size(24.dp),
				colorFilter = ColorFilter.tint(
					MaterialTheme.colorScheme.onSurfaceVariant.copy(
						alpha = if (enabled) 1f else 0.4f,
					),
				),
			)
		}
	}
}
