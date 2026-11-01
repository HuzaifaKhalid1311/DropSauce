package org.koitharu.kotatsu.reader.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.dialog.DialogButtonSpec
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerAction
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveButtonGrid
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveOptionGroupItem
import org.koitharu.kotatsu.core.ui.dialog.showComposeDialog

/**
 * M3 Expressive replacement for the reader's "incognito mode" prompt. Choose to read in incognito,
 * disable it, or cancel; an optional "don't ask again" persists the choice. Cancelling (button, back
 * or outside tap) runs [onCancel] — the reader closes, matching the old dialog.
 */
fun showIncognitoModeDialog(
	activity: FragmentActivity,
	onResult: (incognito: Boolean, dontAskAgain: Boolean) -> Unit,
	onCancel: () -> Unit,
) {
	showComposeDialog(activity, cancelable = true, onCancel = onCancel) { dismiss ->
		IncognitoContent(
			onResult = { incognito, dontAskAgain ->
				dismiss()
				onResult(incognito, dontAskAgain)
			},
			onCancel = {
				dismiss()
				onCancel()
			},
		)
	}
}

@Composable
private fun IncognitoContent(
	onResult: (incognito: Boolean, dontAskAgain: Boolean) -> Unit,
	onCancel: () -> Unit,
) {
	var dontAskAgain by remember { mutableStateOf(false) }
	val colors = MaterialTheme.colorScheme
	ExpressiveDialogCard(
		icon = painterResource(R.drawable.ic_incognito),
		title = stringResource(R.string.incognito_mode),
		message = stringResource(R.string.incognito_mode_hint_nsfw),
		cornerAction = DialogCornerAction(
			icon = painterResource(R.drawable.ic_close),
			label = stringResource(android.R.string.cancel),
			onClick = onCancel,
		),
		footer = {
			ExpressiveButtonGrid(
				buttons = listOf(
					DialogButtonSpec(
						label = stringResource(R.string.incognito),
						icon = painterResource(R.drawable.ic_incognito),
						primary = true,
						onClick = { onResult(true, dontAskAgain) },
					),
					DialogButtonSpec(
						label = stringResource(R.string.disable),
						primary = false,
						onClick = { onResult(false, dontAskAgain) },
					),
				),
			)
		},
	) {
		ExpressiveOptionGroupItem(
			index = 0,
			count = 1,
			onClick = { dontAskAgain = !dontAskAgain },
		) {
			Checkbox(checked = dontAskAgain, onCheckedChange = null)
			Spacer(Modifier.width(12.dp))
			Text(
				text = stringResource(R.string.dont_ask_again),
				style = MaterialTheme.typography.bodyLarge,
				fontWeight = if (dontAskAgain) FontWeight.Medium else FontWeight.Normal,
				color = if (dontAskAgain) colors.primary else colors.onSurface,
				modifier = Modifier.weight(1f),
			)
		}
	}
}
