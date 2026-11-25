package org.koitharu.kotatsu.settings.compose

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect

/**
 * Settings row whose value is picked from a short, fixed set of options shown as an M3 Expressive
 * connected button group underneath the title — for choices that are too small to deserve a dialog
 * but need their whole range visible at a glance.
 *
 * @param labels one label per option; [selectedIndex] indexes into it.
 */
@Composable
fun SegmentedSettingsItem(
	title: String,
	labels: List<String>,
	selectedIndex: Int,
	onSelected: (Int) -> Unit,
	modifier: Modifier = Modifier,
	subtitle: String? = null,
	@DrawableRes icon: Int? = null,
	shape: Shape = MaterialTheme.shapes.medium,
	enabled: Boolean = true,
) {
	SettingsItem(
		title = title,
		modifier = modifier,
		subtitle = subtitle,
		icon = icon,
		shape = shape,
		enabled = enabled,
		below = {
			SegmentedRow(
				labels = labels,
				selectedIndex = selectedIndex,
				onSelected = onSelected,
				enabled = enabled,
			)
		},
	)
}

/**
 * M3 Expressive connected button group: tight seams, and the picked option morphs into a full pill
 * with a checkmark.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SegmentedRow(
	labels: List<String>,
	selectedIndex: Int,
	onSelected: (Int) -> Unit,
	enabled: Boolean,
) {
	val haptic = rememberHapticEffect()
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
	) {
		labels.forEachIndexed { index, label ->
			val isSelected = index == selectedIndex
			ToggleButton(
				checked = isSelected,
				onCheckedChange = {
					haptic(HapticEffect.TOGGLE_ON)
					onSelected(index)
				},
				enabled = enabled,
				modifier = Modifier
					.weight(1f)
					.heightIn(min = 48.dp),
				shapes = when (index) {
					0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
					labels.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
					else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
				},
				// The row sits on a surfaceContainer card, so the default container would vanish into it.
				colors = ToggleButtonDefaults.colors(
					containerColor = MaterialTheme.colorScheme.surfaceVariant,
					contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
				),
				contentPadding = PaddingValues(horizontal = 8.dp),
			) {
				AnimatedVisibility(
					visible = isSelected,
					enter = fadeIn() + expandHorizontally(),
					exit = fadeOut() + shrinkHorizontally(),
				) {
					Icon(
						painter = painterResource(R.drawable.ic_check),
						contentDescription = null,
						modifier = Modifier
							.padding(end = 6.dp)
							.size(ToggleButtonDefaults.IconSize),
					)
				}
				Text(
					text = label,
					style = MaterialTheme.typography.labelLarge,
					fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
					textAlign = TextAlign.Center,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}
