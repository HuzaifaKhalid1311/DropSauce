package org.koitharu.kotatsu.core.ui.sheet

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R

/**
 * The Compose counterpart of the M3 Expressive popup toolkit in
 * [org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialog], for content hosted directly on a
 * bottom-sheet surface: no icon badge or card, sections laid out flush against the sheet.
 */

/** Horizontal inset shared by every row so labels, chips and sliders line up down the sheet. */
val SheetContentPadding = 16.dp

/**
 * Titled section: a [title] label, an optional right-aligned [value] readout (e.g. the current
 * slider position) and an optional [onMore] text action, above [content].
 */
@Composable
fun SheetSection(
	title: String,
	modifier: Modifier = Modifier,
	value: String? = null,
	moreLabel: String? = null,
	onMore: (() -> Unit)? = null,
	content: @Composable ColumnScope.() -> Unit,
) {
	Column(modifier = modifier.padding(top = 16.dp)) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = SheetContentPadding),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = title,
				style = MaterialTheme.typography.titleSmall,
				color = MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.weight(1f),
			)
			if (value != null) {
				Text(
					text = value,
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.primary,
				)
			}
			if (onMore != null && moreLabel != null) {
				TextButton(onClick = onMore, modifier = Modifier.padding(start = 4.dp)) {
					Text(text = moreLabel, style = MaterialTheme.typography.labelLarge)
				}
			}
		}
		Spacer(Modifier.size(4.dp))
		content()
	}
}

/** Full-width row toggling a boolean setting: leading [icon], [title], trailing switch. */
@Composable
fun SheetSwitchRow(
	icon: Painter?,
	title: String,
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
) {
	// Dim the icon and label together when the setting doesn't apply, matching the disabled
	// MaterialSwitch row this replaces.
	val contentColor = if (enabled) {
		MaterialTheme.colorScheme.onSurfaceVariant
	} else {
		MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
	}
	Row(
		modifier = modifier
			.fillMaxWidth()
			.heightIn(min = 56.dp)
			.clip(RoundedCornerShape(16.dp))
			.clickable(enabled = enabled) { onCheckedChange(!checked) }
			.padding(horizontal = SheetContentPadding),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (icon != null) {
			Icon(
				painter = icon,
				contentDescription = null,
				tint = contentColor,
				modifier = Modifier.size(24.dp),
			)
			Spacer(Modifier.size(16.dp))
		}
		Text(
			text = title,
			style = MaterialTheme.typography.bodyLarge,
			color = contentColor,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		Spacer(Modifier.size(8.dp))
		Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
	}
}

/**
 * Filled dropdown field replacing a `Spinner`: shows [current] and opens a menu of [items],
 * reporting the picked index to [onSelect].
 */
@Composable
fun SheetSelectorField(
	current: String,
	items: List<String>,
	onSelect: (Int) -> Unit,
	modifier: Modifier = Modifier,
) {
	var expanded by remember { mutableStateOf(false) }
	val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
	Box(modifier = modifier) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(min = 56.dp)
				.clip(RoundedCornerShape(16.dp))
				.background(MaterialTheme.colorScheme.surfaceContainerHigh)
				.clickable { expanded = true }
				.padding(horizontal = 16.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = current,
				style = MaterialTheme.typography.bodyLarge,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.weight(1f),
			)
			Icon(
				painter = painterResource(R.drawable.ic_expand_more),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier
					.padding(start = 8.dp)
					.size(20.dp)
					.rotate(chevronRotation),
			)
		}
		DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			items.forEachIndexed { index, item ->
				DropdownMenuItem(
					text = { Text(item) },
					onClick = {
						expanded = false
						onSelect(index)
					},
				)
			}
		}
	}
}

/** One entry of a [SheetChips] row. */
class SheetChip(val title: String, val isChecked: Boolean)

/** Wrapping row of filter chips; [onClick] receives the tapped chip's index. */
@Composable
fun SheetChips(
	chips: List<SheetChip>,
	onClick: (Int) -> Unit,
	modifier: Modifier = Modifier,
) {
	FlowRow(
		modifier = modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		chips.forEachIndexed { index, chip ->
			FilterChip(
				selected = chip.isChecked,
				onClick = { onClick(index) },
				label = { Text(chip.title) },
			)
		}
	}
}

/** One segment of a [SheetSegmentedSelector]. */
class SheetSegment(val label: String, val icon: Painter)

/**
 * Single-choice selector where a filled pill slides between the [options] — the control the reader's
 * read-mode picker uses, shared so the two stay identical.
 */
@Composable
fun SheetSegmentedSelector(
	options: List<SheetSegment>,
	selectedIndex: Int,
	onSelect: (Int) -> Unit,
	modifier: Modifier = Modifier,
) {
	if (options.isEmpty()) {
		return
	}
	Surface(
		shape = RoundedCornerShape(24.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		modifier = modifier.fillMaxWidth(),
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.height(112.dp)
				.padding(8.dp),
		) {
			// Bias runs -1 (first) .. 1 (last), so the pill lands centred on the chosen segment.
			val lastIndex = (options.size - 1).coerceAtLeast(1)
			val animatedBias by animateFloatAsState(
				targetValue = -1f + 2f * selectedIndex.coerceIn(0, options.lastIndex) / lastIndex,
				animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
				label = "segment_highlighter",
			)
			Box(
				modifier = Modifier
					.fillMaxHeight()
					.fillMaxWidth(1f / options.size)
					.align(BiasAlignment(horizontalBias = animatedBias, verticalBias = 0f))
					.clip(RoundedCornerShape(22.dp))
					.background(MaterialTheme.colorScheme.primary),
			)
			Row(
				modifier = Modifier.fillMaxSize(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				options.forEachIndexed { index, option ->
					val isSelected = index == selectedIndex
					val contentColor by animateColorAsState(
						targetValue = if (isSelected) {
							MaterialTheme.colorScheme.onPrimary
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
						label = "segment_fg_$index",
					)
					Box(
						modifier = Modifier
							.weight(1f)
							.fillMaxHeight()
							.clickable(
								interactionSource = remember { MutableInteractionSource() },
								indication = null,
							) { onSelect(index) },
						contentAlignment = Alignment.Center,
					) {
						Column(
							horizontalAlignment = Alignment.CenterHorizontally,
							verticalArrangement = Arrangement.Center,
						) {
							Icon(
								painter = option.icon,
								contentDescription = null,
								modifier = Modifier.size(34.dp),
								tint = contentColor,
							)
							Spacer(Modifier.height(8.dp))
							Text(
								text = option.label,
								style = MaterialTheme.typography.titleSmall,
								fontWeight = FontWeight.Medium,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
								color = contentColor,
							)
						}
					}
				}
			}
		}
	}
}
