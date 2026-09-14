package org.koitharu.kotatsu.backup.local.ui.restore

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.ComposeAlertDialogFragment
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogTextButton
import org.koitharu.kotatsu.core.ui.dialog.ExpressivePillButton
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
		val subtitle = when {
			isLoading -> stringResource(R.string.processing_)
			backupDate != null -> formatBackupDate(backupDate!!)
			else -> null
		}
		ExpressiveDialogCard(
			icon = painterResource(R.drawable.ic_backup_restore),
			title = stringResource(R.string.restore_backup),
			message = subtitle,
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
				Column(
					modifier = Modifier
						.heightIn(max = 320.dp)
						.verticalScroll(rememberScrollState()),
				) {
					entries.forEach { item ->
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.heightIn(min = 52.dp)
								.clip(RoundedCornerShape(16.dp))
								.clickable(enabled = item.isEnabled) { viewModel.onItemClick(item) }
								.padding(horizontal = 8.dp),
							verticalAlignment = Alignment.CenterVertically,
						) {
							Checkbox(
								checked = item.isChecked,
								enabled = item.isEnabled,
								onCheckedChange = { viewModel.onItemClick(item) },
							)
							Spacer(Modifier.size(8.dp))
							Text(
								text = stringResource(item.titleResId),
								style = MaterialTheme.typography.bodyLarge,
								color = if (item.isEnabled) {
									MaterialTheme.colorScheme.onSurface
								} else {
									MaterialTheme.colorScheme.onSurfaceVariant
								},
								modifier = Modifier.fillMaxWidth(),
							)
						}
					}
				}
			}
			Spacer(Modifier.size(16.dp))
			ExpressivePillButton(
				text = stringResource(R.string.restore_backup),
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
				dismiss()
			}
			Spacer(Modifier.size(8.dp))
			ExpressiveDialogTextButton(text = stringResource(android.R.string.cancel)) { dismiss() }
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
		MaterialAlertDialogBuilder(context ?: return)
			.setNegativeButton(android.R.string.cancel, null)
			.setTitle(R.string.error_occurred)
			.setMessage(e.getDisplayMessage(resources))
			.show()
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

		fun show(fm: FragmentManager, uri: Uri) {
			RestoreDialogFragment().withArgs(1) {
				putString(AppRouter.KEY_FILE, uri.toString())
			}.show(fm, TAG)
		}
	}
}
