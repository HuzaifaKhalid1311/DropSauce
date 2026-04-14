package org.koitharu.kotatsu.settings

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.backup.MihonBackupManager.Options
import org.koitharu.kotatsu.backup.MihonBackupManager.RestoreReport
import org.koitharu.kotatsu.backup.MihonBackupManager
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.checkNotificationPermission
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import javax.inject.Inject

@AndroidEntryPoint
class BackupSettingsFragment : BasePreferenceFragment(R.string.backup_restore) {

    @Inject
    lateinit var backupManager: MihonBackupManager

    private val restoreBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
          runRestoreJob(uri, options = getOptions())
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_backup)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        return when (preference.key) {
            AppSettings.KEY_RESTORE_BACKUP -> {
                restoreBackupLauncher.launch(arrayOf("application/*", "*/*"))
                true
            }

            else -> super.onPreferenceTreeClick(preference)
        }
    }

      private fun runRestoreJob(uri: Uri, options: Options) {
        lifecycleScope.launch {
			var restoreReport: RestoreReport? = null
            val result = runCatching {
                restoreReport = backupManager.restoreBackup(uri, options)
            }
            val message = result.fold(
                onSuccess = {
                    getString(R.string.data_restored_success)
                },
                onFailure = { it.getDisplayMessage(resources) },
            )
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
			if (result.isSuccess) {
            		restoreReport?.let {
            			showRestoreDiagnostics(it)
            			showRestoreNotification(it)
            		}
			}
        }
    }

  private fun showRestoreDiagnostics(report: RestoreReport) {
                val lines = buildList {
                  add(getString(R.string.restore_report_restored_simple, report.restoredMangaCount))
                  if (report.missingSources.isNotEmpty()) {
                    add(getString(R.string.restore_report_missing_extensions_compact, report.missingSources.joinToString("\n")))
                  }
                }
                if (lines.size <= 1) return
                buildAlertDialog(requireContext()) {
                  setTitle(R.string.restore_diagnostics_title)
                  setMessage(lines.joinToString(separator = "\n\n"))
                  setPositiveButton(android.R.string.ok, null)
                }.show()
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

      private fun getOptions() = Options()

	companion object {
		private const val RESTORE_CHANNEL_ID = "backup_restore"
		private const val RESTORE_NOTIFICATION_ID = 7002
	}
}

