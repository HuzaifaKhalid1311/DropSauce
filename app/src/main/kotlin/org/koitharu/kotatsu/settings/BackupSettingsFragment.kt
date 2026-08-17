package org.koitharu.kotatsu.settings

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backup.MihonBackupExporter
import org.koitharu.kotatsu.backup.MihonBackupManager
import org.koitharu.kotatsu.backup.MihonBackupManager.Options
import org.koitharu.kotatsu.backup.MihonBackupManager.RestoreReport
import org.koitharu.kotatsu.backup.local.domain.BackupUtils
import org.koitharu.kotatsu.backup.local.ui.backup.BackupService
import org.koitharu.kotatsu.backup.local.ui.periodical.PeriodicalBackupSettingsFragment
import org.koitharu.kotatsu.backup.local.ui.restore.RestoreDialogFragment
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.checkNotificationPermission
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.kotatsumigration.domain.KotatsuMigrationManager
import org.koitharu.kotatsu.kotatsumigration.domain.MigrationState
import org.koitharu.kotatsu.kotatsumigration.ui.KotatsuMigrationService
import org.koitharu.kotatsu.kotatsumigration.ui.showExtensionInstallPromptDialog
import org.koitharu.kotatsu.kotatsumigration.ui.showKotatsuMigrationCompleteDialog
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.NavigationSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import javax.inject.Inject

@AndroidEntryPoint
class BackupSettingsFragment : BaseComposeSettingsFragment(R.string.backup_restore) {

	@Inject
	lateinit var backupManager: MihonBackupManager

	@Inject
	lateinit var migrationManager: KotatsuMigrationManager

	@Inject
	lateinit var mihonExporter: MihonBackupExporter

	private val restoreMihonBackupLauncher = registerForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri != null) {
			runMihonRestoreJob(uri, options = Options())
		}
	}

	private val createLocalBackupLauncher = registerForActivityResult(
		ActivityResultContracts.CreateDocument(BackupUtils.MIME_TYPE),
	) { uri ->
		if (uri != null && BackupService.start(requireContext(), uri)) {
			Toast.makeText(requireContext(), R.string.creating_backup, Toast.LENGTH_SHORT).show()
		}
	}

	private val exportMihonBackupLauncher = registerForActivityResult(
		ActivityResultContracts.CreateDocument(MihonBackupExporter.MIME_TYPE),
	) { uri ->
		if (uri != null) {
			runMihonExportJob(uri)
		}
	}

	private val restoreLocalBackupLauncher = registerForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri != null) {
			RestoreDialogFragment.show(parentFragmentManager, uri)
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
				val migrationState by migrationManager.state.collectAsState()
				val migrationSubtitle = when (val s = migrationState) {
					is MigrationState.Running -> stringResource(
						R.string.kotatsu_migration_progress,
						s.done,
						s.total,
					)

					is MigrationState.Finished -> stringResource(
						R.string.kotatsu_migration_result,
						s.summary.converted,
						s.summary.total,
					)

					MigrationState.Idle -> stringResource(R.string.migrate_from_kotatsu_summary)
				}
				BackupScreen(
					onCreateBackup = {
						createLocalBackupLauncher.launch(
							BackupUtils.generateFileName(requireContext()),
						)
					},
					onRestoreLocal = {
						restoreLocalBackupLauncher.launch(
							arrayOf(BackupUtils.MIME_TYPE, "application/*", "*/*"),
						)
					},
					onOpenPeriodic = {
						(activity as? SettingsActivity)?.openFragment(
							PeriodicalBackupSettingsFragment::class.java,
							null,
							isFromRoot = false,
						)
					},
					onRestoreFromTachiyomi = {
						restoreMihonBackupLauncher.launch(arrayOf("application/*", "*/*"))
					},
					migrationSubtitle = migrationSubtitle,
					onMigrateFromKotatsu = ::confirmAndStartKotatsuMigration,
					onExportToMihon = {
						exportMihonBackupLauncher.launch(MihonBackupExporter.generateFileName())
					},
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		migrationManager.onStarted.observeEvent(viewLifecycleOwner) {
			Toast.makeText(requireContext(), R.string.kotatsu_migration_started, Toast.LENGTH_SHORT).show()
		}
		migrationManager.onCompleted.observeEvent(viewLifecycleOwner) { summary ->
			requireContext().showKotatsuMigrationCompleteDialog(summary)
		}
	}

	private fun confirmAndStartKotatsuMigration() {
		if (migrationManager.isRunning) {
			Toast.makeText(requireContext(), R.string.kotatsu_migration_running, Toast.LENGTH_SHORT).show()
			return
		}
		buildAlertDialog(requireContext()) {
			setTitle(R.string.migrate_from_kotatsu)
			setMessage(R.string.migrate_from_kotatsu_confirm)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.migrate_from_kotatsu) { _, _ ->
				if (KotatsuMigrationService.start(requireContext())) {
					Toast.makeText(requireContext(), R.string.kotatsu_migration_running, Toast.LENGTH_SHORT).show()
				}
			}
		}.show()
	}

	private fun runMihonExportJob(uri: Uri) {
		lifecycleScope.launch {
			val message = runCatching {
				mihonExporter.export(uri)
			}.fold(
				onSuccess = { report ->
					if (report.skippedCount > 0) {
						getString(R.string.export_to_mihon_done_partial, report.exportedCount, report.skippedCount)
					} else {
						getString(R.string.export_to_mihon_done, report.exportedCount)
					}
				},
				onFailure = { it.getDisplayMessage(resources) },
			)
			Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
		}
	}

	private fun runMihonRestoreJob(uri: Uri, options: Options) {
		lifecycleScope.launch {
			var restoreReport: RestoreReport? = null
			val result = runCatching {
				restoreReport = backupManager.restoreBackup(uri, options)
			}
			val message = result.fold(
				onSuccess = { getString(R.string.data_restored_success) },
				onFailure = { it.getDisplayMessage(resources) },
			)
			Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
			if (result.isSuccess) {
				restoreReport?.let {
					requireContext().showExtensionInstallPromptDialog(it.missingSources)
					showRestoreNotification(it)
				}
			}
		}
	}

	private fun showRestoreNotification(report: RestoreReport) {
		val ctx = requireContext().applicationContext
		val manager = NotificationManagerCompat.from(ctx)
		val channel = NotificationChannelCompat.Builder(
			RESTORE_CHANNEL_ID,
			NotificationManagerCompat.IMPORTANCE_DEFAULT,
		)
			.setName(getString(R.string.backup_restore))
			.setShowBadge(false)
			.build()
		manager.createNotificationChannel(channel)
		if (!ctx.checkNotificationPermission(RESTORE_CHANNEL_ID)) return

		val details = buildString {
			append(getString(R.string.restore_report_restored_simple, report.restoredMangaCount))
			if (report.missingSources.isNotEmpty()) {
				append('\n')
				append(report.missingSources.joinToString())
			}
		}
		val notification = NotificationCompat.Builder(ctx, RESTORE_CHANNEL_ID)
			.setSmallIcon(R.drawable.general_notification)
			.setContentTitle(getString(R.string.data_restored_success))
			.setContentText(details)
			.setStyle(NotificationCompat.BigTextStyle().bigText(details))
			.setAutoCancel(true)
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.setSilent(true)
			.build()
		manager.notify(RESTORE_NOTIFICATION_ID, notification)
	}

	companion object {
		private const val RESTORE_CHANNEL_ID = "backup_restore"
		private const val RESTORE_NOTIFICATION_ID = 7002
	}
}

