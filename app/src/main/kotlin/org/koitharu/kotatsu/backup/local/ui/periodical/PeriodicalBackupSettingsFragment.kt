package org.koitharu.kotatsu.backup.local.ui.periodical

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultCallback
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
import kotlinx.coroutines.flow.StateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.os.OpenDocumentTreeHelper
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.tryLaunch
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.PlainInfoSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SliderSettingsItem
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberIntPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref
import java.util.Date
import javax.inject.Inject

@AndroidEntryPoint
class PeriodicalBackupSettingsFragment :
	BaseComposeSettingsFragment(R.string.periodic_backups),
	ActivityResultCallback<Uri?> {

	private val viewModel by viewModels<PeriodicalBackupSettingsViewModel>()

	@Inject
	lateinit var settings: AppSettings

	private val outputSelectCall = OpenDocumentTreeHelper(this, this)

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				PeriodicalBackupScreen(
					backupsDirectory = viewModel.backupsDirectory,
					lastBackupDate = viewModel.lastBackupDate,
					onPickDirectory = ::pickDirectory,
				)
			}
		}
	}

	private fun pickDirectory() {
		if (!outputSelectCall.tryLaunch(null)) {
			Snackbar.make(requireView(), R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
		}
	}

	override fun onActivityResult(result: Uri?) {
		if (result != null) {
			val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
			context?.contentResolver?.takePersistableUriPermission(result, takeFlags)
			settings.periodicalBackupDirectory = result
			viewModel.updateSummaryData()
		}
	}
}

@Composable
private fun PeriodicalBackupScreen(
	backupsDirectory: StateFlow<String?>,
	lastBackupDate: StateFlow<Date?>,
	onPickDirectory: () -> Unit,
) {
	val ctx = LocalContext.current
	val directory by backupsDirectory.collectAsState()
	val lastBackup by lastBackupDate.collectAsState()

	val frequencyEntries = remember { ctx.resources.getStringArray(R.array.backup_frequency).toList() }
	val frequencyValues = remember { ctx.resources.getStringArray(R.array.backup_frequency_values).toList() }

	var enabled by rememberBooleanPref(AppSettings.KEY_BACKUP_PERIODICAL_ENABLED, false)
	var frequency by rememberStringPref(AppSettings.KEY_BACKUP_PERIODICAL_FREQ, "3")
	var trim by rememberBooleanPref(AppSettings.KEY_BACKUP_PERIODICAL_TRIM, true)
	var maxCount by rememberIntPref(AppSettings.KEY_BACKUP_PERIODICAL_COUNT, 10)

	val dirIsWarning = directory == null
	val dirSubtitle = when (directory) {
		null -> stringResource(R.string.invalid_value_message)
		"" -> null
		else -> directory
	}

	SettingsScaffold {
		item {
			SettingsGroup {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.periodic_backups_enable),
						checked = enabled,
						onCheckedChange = { enabled = it },
						icon = R.drawable.ic_backup_restore,
						main = true,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.backups_output_directory),
						subtitle = dirSubtitle,
						icon = if (dirIsWarning) R.drawable.ic_alert_outline else R.drawable.ic_folder_file,
						shape = pos.shape,
						enabled = enabled,
						onClick = onPickDirectory,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.backup_frequency),
						entries = frequencyEntries,
						entryValues = frequencyValues,
						selectedValue = frequency,
						onValueChange = { frequency = it },
						icon = R.drawable.ic_timelapse,
						shape = pos.shape,
						enabled = enabled,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.delete_old_backups),
						subtitle = stringResource(R.string.delete_old_backups_summary),
						checked = trim,
						onCheckedChange = { trim = it },
						icon = R.drawable.ic_delete,
						shape = pos.shape,
						enabled = enabled,
					)
				}
				item { pos ->
					SliderSettingsItem(
						title = stringResource(R.string.max_backups_count),
						value = maxCount.coerceIn(1, 32),
						valueFrom = 1,
						valueTo = 32,
						stepSize = 1,
						onValueChange = { maxCount = it },
						icon = R.drawable.ic_storage,
						shape = pos.shape,
						enabled = enabled && trim,
					)
				}
			}
		}
		if (lastBackup != null) {
			item {
				PlainInfoSettingsItem(
					text = stringResource(
						R.string.last_successful_backup,
						DateUtils.getRelativeTimeSpanString(lastBackup!!.time),
					),
					icon = R.drawable.ic_info_outline,
				)
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}
