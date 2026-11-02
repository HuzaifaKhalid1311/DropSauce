package org.koitharu.kotatsu.settings.compose

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerAction
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveOptionGroupItem
import org.koitharu.kotatsu.core.ui.dialog.ExpressivePillButton
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect

/**
 * M3 Expressive single-choice picker popup (replaces ListPreference's built-in dialog).
 * Tapping an entry selects it immediately and dismisses.
 */
@Composable
fun ChoiceDialog(
	title: String,
	entries: List<String>,
	selectedIndex: Int,
	onSelect: (Int) -> Unit,
	onDismiss: () -> Unit,
	@DrawableRes icon: Int? = null,
) {
	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(usePlatformDefaultWidth = false),
	) {
		ChoiceDialogCard(
			title = title,
			entries = entries,
			selectedIndex = selectedIndex,
			onSelect = onSelect,
			onDismiss = onDismiss,
			icon = icon,
		)
	}
}

@Composable
fun ChoiceDialogCard(
	title: String,
	entries: List<String>,
	selectedIndex: Int,
	onSelect: (Int) -> Unit,
	onDismiss: () -> Unit,
	@DrawableRes icon: Int? = null,
) {
	val haptic = rememberHapticEffect()
	val colors = MaterialTheme.colorScheme
	val select: (Int) -> Unit = { index ->
		haptic(HapticEffect.CONFIRM)
		onSelect(index)
		onDismiss()
	}
	ExpressiveDialogCard(
		icon = icon?.let { painterResource(it) },
		title = title,
		cornerAction = DialogCornerAction(
			icon = painterResource(R.drawable.ic_close),
			label = stringResource(android.R.string.cancel),
			onClick = onDismiss,
		),
	) {
		entries.forEachIndexed { index, entry ->
			val isSelected = index == selectedIndex
			ExpressiveOptionGroupItem(
				index = index,
				count = entries.size,
				onClick = { select(index) },
			) {
				RadioButton(selected = isSelected, onClick = null)
				Spacer(Modifier.width(12.dp))
				Text(
					text = entry,
					style = MaterialTheme.typography.bodyLarge,
					fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
					color = if (isSelected) colors.primary else colors.onSurface,
					modifier = Modifier.weight(1f),
				)
			}
		}
	}
}

/**
 * M3 Expressive multi-choice picker popup (replaces MultiSelectListPreference's dialog).
 */
@Composable
fun MultiChoiceDialog(
	title: String,
	entries: List<String>,
	selectedIndices: Set<Int>,
	onConfirm: (Set<Int>) -> Unit,
	onDismiss: () -> Unit,
	@DrawableRes icon: Int? = null,
) {
	var current by remember { mutableStateOf(selectedIndices) }
	val haptic = rememberHapticEffect()
	val colors = MaterialTheme.colorScheme
	val toggle: (Int, Boolean) -> Unit = { index, checked ->
		haptic(if (checked) HapticEffect.TOGGLE_OFF else HapticEffect.TOGGLE_ON)
		current = if (checked) current - index else current + index
	}
	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(usePlatformDefaultWidth = false),
	) {
		ExpressiveDialogCard(
			icon = icon?.let { painterResource(it) },
			title = title,
			cornerAction = DialogCornerAction(
				icon = painterResource(R.drawable.ic_check),
				label = stringResource(android.R.string.ok),
				onClick = {
					onConfirm(current)
					onDismiss()
				},
			),
		) {
			entries.forEachIndexed { index, entry ->
				val checked = index in current
				ExpressiveOptionGroupItem(
					index = index,
					count = entries.size,
					onClick = { toggle(index, checked) },
				) {
					Checkbox(checked = checked, onCheckedChange = null)
					Spacer(Modifier.width(12.dp))
					Text(
						text = entry,
						style = MaterialTheme.typography.bodyLarge,
						fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal,
						color = if (checked) colors.primary else colors.onSurface,
						modifier = Modifier.weight(1f),
					)
				}
			}
		}
	}
}

/**
 * M3 Expressive text input popup (replaces EditTextPreference's dialog). The tick stays off while
 * [isValid] rejects the text; [isPassword] hides it.
 */
@Composable
fun TextInputDialog(
	title: String,
	initialValue: String,
	hint: String? = null,
	onConfirm: (String) -> Unit,
	onDismiss: () -> Unit,
	@DrawableRes icon: Int? = null,
	message: String? = null,
	keyboardType: KeyboardType = KeyboardType.Text,
	isPassword: Boolean = false,
	isValid: (String) -> Boolean = { true },
) {
	var value by remember { mutableStateOf(initialValue) }
	val submit = {
		if (isValid(value)) {
			onConfirm(value)
			onDismiss()
		}
	}
	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(usePlatformDefaultWidth = false),
	) {
		ExpressiveDialogCard(
			icon = icon?.let { painterResource(it) },
			title = title,
			message = message,
			cornerAction = DialogCornerAction(
				icon = painterResource(R.drawable.ic_check),
				label = stringResource(android.R.string.ok),
				enabled = isValid(value),
				onClick = submit,
			),
		) {
			OutlinedTextField(
				value = value,
				onValueChange = { value = it },
				placeholder = if (hint != null) {
					{ Text(hint) }
				} else {
					null
				},
				singleLine = true,
				visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
				keyboardOptions = KeyboardOptions(
					keyboardType = if (isPassword) KeyboardType.Password else keyboardType,
					imeAction = ImeAction.Done,
				),
				keyboardActions = KeyboardActions(onDone = { submit() }),
				shape = RoundedCornerShape(16.dp),
				modifier = Modifier.fillMaxWidth(),
			)
		}
	}
}

/**
 * M3 Expressive confirm / info popup.
 */
@Composable
fun ConfirmDialog(
	title: String,
	message: String,
	confirmLabel: String = stringResource(android.R.string.ok),
	dismissLabel: String? = stringResource(android.R.string.cancel),
	@DrawableRes icon: Int? = null,
	destructive: Boolean = false,
	onConfirm: () -> Unit,
	onDismiss: () -> Unit,
) {
	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(usePlatformDefaultWidth = false),
	) {
		val resolvedDismiss = dismissLabel ?: stringResource(R.string.close)
		ExpressiveDialogCard(
			icon = icon?.let { painterResource(it) },
			title = title,
			message = message,
			destructive = destructive,
			cornerAction = DialogCornerAction(
				icon = painterResource(R.drawable.ic_close),
				label = resolvedDismiss,
				onClick = onDismiss,
			),
			footer = {
				ExpressivePillButton(
					text = confirmLabel,
					primary = true,
					destructive = destructive,
					onClick = {
						onConfirm()
						onDismiss()
					},
				)
			},
		)
	}
}
