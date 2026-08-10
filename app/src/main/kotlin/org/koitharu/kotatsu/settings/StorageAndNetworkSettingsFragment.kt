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
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.network.DoHProvider
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.FileSize
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.settings.compose.CategoryPalette
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.EditTextSettingsItem
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.NavigationSettingsItem
import org.koitharu.kotatsu.settings.compose.StorageUsageRow
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref
import org.koitharu.kotatsu.settings.userdata.storage.DataCleanupSettingsFragment
import org.koitharu.kotatsu.settings.userdata.storage.StorageUsage
import java.net.Proxy
import javax.inject.Inject

@AndroidEntryPoint
class StorageAndNetworkSettingsFragment : BaseComposeSettingsFragment(R.string.storage_and_network) {

	@Inject
	lateinit var settings: AppSettings

	private val viewModel by viewModels<StorageAndNetworkSettingsViewModel>()
	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				val usage by viewModel.storageUsage.collectAsState(null)
				StorageNetworkScreen(
					usage = usage,
					onDataRemoval = {
						(activity as? SettingsActivity)?.openFragment(
							DataCleanupSettingsFragment::class.java,
							null,
							isFromRoot = false,
						)
					},
					onOpenProxy = {
						(activity as? SettingsActivity)?.openFragment(
							ProxySettingsFragment::class.java,
							null,
							isFromRoot = false,
						)
					},
					proxySummary = buildProxySummary(),
					onSslRestartHint = {
						Snackbar.make(
							requireView(),
							R.string.settings_apply_restart_required,
							Snackbar.LENGTH_INDEFINITE,
						).show()
					},
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.onError.observeEvent(viewLifecycleOwner) { err ->
			Snackbar.make(requireView(), err.message ?: "", Snackbar.LENGTH_SHORT).show()
		}
	}


	private fun buildProxySummary(): String {
		val type = settings.proxyType
		val address = settings.proxyAddress
		val port = settings.proxyPort
		return when {
			type == Proxy.Type.DIRECT -> getString(R.string.disabled)
			address.isNullOrEmpty() || port == 0 -> getString(R.string.invalid_proxy_configuration)
			else -> "$address:$port"
		}
	}
}

@Composable
private fun StorageNetworkScreen(
	usage: StorageUsage?,
	onDataRemoval: () -> Unit,
	onOpenProxy: () -> Unit,
	proxySummary: String,
	onSslRestartHint: () -> Unit,
) {
	val ctx = LocalContext.current
	val colors = CategoryPalette.forKey("storage")

	val networkPolicyEntries = remember {
		ctx.resources.getStringArray(R.array.network_policy).toList()
	}
	val networkPolicyValues = remember {
		ctx.resources.getStringArray(R.array.values_network_policy).toList()
	}
	val dohEntries = remember { ctx.resources.getStringArray(R.array.doh_providers).toList() }
	val dohValues = remember { DoHProvider.entries.names().toList() }
	val imageProxyEntries = remember {
		ctx.resources.getStringArray(R.array.image_proxies).toList()
	}
	val imageProxyValues = remember {
		ctx.resources.getStringArray(R.array.values_image_proxies).toList()
	}

	var prefetchContent by rememberStringPref(AppSettings.KEY_PREFETCH_CONTENT, "1")
	var pagesPreload by rememberStringPref(AppSettings.KEY_PAGES_PRELOAD, "1")
	var doh by rememberStringPref(AppSettings.KEY_DOH, DoHProvider.NONE.name)
	// Blank = use the device WebView UA (kept in sync with Cloudflare challenge solving).
	var userAgent by rememberStringPref(AppSettings.KEY_MIHON_USER_AGENT, "")
	var imageProxy by rememberStringPref(AppSettings.KEY_IMAGES_PROXY, "-1")
	var sslBypass by rememberBooleanPref(AppSettings.KEY_SSL_BYPASS, false)
	var noOffline by rememberBooleanPref(AppSettings.KEY_OFFLINE_DISABLED, false)
	var adblock by rememberBooleanPref(AppSettings.KEY_ADBLOCK, false)

	val storageSummary = usage?.let {
		val used = it.savedManga.bytes + it.pagesCache.bytes + it.otherCache.bytes
		val total = used + it.available.bytes
		ctx.getString(
			R.string.memory_usage_pattern,
			FileSize.BYTES.format(ctx, used),
			FileSize.BYTES.format(ctx, total),
		)
	}

	SettingsScaffold {
		// Inline storage chart + legend (segmented bar). Replaces the simple Info row.
		item {
			StorageUsageRow(
				usage = usage,
				shape = androidx.compose.foundation.shape.RoundedCornerShape(
					topStart = 24.dp,
					topEnd = 24.dp,
					bottomStart = 4.dp,
					bottomEnd = 4.dp,
				),
			)
		}
		item { Spacer(Modifier.height(2.dp).fillMaxWidth()) }
		item {
			SettingsGroup {
				item { pos ->
					NavigationSettingsItem(
						title = stringResource(R.string.data_removal),
						icon = R.drawable.ic_delete,
						shape = androidx.compose.foundation.shape.RoundedCornerShape(
							topStart = 4.dp,
							topEnd = 4.dp,
							bottomStart = 24.dp,
							bottomEnd = 24.dp,
						),
						onClick = onDataRemoval,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = "Network") {
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.prefetch_content),
						entries = networkPolicyEntries,
						entryValues = networkPolicyValues,
						selectedValue = prefetchContent,
						onValueChange = { prefetchContent = it },
						icon = R.drawable.ic_download,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.preload_pages),
						entries = networkPolicyEntries,
						entryValues = networkPolicyValues,
						selectedValue = pagesPreload,
						onValueChange = { pagesPreload = it },
						icon = R.drawable.ic_book_page,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					NavigationSettingsItem(
						title = stringResource(R.string.proxy),
						subtitle = proxySummary,
						icon = R.drawable.ic_plug_large,
						
						shape = pos.shape,
						onClick = onOpenProxy,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.dns_over_https),
						entries = dohEntries,
						entryValues = dohValues,
						selectedValue = doh,
						onValueChange = { doh = it },
						icon = R.drawable.ic_web,

						shape = pos.shape,
					)
				}
				item { pos ->
					EditTextSettingsItem(
						title = stringResource(R.string.user_agent),
						value = userAgent,
						// Blank uses the device default; AppSettings.mihonUserAgentOverride returns null.
						hint = "Default (device WebView)",
						onValueChange = { userAgent = it.trim() },
						icon = R.drawable.ic_script,
						shape = pos.shape,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.images_proxy_title),
						entries = imageProxyEntries,
						entryValues = imageProxyValues,
						selectedValue = imageProxy,
						onValueChange = { imageProxy = it },
						icon = R.drawable.ic_images,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.ignore_ssl_errors),
						subtitle = stringResource(R.string.ignore_ssl_errors_summary),
						checked = sslBypass,
						onCheckedChange = {
							sslBypass = it
							onSslRestartHint()
						},
						icon = R.drawable.ic_lock,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.disable_connectivity_check),
						subtitle = stringResource(R.string.disable_connectivity_check_summary),
						checked = noOffline,
						onCheckedChange = { noOffline = it },
						icon = R.drawable.ic_offline,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.adblock),
						subtitle = stringResource(R.string.adblock_summary),
						checked = adblock,
						onCheckedChange = { adblock = it },
						icon = R.drawable.ic_disable,
						
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}
