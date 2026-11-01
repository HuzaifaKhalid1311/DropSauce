package org.koitharu.kotatsu.settings.protect

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.dialog.DialogButtonSpec
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerAction
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveButtonGrid
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressivePillButton
import org.koitharu.kotatsu.core.ui.dialog.showComposeDialog

private const val MIN_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 24

/**
 * M3 Expressive popup shown when the user turns on "Require unlock": pick device/biometric auth
 * (recommended) or set up a custom PIN. PIN setup happens inline in two steps (enter -> confirm).
 */
fun showProtectMethodDialog(
	activity: FragmentActivity,
	deviceAuthSupported: Boolean,
	onSelectDevice: () -> Unit,
	onPinConfirmed: (String) -> Unit,
) {
	showComposeDialog(activity) { dismiss ->
		ProtectMethodContent(
			deviceAuthSupported = deviceAuthSupported,
			onSelectDevice = {
				dismiss()
				onSelectDevice()
			},
			onPinConfirmed = { pin ->
				dismiss()
				onPinConfirmed(pin)
			},
			onCancel = dismiss,
		)
	}
}

@Composable
private fun ProtectMethodContent(
	deviceAuthSupported: Boolean,
	onSelectDevice: () -> Unit,
	onPinConfirmed: (String) -> Unit,
	onCancel: () -> Unit,
) {
	var showPinSetup by remember { mutableStateOf(false) }
	if (showPinSetup) {
		PinSetupCard(onPinConfirmed = onPinConfirmed, onCancel = onCancel)
	} else {
		ExpressiveDialogCard(
			icon = painterResource(R.drawable.ic_lock),
			title = stringResource(R.string.lock_method_title),
			message = if (deviceAuthSupported) {
				stringResource(R.string.lock_method_message)
			} else {
				stringResource(R.string.require_unlock_unavailable)
			},
			cornerAction = DialogCornerAction(
				icon = painterResource(R.drawable.ic_close),
				label = stringResource(android.R.string.cancel),
				onClick = onCancel,
			),
			footer = {
				ExpressiveButtonGrid(
					buttons = listOf(
						DialogButtonSpec(
							label = stringResource(R.string.lock_method_device),
							primary = true,
							enabled = deviceAuthSupported,
							onClick = onSelectDevice,
						),
						DialogButtonSpec(
							label = stringResource(R.string.lock_method_pin),
							primary = false,
							onClick = { showPinSetup = true },
						),
					),
				)
			},
		)
	}
}

/** Self-contained two-step PIN setup: enter, then confirm; mismatch restarts from entry. */
@Composable
private fun PinSetupCard(
	onPinConfirmed: (String) -> Unit,
	onCancel: () -> Unit,
) {
	var firstPin by remember { mutableStateOf<String?>(null) }
	var pin by remember { mutableStateOf("") }
	var mismatch by remember { mutableStateOf(false) }
	val isConfirmStep = firstPin != null
	val canSubmit = pin.length >= MIN_PIN_LENGTH

	fun submit() {
		if (!canSubmit) return
		val first = firstPin
		if (first == null) {
			firstPin = pin
			pin = ""
		} else if (pin == first) {
			onPinConfirmed(pin)
		} else {
			mismatch = true
			firstPin = null
			pin = ""
		}
	}

	ExpressiveDialogCard(
		icon = painterResource(R.drawable.ic_lock),
		title = stringResource(if (isConfirmStep) R.string.confirm_pin else R.string.create_pin),
		cornerAction = DialogCornerAction(
			icon = painterResource(R.drawable.ic_close),
			label = stringResource(android.R.string.cancel),
			onClick = onCancel,
		),
		footer = {
			ExpressivePillButton(
				text = stringResource(if (isConfirmStep) R.string.confirm else R.string.next),
				primary = true,
				enabled = canSubmit,
				onClick = ::submit,
			)
		},
	) {
		Text(
			text = when {
				mismatch -> stringResource(R.string.pins_mismatch)
				isConfirmStep -> stringResource(R.string.confirm_pin_hint)
				else -> stringResource(R.string.create_pin_hint)
			},
			style = MaterialTheme.typography.bodyMedium,
			color = if (mismatch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Spacer(Modifier.height(16.dp))
		OutlinedTextField(
			value = pin,
			onValueChange = { new ->
				mismatch = false
				pin = new.filter(Char::isDigit).take(MAX_PIN_LENGTH)
			},
			singleLine = true,
			visualTransformation = PasswordVisualTransformation(),
			keyboardOptions = KeyboardOptions(
				keyboardType = KeyboardType.NumberPassword,
				imeAction = ImeAction.Done,
			),
			keyboardActions = KeyboardActions(onDone = { submit() }),
			shape = RoundedCornerShape(16.dp),
			modifier = Modifier.fillMaxWidth(),
		)
	}
}
