package org.koitharu.kotatsu.settings.compose

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.koitharu.kotatsu.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect
import org.koitharu.kotatsu.main.ui.nav.rememberAnyDrawablePainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Generic row in a [SettingsGroup]. Lays out a circular tinted icon, a 1- or 2-line text
 * block, and an optional trailing slot (switch, value chip, chevron, etc.).
 *
 * Designed to be the building block for typed wrappers like [SwitchSettingsItem],
 * [ListSettingsItem], etc. — keep the bespoke logic in those wrappers and just delegate
 * the rendering here.
 */
@Composable
fun SettingsItem(
	title: String,
	modifier: Modifier = Modifier,
	subtitle: String? = null,
	@DrawableRes icon: Int? = null,
	iconColors: CategoryIconColors? = null,
	tintIcon: Boolean = true,
	shape: Shape = MaterialTheme.shapes.medium,
	enabled: Boolean = true,
	// Overrides the title and plain-icon color, e.g. colorScheme.error for destructive actions.
	accentColor: Color? = null,
	containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
	titleStyle: TextStyle = MaterialTheme.typography.titleMedium,
	onClick: (() -> Unit)? = null,
	hapticEffect: HapticEffect? = null,
	trailing: @Composable (() -> Unit)? = null,
	// Extra content right under the title/subtitle, in the text column (e.g. value pills).
	supporting: @Composable (() -> Unit)? = null,
	// Inline control shown under the title line, inside the same card (slider, segments, tiles).
	below: @Composable (() -> Unit)? = null,
) {
	val haptic = rememberHapticEffect()
	// Pressed rows round their inner corners, like a pressed button in a connected group.
	val interactionSource = remember { MutableInteractionSource() }
	val pressed by interactionSource.collectIsPressedAsState()
	val press by animateFloatAsState(
		targetValue = if (pressed) 1f else 0f,
		animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
		label = "rowPress",
	)
	// One-shot search highlight: scroll this row comfortably into view and flash its background
	// once when it is the navigation target (matched by title).
	val pendingHighlight by SettingsSearchHighlight.pendingTitle.collectAsState()
	val isHighlightTarget = pendingHighlight != null && pendingHighlight == title
	val scrollToHighlight = LocalSettingsHighlightScroll.current
	val rowWindowY = remember { mutableFloatStateOf(Float.NaN) }
	val highlight = remember { Animatable(0f) }
	LaunchedEffect(isHighlightTarget) {
		if (isHighlightTarget) {
			// Wait until this row has a real laid-out position, then scroll to it.
			val y = snapshotFlow { rowWindowY.floatValue }.first { !it.isNaN() }
			scrollToHighlight(y)
			highlight.snapTo(1f)
			delay(320)
			highlight.animateTo(0f, animationSpec = tween(durationMillis = 1100))
			SettingsSearchHighlight.consume(title)
		}
	}
	val surfaceColor = lerp(
		containerColor,
		MaterialTheme.colorScheme.primaryContainer,
		highlight.value,
	)
	// Pin the content color: an interpolated `color` makes contentColorFor() return Unspecified,
	// which would render text as plain black (broken in dark mode). Keep text legible on both
	// the resting surfaceContainer and the brief primaryContainer flash.
	val contentColor = lerp(
		MaterialTheme.colorScheme.onSurface,
		MaterialTheme.colorScheme.onPrimaryContainer,
		highlight.value,
	)
	Surface(
		modifier = modifier.onGloballyPositioned { rowWindowY.floatValue = it.positionInWindow().y },
		shape = if (shape is CornerBasedShape && onClick != null) {
			PressMorphShape(shape, press.coerceIn(0f, 1f))
		} else {
			shape
		},
		color = surfaceColor,
		contentColor = contentColor,
	) {
		Column(
			modifier = if (onClick != null && enabled) {
				Modifier.clickable(interactionSource = interactionSource, indication = LocalIndication.current) {
					if (hapticEffect != null) haptic(hapticEffect)
					onClick()
				}
			} else {
				Modifier
			},
		) {
		Row(
			modifier = Modifier
				.heightIn(min = if (below == null) 72.dp else 0.dp)
				.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = if (below == null) 10.dp else 8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (icon != null) {
				if (iconColors != null) {
					SettingsIconBubble(
						iconRes = icon,
						colors = iconColors,
						enabled = enabled,
						tintIcon = tintIcon,
					)
				} else {
					SettingsIconPlain(
						iconRes = icon,
						enabled = enabled,
						tintOverride = accentColor,
						tintIcon = tintIcon,
					)
				}
				Spacer(Modifier.width(14.dp))
			}
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = title,
					style = titleStyle,
					color = accentColor?.copy(alpha = if (enabled) 1f else 0.38f) ?: textColor(enabled),
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
				if (!subtitle.isNullOrBlank()) {
					Text(
						text = subtitle,
						style = MaterialTheme.typography.bodySmall,
						color = accentColor?.copy(alpha = if (enabled) 0.8f else 0.38f) ?: secondaryTextColor(enabled),
					)
				}
				if (supporting != null) {
					Spacer(Modifier.height(6.dp))
					supporting()
				}
			}
			if (trailing != null) {
				Spacer(Modifier.width(8.dp))
				trailing()
			}
		}
		if (below != null) {
			Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) { below() }
		}
		}
	}
}

