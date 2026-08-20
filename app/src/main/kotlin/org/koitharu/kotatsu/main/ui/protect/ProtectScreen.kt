package org.koitharu.kotatsu.main.ui.protect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R

private const val MIN_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 24

/**
 * Full-screen app-unlock UI in the app's M3 Expressive style. In PIN mode it shows a numeric field;
 * in device/biometric mode it shows a prompt with an "Unlock" action (the system prompt is launched
 * automatically by the hosting activity).
 */
@Composable
fun ProtectScreen(
	isPinMode: Boolean,
	onVerifyPin: (String) -> Boolean,
	onBiometric: () -> Unit,
	onCancel: () -> Unit,
) {
	var pin by remember { mutableStateOf("") }
	var isError by remember { mutableStateOf(false) }
	val focusRequester = remember { FocusRequester() }
	val keyboardController = LocalSoftwareKeyboardController.current

	// PIN mode: put the caret in the field and raise the numpad right away, so unlocking is one action.
	LaunchedEffect(isPinMode) {
		if (isPinMode) {
			focusRequester.requestFocus()
			keyboardController?.show()
		}
	}

	fun submit() {
		if (pin.length < MIN_PIN_LENGTH) return
		if (!onVerifyPin(pin)) {
			isError = true
		}
	}

	Column(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colorScheme.background)
			.systemBarsPadding()
			.imePadding()
			.padding(horizontal = 24.dp, vertical = 48.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.SpaceBetween,
	) {
		// Top: identity + PIN entry.
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.widthIn(max = 400.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Box(
				modifier = Modifier
					.size(96.dp)
					.background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					painter = painterResource(R.drawable.ic_lock),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSecondaryContainer,
					modifier = Modifier.size(44.dp),
				)
			}
			Spacer(Modifier.height(24.dp))
			Text(
				text = stringResource(R.string.app_name),
				style = MaterialTheme.typography.headlineMedium,
				color = MaterialTheme.colorScheme.onSurface,
				textAlign = TextAlign.Center,
			)
			Spacer(Modifier.height(8.dp))
			Text(
				text = stringResource(if (isPinMode) R.string.enter_pin else R.string.require_unlock),
				style = MaterialTheme.typography.bodyLarge,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				textAlign = TextAlign.Center,
			)
			if (isPinMode) {
				Spacer(Modifier.height(32.dp))
				OutlinedTextField(
					value = pin,
					onValueChange = { new ->
						isError = false
						pin = new.filter(Char::isDigit).take(MAX_PIN_LENGTH)
					},
					singleLine = true,
					isError = isError,
					supportingText = if (isError) {
						{ Text(stringResource(R.string.wrong_pin)) }
					} else {
						null
					},
					visualTransformation = PasswordVisualTransformation(),
					keyboardOptions = KeyboardOptions(
						keyboardType = KeyboardType.NumberPassword,
						imeAction = ImeAction.Done,
					),
					keyboardActions = KeyboardActions(onDone = { submit() }),
					shape = RoundedCornerShape(16.dp),
					modifier = Modifier
						.fillMaxWidth()
						.focusRequester(focusRequester),
				)
			}
		}
		// Bottom: actions.
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.widthIn(max = 400.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Button(
				onClick = { if (isPinMode) submit() else onBiometric() },
				enabled = !isPinMode || pin.length >= MIN_PIN_LENGTH,
				shape = RoundedCornerShape(28.dp),
				modifier = Modifier
					.fillMaxWidth()
					.height(56.dp),
			) {
				Text(
					text = stringResource(R.string.unlock_app),
					style = MaterialTheme.typography.labelLarge,
				)
			}
			Spacer(Modifier.height(8.dp))
			TextButton(
				onClick = onCancel,
				modifier = Modifier.fillMaxWidth(),
			) {
				Text(text = stringResource(android.R.string.cancel))
			}
		}
	}
}
