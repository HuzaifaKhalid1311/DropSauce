package org.koitharu.kotatsu.core.ui.dialog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
 * on the clipboard; "close" just discards the pending report.
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
			// report is a file on disk, and it is the only thing this screen has to show
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
				message = listOfNotNull(
					stringResource(R.string.crash_text),
					stackTrace?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
				).joinToString("\n\n"),
			) {
				if (reportJson != null) {
					ExpressivePillButton(
						text = stringResource(R.string.copy_details),
						icon = painterResource(R.drawable.ic_content_copy),
						onClick = { copyToClipboard(getString(R.string.error_details), reportJson) },
					)
					Spacer(Modifier.height(8.dp))
				}
				ExpressiveDialogTextButton(
					text = stringResource(R.string.close),
					onClick = {
						helper.cancelReports()
						dismiss()
						finish()
					},
				)
			}
		}
	}
}
