package org.koitharu.kotatsu.settings

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.os.OpenDocumentTreeHelper
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.DownloadFormat
import org.koitharu.kotatsu.core.prefs.TriStateOption
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.powerManager
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.resolveFile
import org.koitharu.kotatsu.core.util.ext.tryLaunch
import org.koitharu.kotatsu.download.domain.DownloadsIndexRebuildUseCase
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.PlainInfoSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref
import javax.inject.Inject

@AndroidEntryPoint
class DownloadsSettingsFragment :
	BaseComposeSettingsFragment(R.string.downloads),
	SharedPreferences.OnSharedPreferenceChangeListener {

	@Inject
	lateinit var storageManager: LocalStorageManager

	@Inject
	lateinit var downloadsScheduler: DownloadWorker.Scheduler

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var downloadsIndexRebuildUseCase: DownloadsIndexRebuildUseCase

	private val storageSummary = MutableStateFlow<String?>(null)
	private val directoryCount = MutableStateFlow(0)
	private val pagesDirSummary = MutableStateFlow<String?>(null)
	private val dozeAvailable = MutableStateFlow(false)

	private val pickFileTreeLauncher = OpenDocumentTreeHelper(this) {
		if (it != null) onDirectoryPicked(it)
	}

	private val startForDozeResult = registerForActivityResult(
		ActivityResultContracts.StartActivityForResult(),
	) {
		dozeAvailable.value = isDozeIgnoreAvailable()
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				DownloadsScreen(
					storageSummary = storageSummary.asStateFlow(),
					directoryCount = directoryCount.asStateFlow(),
					pagesDirSummary = pagesDirSummary.asStateFlow(),
					dozeAvailable = dozeAvailable.asStateFlow(),
					onBack = { requireActivity().onBackPressedDispatcher.onBackPressed() },
					onPickLocalManga = { router.openDirectoriesSettings() },
					onPickLocalStorage = { router.showDirectorySelectDialog() },
					onMeteredChanged = { updateDownloadsConstraints() },
					onPickPagesDir = ::launchPagesDirPicker,
					onIgnoreDoze = ::startIgnoreDoseActivity,
					onRebuildDownloadsIndex = ::rebuildDownloadsIndex,
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		settings.subscribe(this)
		refreshAsync()
		dozeAvailable.value = isDozeIgnoreAvailable()
	}

	override fun onDestroyView() {
		settings.unsubscribe(this)
		super.onDestroyView()
	}

	override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
		when (key) {
			AppSettings.KEY_LOCAL_STORAGE,
			AppSettings.KEY_LOCAL_MANGA_DIRS,
			AppSettings.KEY_PAGES_SAVE_DIR -> refreshAsync()
		}
	}

	private fun refreshAsync() {
		lifecycleScope.launch {
			val storage = withContext(Dispatchers.IO) { storageManager.getDefaultWriteableDir() }
			storageSummary.value = if (storage != null) {
				storageManager.getDirectoryDisplayName(storage, isFullPath = true)
			} else {
				getString(R.string.not_available)
			}
			directoryCount.value = withContext(Dispatchers.IO) { storageManager.getReadableDirs().size }
			val df = withContext(Dispatchers.IO) { settings.getPagesSaveDir(requireContext()) }
			pagesDirSummary.value = df?.uri?.resolveFile(requireContext())?.path
				?: df?.uri?.toString()
		}
	}

	private fun onDirectoryPicked(uri: Uri) {
		storageManager.takePermissions(uri)
		val doc = DocumentFile.fromTreeUri(requireContext(), uri)?.takeIf { it.canWrite() }
		settings.setPagesSaveDir(doc?.uri)
	}

	private fun launchPagesDirPicker() {
		val current = settings.getPagesSaveDir(requireContext())?.uri
		if (!pickFileTreeLauncher.tryLaunch(current)) {
			Snackbar.make(
				requireView(),
				R.string.operation_not_supported,
				Snackbar.LENGTH_SHORT,
			).show()
		}
	}

	private fun updateDownloadsConstraints() {
		lifecycleScope.launch {
			try {
				withContext(Dispatchers.Default) {
					val option = when (settings.allowDownloadOnMeteredNetwork) {
						TriStateOption.ENABLED -> true
						TriStateOption.ASK -> return@withContext
						TriStateOption.DISABLED -> false
					}
					downloadsScheduler.updateConstraints(option)
				}
			} catch (e: Exception) {
				e.printStackTraceDebug()
			}
		}
	}

	private fun isDozeIgnoreAvailable(): Boolean {
		val ctx = context ?: return false
		val pm = ctx.powerManager ?: return false
		return !pm.isIgnoringBatteryOptimizations(ctx.packageName)
	}

	@SuppressLint("BatteryLife")
	private fun startIgnoreDoseActivity() {
		val ctx = context ?: return
		val pm = ctx.powerManager ?: return
		if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) return
		try {
			val intent = Intent(
				SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
				"package:${ctx.packageName}".toUri(),
			)
			startForDozeResult.launch(intent)
		} catch (e: ActivityNotFoundException) {
			Snackbar.make(
				requireView(),
				R.string.operation_not_supported,
				Snackbar.LENGTH_SHORT,
			).show()
		}
	}

	private fun rebuildDownloadsIndex() {
		lifecycleScope.launch {
			withContext(Dispatchers.Main) {
				android.widget.Toast.makeText(requireContext(), R.string.rebuild_downloads_index_started, android.widget.Toast.LENGTH_SHORT).show()
			}
			try {
				val updatedCount = downloadsIndexRebuildUseCase.execute(requireContext()) { mangaTitle, localSource, favoriteSource ->
					showMigrationDialog(mangaTitle, localSource, favoriteSource)
				}
				withContext(Dispatchers.Main) {
					android.widget.Toast.makeText(
						requireContext(),
						getString(R.string.rebuild_downloads_index_completed, updatedCount),
						android.widget.Toast.LENGTH_LONG,
					).show()
				}
			} catch (e: Exception) {
				e.printStackTraceDebug()
				withContext(Dispatchers.Main) {
					android.widget.Toast.makeText(requireContext(), "Error: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
				}
			}
		}
	}

	private suspend fun showMigrationDialog(
		mangaTitle: String,
		localSource: String,
		favoriteSource: String,
	): Boolean = downloadsIndexRebuildUseCase.showMigrationDialog(requireContext(), mangaTitle, localSource, favoriteSource)
}

