package org.koitharu.kotatsu.local.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.os.OpenDocumentTreeHelper
import org.koitharu.kotatsu.core.ui.ComposeAlertDialogFragment
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogTextButton
import org.koitharu.kotatsu.core.util.ext.resolveName
import org.koitharu.kotatsu.core.util.ext.tryLaunch
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.local.data.isSupportedArchive
import javax.inject.Inject

@AndroidEntryPoint
class ImportDialogFragment : ComposeAlertDialogFragment() {

	@Inject
	lateinit var storageManager: LocalStorageManager

	private val importFileCall = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
		startImport(it)
	}
	private val importDirCall = OpenDocumentTreeHelper(this) {
		startImport(listOfNotNull(it))
	}

	@Composable
	override fun Content() {
		ExpressiveDialogCard(
			icon = painterResource(R.drawable.ic_file_zip),
			title = stringResource(R.string._import),
		) {
			ImportChoice(
				icon = R.drawable.ic_file_zip,
				title = R.string.comics_archive,
				subtitle = R.string.comics_archive_import_description,
			) {
				launch(
					importFileCall.tryLaunch(
						arrayOf("application/zip", "application/x-cbz", "application/vnd.comicbook-rar", "application/x-cbr", "application/pdf", "application/epub+zip", "*/*"),
					),
				)
			}
			Spacer(Modifier.height(12.dp))
			ImportChoice(
				icon = R.drawable.ic_folder_file,
				title = R.string.folder_with_images,
				subtitle = R.string.folder_with_images_import_description,
			) {
				launch(importDirCall.tryLaunch(null))
			}
			Spacer(Modifier.height(8.dp))
			ExpressiveDialogTextButton(text = stringResource(android.R.string.cancel)) { dismiss() }
		}
	}

	@Composable
	private fun ImportChoice(
		@DrawableRes icon: Int,
		@StringRes title: Int,
		@StringRes subtitle: Int,
		onClick: () -> Unit,
	) {
		Surface(
			onClick = onClick,
			shape = RoundedCornerShape(20.dp),
			color = MaterialTheme.colorScheme.secondaryContainer,
			contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
			modifier = Modifier.fillMaxWidth(),
		) {
			Row(
				modifier = Modifier.padding(16.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Icon(
					painter = painterResource(icon),
					contentDescription = null,
					modifier = Modifier.size(28.dp),
				)
				Spacer(Modifier.width(16.dp))
				Column {
					Text(
						text = stringResource(title),
						style = MaterialTheme.typography.titleMedium,
					)
					Text(
						text = stringResource(subtitle),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}
	}

	private fun launch(result: Boolean) {
		if (!result) {
			Toast.makeText(requireContext(), R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
		}
	}

	private fun startImport(uris: Collection<Uri>) {
		if (uris.isEmpty()) {
			return
		}
		if (uris.any { !it.isDirectoryUri() && !it.isSupportedArchiveFile() }) {
			Toast.makeText(requireContext(), R.string.text_file_not_supported, Toast.LENGTH_LONG).show()
			return
		}
		uris.forEach {
			storageManager.takePermissions(it)
		}
		val ctx = requireContext()
		val msg = if (ImportService.start(ctx, uris)) {
			R.string.import_will_start_soon
		} else {
			R.string.error_occurred
		}
		Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
		dismiss()
	}

	private fun Uri.isDirectoryUri(): Boolean {
		val ctx = context ?: return false
		return runCatching {
			DocumentFile.fromTreeUri(ctx, this)?.isDirectory == true
		}.getOrDefault(false)
	}

	private fun Uri.isSupportedArchiveFile(): Boolean {
		return runCatching {
			val name = storageManager.contentResolver.resolveName(this)
			!name.isNullOrBlank() && isSupportedArchive(name)
		}.getOrDefault(false)
	}
}
