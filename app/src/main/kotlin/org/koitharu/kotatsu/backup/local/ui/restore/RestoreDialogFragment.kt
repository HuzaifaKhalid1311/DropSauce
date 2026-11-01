package org.koitharu.kotatsu.backup.local.ui.restore

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.ComposeAlertDialogFragment
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerAction
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveOptionGroupItem
import org.koitharu.kotatsu.core.ui.dialog.ExpressivePillButton
import org.koitharu.kotatsu.core.ui.dialog.showComposeDialog
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.withArgs
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

@AndroidEntryPoint
class RestoreDialogFragment : ComposeAlertDialogFragment() {

	private val viewModel: RestoreViewModel by viewModels()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		isCancelable = false
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.onError.observeEvent(viewLifecycleOwner, ::onError)
	}

	@Composable
	override fun Content() {
		val isLoading by viewModel.isLoading.collectAsState()
		val entries by viewModel.availableEntries.collectAsState()
		val backupDate by viewModel.backupDate.collectAsState()
		val colors = MaterialTheme.colorScheme
		val subtitle = when {
			isLoading -> stringResource(R.string.processing_)
			backupDate != null -> formatBackupDate(backupDate!!)
			else -> null
		}
		ExpressiveDialogCard(
			icon = painterResource(R.drawable.ic_backup_restore),
			title = stringResource(R.string.restore_backup),
			message = subtitle,
			cornerAction = DialogCornerAction(
				icon = painterResource(R.drawable.ic_close),
				label = stringResource(android.R.string.cancel),
				onClick = ::dismiss,
			),
			footer = {
				ExpressivePillButton(
					text = stringResource(R.string.restore_backup),
					icon = painterResource(R.drawable.ic_backup_restore),
					primary = true,
					enabled = !isLoading && entries.any { it.isChecked },
				) {
					val ctx = context ?: return@ExpressivePillButton
					val started = startRestoreService()
					Toast.makeText(
						ctx,
						if (started) R.string.restoring_backup else R.string.error_occurred,
						Toast.LENGTH_SHORT,
					).show()
					if (started) setFragmentResult(RESULT_STARTED, Bundle.EMPTY)
					dismiss()
				}
			},
		) {
			if (isLoading) {
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.padding(24.dp),
					contentAlignment = Alignment.Center,
				) {
					LoadingIndicator()
				}
			} else {
				entries.forEachIndexed { index, item ->
					ExpressiveOptionGroupItem(
						index = index,
						count = entries.size,
						enabled = item.isEnabled,
						onClick = { viewModel.onItemClick(item) },
					) {
						Checkbox(
							checked = item.isChecked,
							enabled = item.isEnabled,
							onCheckedChange = null,
						)
						Spacer(Modifier.width(12.dp))
						Text(
							text = stringResource(item.titleResId),
							style = MaterialTheme.typography.bodyLarge,
							color = when {
								!item.isEnabled -> colors.onSurfaceVariant.copy(alpha = 0.5f)
								item.isChecked -> colors.primary
								else -> colors.onSurface
							},
							fontWeight = if (item.isChecked && item.isEnabled) FontWeight.Medium else FontWeight.Normal,
							modifier = Modifier.weight(1f),
						)
					}
				}
			}
		}
	}

	private fun startRestoreService(): Boolean {
		return RestoreService.start(
			context ?: return false,
			viewModel.uri ?: return false,
			viewModel.getCheckedSections(),
		)
	}

	private fun onError(e: Throwable) {
		val context = context ?: return
		val message = e.getDisplayMessage(resources)
		showComposeDialog(context) { close ->
			ExpressiveDialogCard(
				icon = painterResource(R.drawable.ic_error_small),
				title = stringResource(R.string.error_occurred),
				message = message,
				cornerAction = DialogCornerAction(
					icon = painterResource(R.drawable.ic_close),
					label = stringResource(android.R.string.cancel),
					onClick = close,
				),
			)
		}
		dismiss()
	}

	private fun formatBackupDate(date: Date): String {
		return getString(
			R.string.backup_date_,
			SimpleDateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(date),
		)
	}

	companion object {

		private const val TAG = "RestoreDialogFragment"

		/** Fragment result key, sent once the restore service has been started. */
		const val RESULT_STARTED = "restore_started"

		fun show(fm: FragmentManager, uri: Uri) {
			RestoreDialogFragment().withArgs(1) {
				putString(AppRouter.KEY_FILE, uri.toString())
			}.show(fm, TAG)
		}
	}
}
