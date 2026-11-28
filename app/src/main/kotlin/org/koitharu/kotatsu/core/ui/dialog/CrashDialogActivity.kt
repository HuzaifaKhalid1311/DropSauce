package org.koitharu.kotatsu.core.ui.dialog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
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
 * on the clipboard; the pending report is discarded as soon as it is shown.
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
		val holder by viewModels<ReportHolder>()
		lifecycleScope.launch {
			if (!holder.isLoaded) {
				// Report is a file on disk, and it is the only thing this screen has to show.
				val report = withContext(Dispatchers.Default) {
					runCatching { helper.reportData }.getOrNull()?.also { data ->
						// A StackOverflowError's trace runs to thousands of lines, and the whole report then
						// overflows the clipboard's size limit: Copy details copied nothing or crashed.
						if (data.containsKey(ReportField.STACK_TRACE)) {
							data.put(ReportField.STACK_TRACE, data.getString(ReportField.STACK_TRACE).trimLines(150, 50))
						}
					}
				}
				holder.json = report?.toJSON()?.takeIf { it.isNotEmpty() }
				holder.stackTrace = report?.getString(ReportField.STACK_TRACE)
				holder.isLoaded = true
				// Held in memory from here on (the holder survives a rotation). Left on disk, ACRA would
				// show it again on every start until the close button was tapped, even after copying it.
				helper.cancelReports()
			}
			showDialog(holder.json, holder.stackTrace)
		}
	}

	private fun showDialog(reportJson: String?, stackTrace: String?) {
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

/** Keeps the first [head] and last [tail] lines (the cause and where it started), noting how many were cut. */
private fun String.trimLines(head: Int, tail: Int): String {
	val lines = lines()
	if (lines.size <= head + tail) return this
	return (lines.take(head) + "… ${lines.size - head - tail} lines skipped …" + lines.takeLast(tail)).joinToString("\n")
}

class ReportHolder : ViewModel() {
	var isLoaded = false
	var json: String? = null
	var stackTrace: String? = null
}