@Composable
private fun BackupScreen(
	onCreateBackup: () -> Unit,
	onRestoreLocal: () -> Unit,
	onOpenPeriodic: () -> Unit,
	onRestoreFromTachiyomi: () -> Unit,
	migrationSubtitle: String,
	onMigrateFromKotatsu: () -> Unit,
	onExportToMihon: () -> Unit,
) {
	SettingsScaffold {
		item {
			SettingsGroup(title = stringResource(R.string.backup_restore)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.create_backup),
						subtitle = stringResource(R.string.create_backup_summary),
						icon = R.drawable.ic_save,
						
						shape = pos.shape,
						onClick = onCreateBackup,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.restore_backup),
						subtitle = stringResource(R.string.restore_summary),
						icon = R.drawable.ic_revert,
						
						shape = pos.shape,
						onClick = onRestoreLocal,
					)
				}
				item { pos ->
					NavigationSettingsItem(
						title = stringResource(R.string.periodic_backups),
						subtitle = stringResource(R.string.periodic_backups_summary),
						icon = R.drawable.ic_backup_restore,
						
						shape = pos.shape,
						onClick = onOpenPeriodic,
					)
				}
			}
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.other_apps)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.restore_from_tachiyomi),
						subtitle = stringResource(R.string.restore_tachiyomi_summary),
						icon = R.drawable.ic_revert,

						shape = pos.shape,
						onClick = onRestoreFromTachiyomi,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.migrate_from_kotatsu),
						subtitle = migrationSubtitle,
						icon = R.drawable.ic_backup_restore,

						shape = pos.shape,
						onClick = onMigrateFromKotatsu,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.export_to_mihon),
						subtitle = stringResource(R.string.export_to_mihon_summary),
						icon = R.drawable.ic_upload_file,

						shape = pos.shape,
						onClick = onExportToMihon,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}
