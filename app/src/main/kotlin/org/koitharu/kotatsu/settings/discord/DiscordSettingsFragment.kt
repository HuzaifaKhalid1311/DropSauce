package org.koitharu.kotatsu.settings.discord

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.StateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerAction
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.scrobbling.discord.ui.DiscordAuthActivity
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref

@AndroidEntryPoint
class DiscordSettingsFragment : BaseComposeSettingsFragment(R.string.discord) {

	private val viewModel by viewModels<DiscordSettingsViewModel>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				DiscordScreen(
					tokenState = viewModel.tokenState,
					onSignIn = ::openSignIn,
				)
			}
		}
	}

	private fun openSignIn() {
		startActivity(Intent(requireContext(), DiscordAuthActivity::class.java))
	}
}

@Composable
private fun DiscordScreen(
	tokenState: StateFlow<Pair<TokenState, String?>>,
	onSignIn: () -> Unit,
) {
	val ctx = LocalContext.current
	val tokenPair by tokenState.collectAsState()
	val state = tokenPair.first
	val stateToken = tokenPair.second

	var rpcEnabled by rememberBooleanPref(AppSettings.KEY_DISCORD_RPC, false)
	var skipNsfw by rememberBooleanPref(AppSettings.KEY_DISCORD_RPC_SKIP_NSFW, false)
	var token by rememberStringPref(AppSettings.KEY_DISCORD_TOKEN, "")

	var showTokenDialog by remember { mutableStateOf(false) }

	val isWarning = state == TokenState.REQUIRED || state == TokenState.INVALID
	val tokenSubtitle = when (state) {
		TokenState.EMPTY, TokenState.REQUIRED -> stringResource(R.string.discord_token_summary)
		TokenState.INVALID -> stringResource(R.string.invalid_token, stateToken ?: "")
		TokenState.VALID -> stateToken
		TokenState.CHECKING -> stringResource(R.string.loading_)
	}

	SettingsScaffold {
		item {
			SettingsGroup {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.discord_rpc),
						subtitle = stringResource(R.string.discord_rpc_summary),
						checked = rpcEnabled,
						onCheckedChange = { rpcEnabled = it },
						icon = R.drawable.ic_discord,
						shape = pos.shape,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.discord_token),
						subtitle = tokenSubtitle,
						icon = if (isWarning) R.drawable.ic_alert_outline else R.drawable.ic_lock,
						shape = pos.shape,
						enabled = rpcEnabled,
						onClick = { showTokenDialog = true },
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.disable_nsfw),
						subtitle = stringResource(R.string.rpc_skip_nsfw_summary),
						checked = skipNsfw,
						onCheckedChange = { skipNsfw = it },
						icon = R.drawable.ic_nsfw,
						shape = pos.shape,
						enabled = rpcEnabled,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}

	if (showTokenDialog) {
		DiscordTokenDialog(
			initialValue = token,
			message = ctx.getString(
				R.string.discord_token_description,
				ctx.getString(R.string.sign_in),
			),
			onConfirm = { token = it },
			onSignIn = {
				showTokenDialog = false
				onSignIn()
			},
			onDismiss = { showTokenDialog = false },
		)
	}
}

@Composable
private fun DiscordTokenDialog(
	initialValue: String,
	message: String,
	onConfirm: (String) -> Unit,
	onSignIn: () -> Unit,
	onDismiss: () -> Unit,
) {
	var value by remember { mutableStateOf(initialValue) }
	val submit = {
		onConfirm(value)
		onDismiss()
	}
	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(usePlatformDefaultWidth = false),
	) {
		val signIn = stringResource(R.string.sign_in)
		val linkStart = message.indexOf(signIn)
		ExpressiveDialogCard(
			icon = painterResource(R.drawable.ic_discord),
			title = stringResource(R.string.discord_token),
			cornerAction = DialogCornerAction(
				icon = painterResource(R.drawable.ic_check),
				label = stringResource(android.R.string.ok),
				onClick = submit,
			),
		) {
			Text(
				text = if (linkStart < 0) AnnotatedString(message) else buildAnnotatedString {
					append(message.substring(0, linkStart))
					withLink(
						LinkAnnotation.Clickable(
							tag = "sign_in",
							styles = TextLinkStyles(
								style = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium),
							),
							linkInteractionListener = { onSignIn() },
						),
					) { append(signIn) }
					append(message.substring(linkStart + signIn.length))
				},
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.fillMaxWidth(),
			)
			Spacer(Modifier.height(16.dp))
			OutlinedTextField(
				value = value,
				onValueChange = { value = it },
				placeholder = { Text(stringResource(R.string.discord_token_hint)) },
				singleLine = true,
				keyboardOptions = KeyboardOptions(
					keyboardType = KeyboardType.Password,
					imeAction = ImeAction.Done,
				),
				keyboardActions = KeyboardActions(onDone = { submit() }),
				shape = RoundedCornerShape(16.dp),
				modifier = Modifier.fillMaxWidth(),
			)
		}
	}
}
