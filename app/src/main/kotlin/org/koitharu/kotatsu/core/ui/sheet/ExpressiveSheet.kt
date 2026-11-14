package org.koitharu.kotatsu.core.ui.sheet

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.lerp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect
import org.koitharu.kotatsu.settings.compose.LocalSettingsItemColor

/**
 * The Compose counterpart of the M3 Expressive popup toolkit in
 * [org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialog], for content hosted directly on a
 * bottom-sheet surface. Sheets made of options use the settings rows inside [SheetRows]; the
 * sections, chips and dropdown here are for the ones laid out flush against the sheet.
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

/** One entry of a [SheetChips] row; [count] trails the title, muted, like the list quick-filter chips. */
class SheetChip(val title: String, val isChecked: Boolean, val icon: Painter? = null, val count: String? = null)

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
				label = {
					Text(
						text = if (chip.count == null) {
							AnnotatedString(chip.title)
						} else {
							buildAnnotatedString {
								append(chip.title)
								append("  ")
								withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 0.74.em)) {
									append(chip.count)
								}
							}
						},
					)
				},
				leadingIcon = chip.icon?.let { icon ->
					{
						Icon(
							painter = icon,
							contentDescription = null,
							modifier = Modifier.size(18.dp),
						)
					}
				},
			)
		}
	}
}

/**
 * Hosts settings rows on a sheet. They sit one tone above the sheet's own tinted surface, so the
 * cards still read as cards.
 */
@Composable
fun SheetRows(content: @Composable () -> Unit) {
	CompositionLocalProvider(
		LocalSettingsItemColor provides MaterialTheme.colorScheme.surfaceContainerHigh,
		content = content,
	)
}

/** One tile of a [SheetActionGrid]; [checked] fills it with the accent colour, for on/off actions. */
class SheetAction(
	@DrawableRes val icon: Int,
	val label: String,
	val checked: Boolean = false,
	val onClick: () -> Unit,
)

/**
 * Actions as one block of connected tiles, [columns] to a row: 24dp corners on the outside of the
 * block and 4dp seams inside it — the settings group look, in two directions.
 *
 * [attachedBelow] keeps the bottom edge tight so a settings row can carry the block on.
 */
@Composable
fun SheetActionGrid(
	actions: List<SheetAction>,
	modifier: Modifier = Modifier,
	columns: Int = 3,
	attachedBelow: Boolean = false,
) {
	val rows = actions.chunked(columns)
	Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
		rows.forEachIndexed { rowIndex, row ->
			val isTop = rowIndex == 0
			val isBottom = rowIndex == rows.lastIndex && !attachedBelow
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.height(IntrinsicSize.Max),
				horizontalArrangement = Arrangement.spacedBy(2.dp),
			) {
				row.forEachIndexed { index, action ->
					val isStart = index == 0
					val isEnd = index == row.lastIndex
					ActionTile(
						action = action,
						topStart = if (isTop && isStart) 24.dp else 4.dp,
						topEnd = if (isTop && isEnd) 24.dp else 4.dp,
						bottomEnd = if (isBottom && isEnd) 24.dp else 4.dp,
						bottomStart = if (isBottom && isStart) 24.dp else 4.dp,
						modifier = Modifier
							.weight(1f)
							.fillMaxHeight(),
					)
				}
			}
		}
	}
}

@Composable
private fun ActionTile(
	action: SheetAction,
	topStart: Dp,
	topEnd: Dp,
	bottomEnd: Dp,
	bottomStart: Dp,
	modifier: Modifier = Modifier,
) {
	val cs = MaterialTheme.colorScheme
	val motion = MaterialTheme.motionScheme
	val haptic = rememberHapticEffect()
	val interactionSource = remember { MutableInteractionSource() }
	val pressed by interactionSource.collectIsPressedAsState()
	// Pressed or switched on, a tile rounds itself off and stands out of the block.
	val round by animateFloatAsState(
		targetValue = if (pressed || action.checked) 1f else 0f,
		animationSpec = motion.fastSpatialSpec(),
		label = "tileRound",
	)
	val container by animateColorAsState(
		targetValue = if (action.checked) cs.primary else LocalSettingsItemColor.current ?: cs.surfaceContainer,
		animationSpec = motion.fastEffectsSpec(),
		label = "tileContainer",
	)
	val content by animateColorAsState(
		targetValue = if (action.checked) cs.onPrimary else cs.onSurface,
		animationSpec = motion.fastEffectsSpec(),
		label = "tileContent",
	)
	val iconTint by animateColorAsState(
		targetValue = if (action.checked) cs.onPrimary else cs.primary,
		animationSpec = motion.fastEffectsSpec(),
		label = "tileIcon",
	)
	// The spring overshoots; a corner can't go below zero.
	val fraction = round.coerceAtLeast(0f)
	Surface(
		onClick = {
			haptic(HapticEffect.CLICK)
			action.onClick()
		},
		modifier = modifier.heightIn(min = 92.dp),
		shape = RoundedCornerShape(
			topStart = lerp(topStart, TileRoundCorner, fraction),
			topEnd = lerp(topEnd, TileRoundCorner, fraction),
			bottomEnd = lerp(bottomEnd, TileRoundCorner, fraction),
			bottomStart = lerp(bottomStart, TileRoundCorner, fraction),
		),
		color = container,
		contentColor = content,
		interactionSource = interactionSource,
	) {
		Column(
			modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.Center,
		) {
			Icon(
				painter = painterResource(action.icon),
				contentDescription = null,
				tint = iconTint,
				modifier = Modifier.size(26.dp),
			)
			Spacer(Modifier.height(6.dp))
			Text(
				text = action.label,
				style = MaterialTheme.typography.labelMedium,
				textAlign = TextAlign.Center,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

private val TileRoundCorner = 32.dp