@Composable
private fun DownloadsScreen(
	storageSummary: StateFlow<String?>,
	directoryCount: StateFlow<Int>,
	pagesDirSummary: StateFlow<String?>,
	dozeAvailable: StateFlow<Boolean>,
	onBack: () -> Unit,
	onPickLocalManga: () -> Unit,
	onPickLocalStorage: () -> Unit,
	onMeteredChanged: () -> Unit,
	onPickPagesDir: () -> Unit,
	onIgnoreDoze: () -> Unit,
	onRebuildDownloadsIndex: () -> Unit,
) {
	val ctx = LocalContext.current
	val storage by storageSummary.collectAsState()
	val dirCount by directoryCount.collectAsState()
	val pagesDir by pagesDirSummary.collectAsState()
	val showDoze by dozeAvailable.collectAsState()
	val downloadFormats = remember {
		ctx.resources.getStringArray(R.array.download_formats).toList()
	}
	val downloadFormatValues = remember { DownloadFormat.entries.names().toList() }
	val meteredOptions = remember {
		ctx.resources.getStringArray(R.array.metered_network_options).toList()
	}
	val meteredOptionValues = remember { TriStateOption.entries.names().toList() }

	var downloadFormat by rememberStringPref(
		AppSettings.KEY_DOWNLOADS_FORMAT,
		DownloadFormat.AUTOMATIC.name,
	)
	var metered by rememberStringPref(
		AppSettings.KEY_DOWNLOADS_METERED_NETWORK,
		TriStateOption.ASK.name,
	)
	var pagesDirAsk by rememberBooleanPref(AppSettings.KEY_PAGES_SAVE_ASK, true)

	LaunchedEffect(metered) { onMeteredChanged() }

	SettingsScaffold(title = stringResource(R.string.downloads), onBack = onBack) {
		item {
			SettingsGroup(title = "General") {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.local_manga_directories),
						subtitle = ctx.resources.getQuantityStringSafe(R.plurals.items, dirCount, dirCount),
						icon = R.drawable.ic_folder_file,
						
						shape = pos.shape,
						onClick = onPickLocalManga,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.manga_save_location),
						subtitle = storage,
						icon = R.drawable.ic_storage,
						
						shape = pos.shape,
						onClick = onPickLocalStorage,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.preferred_download_format),
						entries = downloadFormats,
						entryValues = downloadFormatValues,
						selectedValue = downloadFormat,
						onValueChange = { downloadFormat = it },
						icon = R.drawable.ic_file_zip,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.download_over_cellular),
						entries = meteredOptions,
						entryValues = meteredOptionValues,
						selectedValue = metered,
						onValueChange = { metered = it },
						icon = R.drawable.ic_network_cellular,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.rebuild_downloads_index),
						subtitle = stringResource(R.string.rebuild_downloads_index_summary),
						icon = R.drawable.ic_sync,
						shape = pos.shape,
						onClick = onRebuildDownloadsIndex,
					)
				}
				if (showDoze) {
					item { pos ->
						ActionSettingsItem(
							title = stringResource(R.string.disable_battery_optimization),
							subtitle = stringResource(R.string.disable_battery_optimization_summary_downloads),
							icon = R.drawable.ic_battery_outline,
							
							shape = pos.shape,
							onClick = onIgnoreDoze,
						)
					}
				}
			}
		}
		item {
			PlainInfoSettingsItem(
				text = stringResource(R.string.downloads_settings_info),
				icon = R.drawable.ic_info_outline,
			)
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.pages_saving)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.default_page_save_dir),
						subtitle = pagesDir ?: stringResource(androidx.preference.R.string.not_set),
						icon = R.drawable.ic_save,
						
						shape = pos.shape,
						onClick = onPickPagesDir,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.ask_for_dest_dir_every_time),
						checked = pagesDirAsk,
						onCheckedChange = { pagesDirAsk = it },
						icon = R.drawable.ic_alert_outline,
						
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}
