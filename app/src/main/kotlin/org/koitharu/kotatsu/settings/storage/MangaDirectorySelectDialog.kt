package org.koitharu.kotatsu.settings.storage

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.os.OpenDocumentTreeHelper
import org.koitharu.kotatsu.core.ui.ComposeAlertDialogFragment
import org.koitharu.kotatsu.core.ui.dialog.DialogButtonSpec
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerAction
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveButtonGrid
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveOptionGroupItem
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.tryLaunch

@AndroidEntryPoint
class MangaDirectorySelectDialog : ComposeAlertDialogFragment() {

	private val viewModel: MangaDirectorySelectViewModel by viewModels()
	private val pickFileTreeLauncher = OpenDocumentTreeHelper(
		activityResultCaller = this,
		flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
			or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
			or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
	) {
		if (it != null) viewModel.onCustomDirectoryPicked(it)
	}
	private val permissionRequestLauncher = registerForActivityResult(
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			RequestStorageManagerPermissionContract()
		} else {
			ActivityResultContracts.RequestPermission()
		},
	) {
		if (it) {
			viewModel.refresh()
			if (!pickFileTreeLauncher.tryLaunch(null)) {
				Toast.makeText(
					context ?: return@registerForActivityResult,
					R.string.operation_not_supported,
					Toast.LENGTH_SHORT,
				).show()
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.onDismissDialog.observeEvent(viewLifecycleOwner) { dismiss() }
		viewModel.onPickDirectory.observeEvent(viewLifecycleOwner) { pickCustomDirectory() }
		viewModel.onError.observeEvent(viewLifecycleOwner) { e ->
			Toast.makeText(context ?: return@observeEvent, e.getDisplayMessage(resources), Toast.LENGTH_SHORT).show()
		}
	}

	@Composable
	override fun Content() {
		val items by viewModel.items.collectAsState()
		val fileItems = items.filter { it.file != null }
		val customItems = items.filter { it.file == null }
		val colors = MaterialTheme.colorScheme

		ExpressiveDialogCard(
			icon = painterResource(R.drawable.ic_storage),
			title = stringResource(R.string.manga_save_location),
			cornerAction = DialogCornerAction(
				icon = painterResource(R.drawable.ic_close),
				label = stringResource(android.R.string.cancel),
				onClick = ::dismiss,
			),
			footer = if (customItems.isNotEmpty()) {
				{
					ExpressiveButtonGrid(
						buttons = customItems.mapIndexed { index, item ->
							DialogButtonSpec(
								label = item.title ?: stringResource(item.titleRes),
								icon = painterResource(R.drawable.ic_folder_file),
								primary = index == 0,
								onClick = { viewModel.onItemClick(item) },
							)
						},
					)
				}
			} else {
				null
			},
		) {
			fileItems.forEachIndexed { index, item ->
				ExpressiveOptionGroupItem(
					index = index,
					count = fileItems.size,
					onClick = { viewModel.onItemClick(item) },
				) {
					RadioButton(selected = item.isChecked, onClick = null)
					Spacer(Modifier.width(12.dp))
					Column(modifier = Modifier.weight(1f)) {
						Text(
							text = item.title ?: stringResource(item.titleRes),
							style = MaterialTheme.typography.bodyLarge,
							fontWeight = if (item.isChecked) FontWeight.Medium else FontWeight.Normal,
							color = if (item.isChecked) colors.primary else colors.onSurface,
						)
						val path = item.file?.absolutePath
						if (path != null) {
							Text(
								text = path,
								style = MaterialTheme.typography.bodySmall,
								color = if (item.isChecked) {
									colors.primary.copy(alpha = 0.8f)
								} else {
									colors.onSurfaceVariant
								},
							)
						}
					}
				}
			}
		}
	}

	private fun pickCustomDirectory() {
		if (!permissionRequestLauncher.tryLaunch(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
			Toast.makeText(context ?: return, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
		}
	}
}
