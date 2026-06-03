package org.koitharu.kotatsu.settings.sources

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
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.explore.data.SourcesSortOrder
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.CategoryPalette
import org.koitharu.kotatsu.settings.compose.ComposeOwnedScreen
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref

@AndroidEntryPoint
class ExtensionsSettingsFragment : BaseComposeSettingsFragment(R.string.extensions) {

	private val viewModel by viewModels<SourcesSettingsViewModel>()
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
					onBack = { requireActivity().onBackPressedDispatcher.onBackPressed() },
					onOpenCatalog = { router.openSourcesCatalog(isExternalOnly = true) },
					onLinksChanged = viewModel::setLinksEnabled,
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
	}

}

@Composable
private fun ExtensionsScreen(
	linksEnabled: Boolean,
	onBack: () -> Unit,
	onOpenCatalog: () -> Unit,
	onLinksChanged: (Boolean) -> Unit,
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

	SettingsScaffold(title = stringResource(R.string.extensions), onBack = onBack) {
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
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}
