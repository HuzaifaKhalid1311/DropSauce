package org.koitharu.kotatsu.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.dialog.showConfirmDialog
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.EditTextSettingsItem
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

@AndroidEntryPoint
class ProxySettingsFragment : BaseComposeSettingsFragment(R.string.proxy) {

	private var testJob: Job? = null
	private val isTesting = MutableStateFlow(false)

	@Inject
	@BaseHttpClient
	lateinit var okHttpClient: OkHttpClient

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				ProxyScreen(
					isTesting = isTesting.asStateFlow(),
					onTestConnection = ::testConnection,
				)
			}
		}
	}

	private fun testConnection() {
		if (isTesting.value) return
		testJob?.cancel()
		testJob = viewLifecycleScope.launch {
			isTesting.value = true
			try {
				withContext(Dispatchers.Default) {
					val request = Request.Builder()
						.get()
						.url("http://neverssl.com")
						.build()
					okHttpClient.newCall(request).await().use { response ->
						check(response.isSuccessful) { response.message }
					}
				}
				showTestResult(null)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				e.printStackTraceDebug()
				showTestResult(e)
			} finally {
				isTesting.value = false
			}
		}
	}

	private fun showTestResult(error: Throwable?) {
		showConfirmDialog(
			context = requireContext(),
			icon = if (error == null) R.drawable.ic_web else R.drawable.ic_error_small,
			title = getString(R.string.proxy),
			message = error?.getDisplayMessage(resources) ?: getString(R.string.connection_ok),
			onConfirm = {},
		)
	}
}

@Composable
private fun ProxyScreen(
	isTesting: StateFlow<Boolean>,
	onTestConnection: () -> Unit,
) {
	val ctx = LocalContext.current
	val testing by isTesting.collectAsState()
	val proxyTypeEntries = remember { ctx.resources.getStringArray(R.array.proxy_types).toList() }
	val proxyTypeValues = remember { ctx.resources.getStringArray(R.array.values_proxy_types).toList() }

	var proxyType by org.koitharu.kotatsu.settings.compose.rememberStringPref(
		AppSettings.KEY_PROXY_TYPE,
		"DIRECT",
	)
	var address by org.koitharu.kotatsu.settings.compose.rememberStringPref(AppSettings.KEY_PROXY_ADDRESS, "")
	var port by org.koitharu.kotatsu.settings.compose.rememberStringPref(AppSettings.KEY_PROXY_PORT, "")
	var login by org.koitharu.kotatsu.settings.compose.rememberStringPref(AppSettings.KEY_PROXY_LOGIN, "")
	var password by org.koitharu.kotatsu.settings.compose.rememberStringPref(AppSettings.KEY_PROXY_PASSWORD, "")

	val proxyEnabled = proxyType != "DIRECT"

	SettingsScaffold {
		item {
			SettingsGroup {
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.type),
						entries = proxyTypeEntries,
						entryValues = proxyTypeValues,
						selectedValue = proxyType,
						onValueChange = { proxyType = it },
						icon = R.drawable.ic_settings,
						shape = pos.shape,
					)
				}
				item { pos ->
					EditTextSettingsItem(
						title = stringResource(R.string.address),
						value = address,
						onValueChange = { address = it.trim() },
						icon = R.drawable.ic_web,
						shape = pos.shape,
						enabled = proxyEnabled,
						keyboardType = KeyboardType.Uri,
						isValid = { it.trim().none(Char::isWhitespace) },
					)
				}
				item { pos ->
					EditTextSettingsItem(
						title = stringResource(R.string.port),
						value = port,
						onValueChange = { port = it },
						icon = R.drawable.ic_plug_large,
						shape = pos.shape,
						enabled = proxyEnabled,
						keyboardType = KeyboardType.Number,
						isValid = { it.isEmpty() || it.toIntOrNull() in 1..65535 },
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.authorization_optional)) {
				item { pos ->
					EditTextSettingsItem(
						title = stringResource(R.string.username),
						value = login,
						onValueChange = { login = it },
						icon = R.drawable.ic_user,
						shape = pos.shape,
						enabled = proxyEnabled,
					)
				}
				item { pos ->
					EditTextSettingsItem(
						title = stringResource(R.string.password),
						value = password,
						onValueChange = { password = it },
						icon = R.drawable.ic_lock,
						shape = pos.shape,
						enabled = proxyEnabled,
						mask = { if (it.isEmpty()) "" else "•".repeat(it.length.coerceAtMost(12)) },
						isPassword = true,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.test_connection),
						subtitle = if (testing) stringResource(R.string.loading_) else null,
						icon = R.drawable.ic_retry,
						shape = pos.shape,
						enabled = proxyEnabled && !testing,
						onClick = onTestConnection,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}