/** [base] with its tighter corners eased up to 16dp by [fraction] — the pressed-row morph. */
private data class PressMorphShape(val base: CornerBasedShape, val fraction: Float) : Shape {
	override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
		val target = with(density) { 16.dp.toPx() }
		fun morph(corner: CornerSize): Float {
			val px = corner.toPx(size, density)
			return px + (maxOf(px, target) - px) * fraction
		}
		return RoundedCornerShape(
			topStart = morph(base.topStart),
			topEnd = morph(base.topEnd),
			bottomEnd = morph(base.bottomEnd),
			bottomStart = morph(base.bottomStart),
		).createOutline(size, layoutDirection, density)
	}
}

/**
 * Convenience for a SwitchPreference — bool state + listener.
 *
 * [main] is for the one switch that turns a whole screen's feature on or off: a taller pill that
 * fills with the accent colour while on. Give it its own [SettingsGroup].
 */
@Composable
fun SwitchSettingsItem(
	title: String,
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
	subtitle: String? = null,
	@DrawableRes icon: Int? = null,
	iconColors: CategoryIconColors? = null,
	shape: Shape = MaterialTheme.shapes.medium,
	enabled: Boolean = true,
	main: Boolean = false,
) {
	val haptic = rememberHapticEffect()
	val onCheckedChangeHaptic: (Boolean) -> Unit = { value ->
		haptic(if (value) HapticEffect.TOGGLE_ON else HapticEffect.TOGGLE_OFF)
		onCheckedChange(value)
	}
	val cs = MaterialTheme.colorScheme
	val mainOn = main && checked
	val container by animateColorAsState(
		targetValue = when {
			mainOn -> cs.primaryContainer
			main -> cs.surfaceContainerHighest
			else -> cs.surfaceContainer
		},
		animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
		label = "switchContainer",
	)
	val mainContent by animateColorAsState(
		targetValue = if (mainOn) cs.onPrimaryContainer else cs.onSurface,
		animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
		label = "switchContent",
	)
	SettingsItem(
		title = title,
		modifier = modifier,
		subtitle = subtitle,
		icon = icon,
		iconColors = iconColors,
		shape = if (main) RoundedCornerShape(36.dp) else shape,
		enabled = enabled,
		accentColor = if (main) mainContent else null,		containerColor = container,
		titleStyle = if (main) MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium) else MaterialTheme.typography.titleMedium,
		// The toggle haptic is fired by [onCheckedChangeHaptic] so a row tap and a thumb tap
		// feel identical — suppress the generic click effect to avoid a double buzz.
		hapticEffect = null,
		onClick = if (enabled) {
			{ onCheckedChangeHaptic(!checked) }
		} else null,
		trailing = {
			SettingsSwitch(checked = checked, onCheckedChange = onCheckedChangeHaptic, enabled = enabled)
		},
	)
}

/** The settings switch: a tick in the thumb while on, a cross while off. Use it for every toggle. */
@Composable
fun SettingsSwitch(
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
	enabled: Boolean = true,
) {
	Switch(
		checked = checked,
		onCheckedChange = onCheckedChange,
		enabled = enabled,
		thumbContent = {
			Icon(
				painter = painterResource(if (checked) R.drawable.ic_check else R.drawable.ic_close),
				contentDescription = null,
				modifier = Modifier.size(SwitchDefaults.IconSize),
			)
		},
	)
}

@Composable
private fun SettingsIconBubble(
	@DrawableRes iconRes: Int,
	colors: CategoryIconColors,
	enabled: Boolean,
	tintIcon: Boolean = true,
) {
	val containerAlpha = if (enabled) 1f else 0.4f
	val contentAlpha = if (enabled) 1f else 0.5f
	// A multicolor logo (e.g. the Google "G") must not be tinted; show it on a white bubble.
	val containerColor = if (tintIcon) colors.container.copy(alpha = containerAlpha) else Color.White
	Box(
		modifier = Modifier
			.size(44.dp)
			.clip(CircleShape)
			.background(containerColor),
		contentAlignment = Alignment.Center,
	) {
		androidx.compose.foundation.Image(
			painter = rememberAnyDrawablePainter(iconRes),
			contentDescription = null,
			modifier = Modifier.size(if (tintIcon) 22.dp else 24.dp),
			colorFilter = if (tintIcon) ColorFilter.tint(colors.onContainer.copy(alpha = contentAlpha)) else null,
		)
	}
}

@Composable
private fun SettingsIconPlain(
	@DrawableRes iconRes: Int,
	enabled: Boolean,
	tintOverride: Color? = null,
	tintIcon: Boolean = true,
) {
	val tint = (tintOverride ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = if (enabled) 1f else 0.4f)
	Box(
		modifier = Modifier.size(44.dp),
		contentAlignment = Alignment.Center,
	) {
		androidx.compose.foundation.Image(
			painter = rememberAnyDrawablePainter(iconRes),
			contentDescription = null,
			modifier = Modifier.size(24.dp),
			// a bitmap logo would collapse to a flat silhouette if tinted
			colorFilter = if (tintIcon) ColorFilter.tint(tint) else null,
			alpha = if (enabled) 1f else 0.4f,
		)
	}
}

@Composable
private fun textColor(enabled: Boolean): Color {
	val base = LocalContentColor.current
	return if (enabled) base else base.copy(alpha = 0.38f)
}

@Composable
private fun secondaryTextColor(enabled: Boolean): Color {
	val base = MaterialTheme.colorScheme.onSurfaceVariant
	return if (enabled) base else base.copy(alpha = 0.38f)
}
