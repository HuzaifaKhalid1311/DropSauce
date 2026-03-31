package org.haziffe.dropsauce.settings.userdata

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.viewModels
import androidx.preference.Preference
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.backups.domain.BackupUtils
import org.haziffe.dropsauce.backups.ui.backup.BackupService
import org.haziffe.dropsauce.core.exceptions.resolve.SnackbarErrorObserver
import org.haziffe.dropsauce.core.nav.router
import org.haziffe.dropsauce.core.prefs.AppSettings
import org.haziffe.dropsauce.core.ui.BasePreferenceFragment
import org.haziffe.dropsauce.core.util.ext.observe
import org.haziffe.dropsauce.core.util.ext.observeEvent
import org.haziffe.dropsauce.core.util.ext.tryLaunch

@AndroidEntryPoint
class BackupsSettingsFragment : BasePreferenceFragment(R.string.backup_restore),
    ActivityResultCallback<Uri?> {

    private val viewModel: BackupsSettingsViewModel by viewModels()

    private val backupSelectCall = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
        this,
    )

    private val backupCreateCall = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            if (!BackupService.start(requireContext(), uri)) {
                Snackbar.make(
                    listView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT,
                ).show()
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_backups)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindPeriodicalBackupSummary()
        viewModel.onError.observeEvent(viewLifecycleOwner, SnackbarErrorObserver(listView, this))
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        return when (preference.key) {
            AppSettings.KEY_BACKUP -> {
                if (!backupCreateCall.tryLaunch(BackupUtils.generateFileName(preference.context))) {
                    Snackbar.make(
                        listView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT,
                    ).show()
                }
                true
            }

            AppSettings.KEY_RESTORE -> {
                if (!backupSelectCall.tryLaunch(arrayOf("*/*"))) {
                    Snackbar.make(
                        listView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT,
                    ).show()
                }
                true
            }

            else -> super.onPreferenceTreeClick(preference)
        }
    }

    override fun onActivityResult(result: Uri?) {
        if (result != null) {
            router.showBackupRestoreDialog(result)
        }
    }

    private fun bindPeriodicalBackupSummary() {
        val preference = findPreference<Preference>(AppSettings.KEY_BACKUP_PERIODICAL_ENABLED) ?: return
        val entries = resources.getStringArray(R.array.backup_frequency)
        val entryValues = resources.getStringArray(R.array.values_backup_frequency)
        viewModel.periodicalBackupFrequency.observe(viewLifecycleOwner) { freq ->
            preference.summary = if (freq == 0L) {
                getString(R.string.disabled)
            } else {
                val index = entryValues.indexOf(freq.toString())
                entries.getOrNull(index)
            }
        }
    }
}
