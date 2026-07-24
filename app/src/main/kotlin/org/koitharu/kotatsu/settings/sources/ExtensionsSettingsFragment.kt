package org.koitharu.kotatsu.settings.sources

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.explore.data.SourcesSortOrder
import org.koitharu.kotatsu.extensions.install.ShizukuExtensionInstaller
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.CategoryPalette
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.PlainInfoSettingsItem
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref
import org.koitharu.kotatsu.settings.sources.migration.BrokenSourcesMigrationFragment
import rikka.shizuku.Shizuku
import javax.inject.Inject

@AndroidEntryPoint
class ExtensionsSettingsFragment : BaseComposeSettingsFragment(R.string.extensions) {

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var shizukuInstaller: ShizukuExtensionInstaller

	private val viewModel by viewModels<SourcesSettingsViewModel>()
	private val shizukuPermissionListener = object : Shizuku.OnRequestPermissionResultListener {
		override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
			if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) return
			runCatching { Shizuku.removeRequestPermissionResultListener(this) }
			if (grantResult == PackageManager.PERMISSION_GRANTED) {
				settings.isShizukuInstallerEnabled = true
			} else {
				settings.isShizukuInstallerEnabled = false
				Toast.makeText(requireContext(), R.string.shizuku_permission_denied, Toast.LENGTH_LONG).show()
			}
		}
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				val linksEnabled by viewModel.isLinksEnabled.collectAsState(false)
				ExtensionsScreen(
					linksEnabled = linksEnabled,
					onOpenCatalog = { router.openSourcesCatalog(isExternalOnly = true) },
					onOpenBrokenSourcesMigration = {
						(requireActivity() as SettingsActivity).openFragment(
							BrokenSourcesMigrationFragment::class.java,
							args = null,
							isFromRoot = false,
						)
					},
					onLinksChanged = viewModel::setLinksEnabled,
					onShizukuChanged = ::setShizukuEnabled,
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
	}

	override fun onDestroy() {
		runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
		super.onDestroy()
	}

	override fun onResume() {
		super.onResume()
		if (!settings.isShizukuInstallerEnabled) return
		if (!shizukuInstaller.isInstalled) {
			settings.isShizukuInstallerEnabled = false
			return
		}
		val binderReady = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
		if (
			binderReady &&
			runCatching {
				Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
			}.getOrDefault(true)
		) {
			settings.isShizukuInstallerEnabled = false
		}
	}

	private fun setShizukuEnabled(enabled: Boolean) {
		if (!enabled) {
			settings.isShizukuInstallerEnabled = false
			return
		}
		when {
			!shizukuInstaller.isInstalled -> {
				Toast.makeText(requireContext(), R.string.shizuku_not_installed, Toast.LENGTH_LONG).show()
			}
			!runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> {
				Toast.makeText(requireContext(), R.string.shizuku_not_running, Toast.LENGTH_LONG).show()
			}
			runCatching {
				Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
			}.getOrDefault(false) -> {
				settings.isShizukuInstallerEnabled = true
			}
			else -> runCatching {
				Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
				Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
			}.onFailure {
				Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
				Toast.makeText(requireContext(), R.string.shizuku_permission_denied, Toast.LENGTH_LONG).show()
			}
		}
	}

	private companion object {
		const val SHIZUKU_PERMISSION_REQUEST_CODE = 14045
	}
}

