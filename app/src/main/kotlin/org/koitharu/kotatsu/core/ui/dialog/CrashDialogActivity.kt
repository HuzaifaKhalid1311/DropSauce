package org.koitharu.kotatsu.core.ui.dialog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.acra.ReportField
import org.acra.dialog.CrashReportDialogHelper
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.copyToClipboard

/**
 * Replaces ACRA's stock crash dialog with the app's M3 Expressive card.
 * No report sender is configured, so the only meaningful action is putting the report
 * on the clipboard; closing discards the pending report.
 */
class CrashDialogActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		val settings = AppSettings(this)
		setTheme(settings.colorScheme.styleResId)
		if (settings.isAmoledTheme) {
			setTheme(R.style.ThemeOverlay_Kotatsu_Amoled)
		}
		super.onCreate(savedInstanceState)
		val helper = try {
			CrashReportDialogHelper(this, intent)
		} catch (e: IllegalArgumentException) {
			finish()
			return
		}
		lifecycleScope.launch {
			// Report is a file on disk, and it is the only thing this screen has to show.
			val report = withContext(Dispatchers.Default) {
				runCatching { helper.reportData }.getOrNull()
			}
			showDialog(helper, report?.toJSON()?.takeIf { it.isNotEmpty() }, report?.getString(ReportField.STACK_TRACE))
		}
	}

	private fun showDialog(helper: CrashReportDialogHelper, reportJson: String?, stackTrace: String?) {
		showComposeDialog(this, cancelable = false) { dismiss ->
			ExpressiveDialogCard(
				icon = painterResource(R.drawable.ic_alert_outline),
				title = stringResource(R.string.error_occurred),
				destructive = true,
				message = listOfNotNull(
					stringResource(R.string.crash_text),
					stackTrace?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
				).joinToString("\n\n"),
				cornerAction = DialogCornerAction(
					icon = painterResource(R.drawable.ic_close),
					label = stringResource(R.string.close),
					onClick = {
						helper.cancelReports()
						dismiss()
						finish()
					},
				),
				footer = if (reportJson != null) {
					{
						ExpressivePillButton(
							text = stringResource(R.string.copy_details),
							icon = painterResource(R.drawable.ic_content_copy),
							onClick = { copyToClipboard(getString(R.string.error_details), reportJson) },
						)
					}
				} else {
					null
				},
			)
		}
	}
}
