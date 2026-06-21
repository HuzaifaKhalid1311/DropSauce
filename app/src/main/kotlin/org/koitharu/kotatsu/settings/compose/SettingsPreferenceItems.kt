package org.koitharu.kotatsu.settings.compose

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.main.ui.nav.rememberAnyDrawablePainter

/**
 * Typed settings rows that wrap the generic [SettingsItem]. Each item owns its own dialog
 * visibility state — callers just provide the current value and a setter, no boilerplate.
 */

/** Single-choice list (replaces ListPreference). */
@Composable
fun ListSettingsItem(
	title: String,
	entries: List<String>,
	entryValues: List<String>,
	selectedValue: String?,
	onValueChange: (String) -> Unit,
	modifier: Modifier = Modifier,
	@DrawableRes icon: Int? = null,
	iconColors: CategoryIconColors? = null,
	shape: Shape = MaterialTheme.shapes.medium,
	enabled: Boolean = true,
	key: String? = null,
) {
	var showDialog by remember { mutableStateOf(false) }
	val selectedIndex = entryValues.indexOf(selectedValue).coerceAtLeast(0)
	val displayValue = entries.getOrNull(selectedIndex)

	SettingsItem(
		title = title,
		modifier = modifier,
		subtitle = displayValue,
		icon = icon,
		iconColors = iconColors,
		shape = shape,
		enabled = enabled,
		onClick = { showDialog = true },
		key = key,
	)

	if (showDialog) {
		ChoiceDialog(
			title = title,
			entries = entries,
			selectedIndex = selectedIndex,
			onSelect = { idx -> onValueChange(entryValues[idx]) },
			onDismiss = { showDialog = false },
		)
	}
}

/** Multi-choice list (replaces MultiSelectListPreference). */
@Composable
fun MultiSelectSettingsItem(
	title: String,
	entries: List<String>,
	entryValues: List<String>,
	selectedValues: Set<String>,
	onValuesChange: (Set<String>) -> Unit,
	modifier: Modifier = Modifier,
	@DrawableRes icon: Int? = null,
	iconColors: CategoryIconColors? = null,
	shape: Shape = MaterialTheme.shapes.medium,
	enabled: Boolean = true,
	key: String? = null,
) {
	var showDialog by remember { mutableStateOf(false) }
	val selectedIndices = entryValues
		.mapIndexedNotNull { i, v -> if (v in selectedValues) i else null }
		.toSet()
	val summary = selectedIndices.joinToString { entries[it] }.ifEmpty { null }

	SettingsItem(
		title = title,
		modifier = modifier,
		subtitle = summary,
		icon = icon,
		iconColors = iconColors,
		shape = shape,
		enabled = enabled,
		onClick = { showDialog = true },
		key = key,
	)

	if (showDialog) {
		MultiChoiceDialog(
			title = title,
			entries = entries,
			selectedIndices = selectedIndices,
			onConfirm = { indices ->
				onValuesChange(indices.map { entryValues[it] }.toSet())
			},
			onDismiss = { showDialog = false },
		)
	}
}

/** Free-text input (replaces EditTextPreference). */
@Composable
fun EditTextSettingsItem(
	title: String,
	value: String,
	hint: String? = null,
	onValueChange: (String) -> Unit,
	modifier: Modifier = Modifier,
	@DrawableRes icon: Int? = null,
	iconColors: CategoryIconColors? = null,
	shape: Shape = MaterialTheme.shapes.medium,
	enabled: Boolean = true,
	mask: ((String) -> String)? = null,
	key: String? = null,
) {
	var showDialog by remember { mutableStateOf(false) }
	val displayValue = remember(value, mask) { mask?.invoke(value) ?: value }

	SettingsItem(
		title = title,
		modifier = modifier,
		subtitle = displayValue.ifBlank { hint },
		icon = icon,
		iconColors = iconColors,
		shape = shape,
		enabled = enabled,
		onClick = { showDialog = true },
		key = key,
	)

	if (showDialog) {
		TextInputDialog(
			title = title,
			initialValue = value,
			hint = hint,
			onConfirm = onValueChange,
			onDismiss = { showDialog = false },
		)
	}
}

/**
 * Integer slider rendered inline in the row.
 */
@Composable
fun SliderSettingsItem(
	title: String,
	value: Int,
	valueFrom: Int,
	valueTo: Int,
	stepSize: Int,
	onValueChange: (Int) -> Unit,
	modifier: Modifier = Modifier,
	unitSuffix: String = "",
	@DrawableRes icon: Int? = null,
	iconColors: CategoryIconColors? = null,
	shape: Shape = androidx.compose.material3.MaterialTheme.shapes.medium,
	enabled: Boolean = true,
) {
	InlineSliderSettingsRow(
		title = title,
		value = value,
		valueFrom = valueFrom,
		valueTo = valueTo,
		stepSize = stepSize,
		unitSuffix = unitSuffix,
		onValueChange = onValueChange,
		modifier = modifier,
		icon = icon,
		iconColors = iconColors,
		shape = shape,
		enabled = enabled,
	)
}

/** A row that navigates to a sub-screen (replaces a PreferenceScreen entry). */
@Composable
fun NavigationSettingsItem(
	title: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	subtitle: String? = null,
	@DrawableRes icon: Int? = null,
	iconColors: CategoryIconColors? = null,
	shape: Shape = MaterialTheme.shapes.medium,
	enabled: Boolean = true,
	key: String? = null,
) {
	SettingsItem(
		title = title,
		modifier = modifier,
		subtitle = subtitle,
		icon = icon,
		iconColors = iconColors,
		shape = shape,
		enabled = enabled,
		onClick = onClick,
		key = key,
	)
}

/** A plain clickable row (replaces an action-only Preference). Same as NavigationSettingsItem in
 * behavior; kept separate so screens can be more explicit about intent. */
@Composable
fun ActionSettingsItem(
	title: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	subtitle: String? = null,
	@DrawableRes icon: Int? = null,
	iconColors: CategoryIconColors? = null,
	shape: Shape = MaterialTheme.shapes.medium,
	enabled: Boolean = true,
	key: String? = null,
) {
	SettingsItem(
		title = title,
		modifier = modifier,
		subtitle = subtitle,
		icon = icon,
		iconColors = iconColors,
		shape = shape,
		enabled = enabled,
		onClick = onClick,
		key = key,
	)
}

/** A read-only info row — no click, just title/subtitle/icon. */
@Composable
fun InfoSettingsItem(
	title: String,
	modifier: Modifier = Modifier,
	subtitle: String? = null,
	@DrawableRes icon: Int? = null,
	iconColors: CategoryIconColors? = null,
	shape: Shape = MaterialTheme.shapes.medium,
	key: String? = null,
) {
	SettingsItem(
		title = title,
		modifier = modifier,
		subtitle = subtitle,
		icon = icon,
		iconColors = iconColors,
		shape = shape,
		enabled = true,
		onClick = null,
		key = key,
	)
}

/** Plain informational text that sits outside grouped option blocks. */
@Composable
fun PlainInfoSettingsItem(
	text: String,
	modifier: Modifier = Modifier,
	@DrawableRes icon: Int,
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 24.dp, vertical = 8.dp),
		verticalAlignment = Alignment.Top,
	) {
		Image(
			painter = rememberAnyDrawablePainter(icon),
			contentDescription = null,
			modifier = Modifier
				.padding(top = 1.dp)
				.size(18.dp),
			colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
		)
		Spacer(Modifier.width(12.dp))
		Text(
			text = text,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.weight(1f),
		)
	}
}