@Composable
private fun ExtensionsScreen(
	linksEnabled: Boolean,
	onOpenCatalog: () -> Unit,
	onOpenBrokenSourcesMigration: () -> Unit,
	onLinksChanged: (Boolean) -> Unit,
	onShizukuChanged: (Boolean) -> Unit,
) {
	val ctx = LocalContext.current
	val colors = CategoryPalette.forKey("extensions")
	val sortEntries = remember {
		SourcesSortOrder.entries.map { ctx.getString(it.titleResId) }
	}
	val sortValues = remember { SourcesSortOrder.entries.names().toList() }
	val incognitoEntries = remember {
		ctx.resources.getStringArray(R.array.incognito_nsfw_options).toList()
	}
	val incognitoValues = remember {
		ctx.resources.getStringArray(R.array.incognito_nsfw_values).toList()
	}

	var sortOrder by rememberStringPref(AppSettings.KEY_SOURCES_ORDER, SourcesSortOrder.ALPHABETIC.name)
	var grid by rememberBooleanPref(AppSettings.KEY_SOURCES_GRID, true)
	var noNsfw by rememberBooleanPref(AppSettings.KEY_DISABLE_NSFW, false)
	var tagsWarnings by rememberBooleanPref(AppSettings.KEY_TAGS_WARNINGS, true)
	var incognitoNsfw by rememberStringPref(AppSettings.KEY_INCOGNITO_NSFW, "ASK")
	val shizukuEnabled by rememberBooleanPref(AppSettings.KEY_SHIZUKU_INSTALLER, false)
	var privateEnabled by rememberBooleanPref(AppSettings.KEY_PRIVATE_INSTALLER, false)
	var autoUpdateExtensions by rememberBooleanPref(AppSettings.KEY_AUTO_UPDATE_EXTENSIONS, false)
	var updateNotifications by rememberBooleanPref(AppSettings.KEY_EXTENSION_UPDATE_NOTIFICATIONS, true)

	SettingsScaffold {
		item {
			SettingsGroup(title = "Catalog") {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.manage_extensions),
						subtitle = stringResource(R.string.manage_extensions_summary),
						icon = R.drawable.ic_download,
						
						shape = pos.shape,
						onClick = onOpenCatalog,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.migrate_broken_sources),
						subtitle = stringResource(R.string.migrate_broken_sources_summary),
						icon = R.drawable.ic_migrate,
						shape = pos.shape,
						onClick = onOpenBrokenSourcesMigration,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.private_extensions),
						subtitle = stringResource(R.string.private_extensions_summary),
						checked = privateEnabled,
						onCheckedChange = { privateEnabled = it },
						icon = R.drawable.ic_shield,
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.appearance)) {
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.sort_order),
						entries = sortEntries,
						entryValues = sortValues,
						selectedValue = sortOrder,
						onValueChange = { sortOrder = it },
						icon = R.drawable.ic_sort_asc,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.show_in_grid_view),
						checked = grid,
						onCheckedChange = { grid = it },
						icon = R.drawable.ic_grid,
						
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.filter)) {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.disable_nsfw),
						subtitle = stringResource(R.string.disable_nsfw_summary),
						checked = noNsfw,
						onCheckedChange = { noNsfw = it },
						icon = R.drawable.ic_nsfw,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.tags_warnings),
						subtitle = stringResource(R.string.tags_warnings_summary),
						checked = tagsWarnings,
						onCheckedChange = { tagsWarnings = it },
						icon = R.drawable.ic_tag,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.incognito_for_nsfw),
						entries = incognitoEntries,
						entryValues = incognitoValues,
						selectedValue = incognitoNsfw,
						onValueChange = { incognitoNsfw = it },
						icon = R.drawable.ic_incognito,
						
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.handle_links)) {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.handle_links),
						subtitle = stringResource(R.string.handle_links_summary),
						checked = linksEnabled,
						onCheckedChange = onLinksChanged,
						icon = R.drawable.ic_open_external,
						
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.auto_update)) {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.shizuku_title),
						subtitle = stringResource(R.string.shizuku_summary),
						checked = shizukuEnabled,
						onCheckedChange = onShizukuChanged,
						icon = R.drawable.ic_auth_key_large,
						shape = pos.shape,
						enabled = !privateEnabled,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.ext_auto_update_title),
						subtitle = stringResource(R.string.ext_auto_update_summary),
						checked = if (privateEnabled) true else autoUpdateExtensions,
						onCheckedChange = { autoUpdateExtensions = it },
						icon = R.drawable.ic_updated,
						shape = pos.shape,
						enabled = !privateEnabled && shizukuEnabled,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.ext_update_notifications_title),
						subtitle = stringResource(R.string.ext_update_notifications_summary),
						checked = updateNotifications,
						onCheckedChange = { updateNotifications = it },
						icon = R.drawable.ic_updated,
						shape = pos.shape,
						enabled = !privateEnabled,
					)
				}
			}
		}
		if (privateEnabled) {
			item {
				PlainInfoSettingsItem(
					text = stringResource(R.string.private_mode_auto_update_note),
					icon = R.drawable.ic_info_outline,
				)
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}
