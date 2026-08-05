package org.koitharu.kotatsu.sync.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.CategoryPalette
import org.koitharu.kotatsu.settings.compose.ConfirmDialog
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.MultiSelectSettingsItem
import org.koitharu.kotatsu.settings.compose.PlainInfoSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.sync.data.model.SyncContent
import javax.inject.Inject

@AndroidEntryPoint
class SyncSettingsFragment : BaseComposeSettingsFragment(R.string.google_drive_sync) {

	@Inject
	lateinit var coilImageLoader: ImageLoader

	private val viewModel by viewModels<SyncSettingsViewModel>()

	private val signInLauncher = registerForActivityResult(
		ActivityResultContracts.StartActivityForResult(),
	) { result ->
		viewModel.onSignInResult(result.data)
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				val state by viewModel.uiState.collectAsState()
				val isSyncing by viewModel.isSyncing.collectAsState()
				SyncScreen(
					state = state,
					isSyncing = isSyncing,
					imageLoader = coilImageLoader,
					onSignIn = viewModel::signIn,
					onSignOut = viewModel::signOut,
					onSyncNow = viewModel::syncNow,
					onIntervalChange = viewModel::setInterval,
					onWifiOnlyChange = viewModel::setWifiOnly,
					onSyncOnStartChange = viewModel::setSyncOnStart,
					onDeletionSyncDisabledChange = viewModel::setDeletionSyncDisabled,
					onContentChange = viewModel::setEnabledContent,
					onToggleEmailHidden = viewModel::setEmailHidden,
					onDeleteData = viewModel::deleteAllData,
					onOpenLocalBackup = {
						(requireActivity() as org.koitharu.kotatsu.settings.SettingsActivity)
							.openFragment(org.koitharu.kotatsu.settings.BackupSettingsFragment::class.java, null, false)
					},
				)
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.events.observeEvent(viewLifecycleOwner) { event ->
			when (event) {
				is SyncEvent.LaunchSignIn -> signInLauncher.launch(event.intent)

				is SyncEvent.Message ->
					Snackbar.make(requireView(), event.resId, Snackbar.LENGTH_SHORT).show()

				is SyncEvent.Error -> showErrorDialog(event.message ?: getString(R.string.sync_error))
			}
		}
	}

	private fun showErrorDialog(message: String) {
		MaterialAlertDialogBuilder(requireContext())
			.setTitle(R.string.sync_error)
			.setMessage(message)
			.setPositiveButton(android.R.string.ok, null)
			.setNeutralButton("Copy") { _, _ ->
				val clipboard = requireContext()
					.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
				clipboard.setPrimaryClip(ClipData.newPlainText("sync error", message))
			}
			.show()
	}
}

@Composable
private fun SyncScreen(
	state: SyncUiState,
	isSyncing: Boolean,
	imageLoader: ImageLoader,
	onSignIn: () -> Unit,
	onSignOut: () -> Unit,
	onSyncNow: () -> Unit,
	onIntervalChange: (Int) -> Unit,
	onWifiOnlyChange: (Boolean) -> Unit,
	onSyncOnStartChange: (Boolean) -> Unit,
	onDeletionSyncDisabledChange: (Boolean) -> Unit,
	onContentChange: (Set<String>) -> Unit,
	onToggleEmailHidden: (Boolean) -> Unit,
	onDeleteData: () -> Unit,
	onOpenLocalBackup: () -> Unit,
) {
	val colors = CategoryPalette.forKey("sync")
	var showDeleteConfirm by remember { mutableStateOf(false) }

	val freqValues = listOf("0", "360", "720", "1440", "10080")
	val freqEntries = listOf(
		stringResource(R.string.sync_freq_off),
		stringResource(R.string.sync_freq_6h),
		stringResource(R.string.sync_freq_12h),
		stringResource(R.string.sync_freq_daily),
		stringResource(R.string.sync_freq_weekly),
	)
	val contentValues = SyncContent.entries.map { it.key }
	val contentEntries = SyncContent.entries.map { stringResource(it.titleRes()) }

	SettingsScaffold {
		// Account group
		item {
			SettingsGroup {
				if (state.isSignedIn) {
					item { pos ->
						AccountRow(
							name = state.accountName,
							email = state.accountEmail,
							photoUrl = state.accountPhotoUrl,
							emailHidden = state.isEmailHidden,
							imageLoader = imageLoader,
							shape = pos.shape,
							onToggleHidden = onToggleEmailHidden,
						)
					}
					item { pos ->
						ActionSettingsItem(
							title = stringResource(R.string.sync_sign_out),
							icon = R.drawable.ic_logout,
							shape = pos.shape,
							onClick = onSignOut,
						)
					}
				} else {
					item { pos ->
						ActionSettingsItem(
							title = stringResource(R.string.sync_sign_in),
							subtitle = stringResource(R.string.sync_sign_in_summary),
							icon = R.drawable.ic_cloud_sync,
							iconColors = colors,
							shape = pos.shape,
							onClick = onSignIn,
						)
					}
				}
			}
		}

		if (state.isSignedIn) {
			item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
			item {
				LocalBackupSafetyNote(onOpenLocalBackup)
			}
			item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
			// Sync now + status
			item {
				SettingsGroup {
					item { pos ->
						val subtitle = when {
							isSyncing -> stringResource(R.string.sync_syncing)
							state.lastError != null -> stringResource(R.string.sync_error)
							state.lastSyncTimestamp > 0L -> stringResource(
								R.string.sync_last,
								DateUtils.getRelativeTimeSpanString(state.lastSyncTimestamp).toString(),
							)

							else -> stringResource(R.string.sync_never)
						}
						ActionSettingsItem(
							title = stringResource(R.string.sync_now),
							subtitle = subtitle,
							icon = R.drawable.ic_sync,
							shape = pos.shape,
							enabled = !isSyncing,
							onClick = onSyncNow,
						)
					}
				}
			}

			item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
			// Options
			item {
				SettingsGroup(title = stringResource(R.string.options)) {
					item { pos ->
						ListSettingsItem(
							title = stringResource(R.string.sync_frequency),
							entries = freqEntries,
							entryValues = freqValues,
							selectedValue = state.intervalMinutes.toString(),
							onValueChange = { onIntervalChange(it.toIntOrNull() ?: 0) },
							icon = R.drawable.ic_timelapse,
							shape = pos.shape,
						)
					}
					item { pos ->
						SwitchSettingsItem(
							title = stringResource(R.string.sync_wifi_only),
							checked = state.isWifiOnly,
							onCheckedChange = onWifiOnlyChange,
							icon = R.drawable.ic_network_cellular,
							shape = pos.shape,
						)
					}
					item { pos ->
						SwitchSettingsItem(
							title = stringResource(R.string.sync_on_start),
							subtitle = stringResource(R.string.sync_on_start_summary),
							checked = state.isSyncOnStart,
							onCheckedChange = onSyncOnStartChange,
							icon = R.drawable.ic_play,
							shape = pos.shape,
						)
					}
					item { pos ->
						SwitchSettingsItem(
							title = stringResource(R.string.sync_disable_deletion),
							subtitle = stringResource(R.string.sync_disable_deletion_summary),
							checked = state.isDeletionSyncDisabled,
							onCheckedChange = onDeletionSyncDisabledChange,
							icon = R.drawable.ic_lock,
							shape = pos.shape,
						)
					}
					item { pos ->
						MultiSelectSettingsItem(
							title = stringResource(R.string.sync_what),
							entries = contentEntries,
							entryValues = contentValues,
							selectedValues = state.enabledContent,
							onValuesChange = onContentChange,
							icon = R.drawable.ic_backup_restore,
							shape = pos.shape,
						)
					}
				}
			}

			item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
			// Danger zone
			item {
				SettingsGroup {
					item { pos ->
						ActionSettingsItem(
							title = stringResource(R.string.sync_delete_data),
							subtitle = stringResource(R.string.sync_delete_data_summary),
							icon = R.drawable.ic_delete,
							shape = pos.shape,
							onClick = { showDeleteConfirm = true },
						)
					}
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}

	if (showDeleteConfirm) {
		ConfirmDialog(
			title = stringResource(R.string.sync_delete_data),
			message = stringResource(R.string.sync_delete_confirm_message),
			confirmLabel = stringResource(R.string.delete),
			onConfirm = {
				showDeleteConfirm = false
				onDeleteData()
			},
			onDismiss = { showDeleteConfirm = false },
		)
	}
}

@Composable
private fun LocalBackupSafetyNote(onOpenLocalBackup: () -> Unit) {
	val message = stringResource(R.string.sync_local_backup_note)
	val link = stringResource(R.string.local_backup)
	val linkStart = message.indexOf(link).coerceAtLeast(0)
	PlainInfoSettingsItem(
		text = buildAnnotatedString {
			append(message.substring(0, linkStart))
			withLink(
				LinkAnnotation.Clickable(
					tag = "local_backup",
					styles = TextLinkStyles(style = SpanStyle(color = MaterialTheme.colorScheme.primary)),
					linkInteractionListener = { onOpenLocalBackup() },
				),
			) { append(link) }
			append(message.substring((linkStart + link.length).coerceAtMost(message.length)))
		},
		icon = R.drawable.ic_info_outline,
	)
}

@Composable
private fun AccountRow(
	name: String?,
	email: String?,
	photoUrl: String?,
	emailHidden: Boolean,
	imageLoader: ImageLoader,
	shape: androidx.compose.ui.graphics.Shape,
	onToggleHidden: (Boolean) -> Unit,
) {
	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = shape,
		color = MaterialTheme.colorScheme.surfaceContainer,
		contentColor = MaterialTheme.colorScheme.onSurface,
	) {
		Row(
			modifier = Modifier
				.heightIn(min = 72.dp)
				.padding(horizontal = 12.dp, vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			AccountAvatar(photoUrl = photoUrl, imageLoader = imageLoader)
			Spacer(Modifier.width(14.dp))
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = name ?: stringResource(R.string.sync_account),
					style = MaterialTheme.typography.titleMedium,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = if (emailHidden) Modifier.blur(7.dp) else Modifier,
				)
				if (!email.isNullOrBlank()) {
					Text(
						text = email,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = if (emailHidden) Modifier.blur(7.dp) else Modifier,
					)
				}
			}
			Spacer(Modifier.width(8.dp))
			IconButton(onClick = { onToggleHidden(!emailHidden) }) {
				Icon(
					painter = painterResource(
						if (emailHidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
					),
					contentDescription = stringResource(
						if (emailHidden) R.string.sync_show_email else R.string.sync_hide_email,
					),
				)
			}
		}
	}
}

@Composable
private fun AccountAvatar(photoUrl: String?, imageLoader: ImageLoader) {
	Box(
		modifier = Modifier
			.size(44.dp)
			.clip(CircleShape)
			.background(MaterialTheme.colorScheme.surfaceContainerHighest),
		contentAlignment = Alignment.Center,
	) {
		if (photoUrl.isNullOrBlank()) {
			Icon(
				painter = painterResource(R.drawable.ic_account_circle),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(30.dp),
			)
		} else {
			AsyncImage(
				model = photoUrl,
				imageLoader = imageLoader,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier.fillMaxSize(),
			)
		}
	}
}

private fun SyncContent.titleRes(): Int = when (this) {
	SyncContent.FAVOURITES -> R.string.sync_content_favourites
	SyncContent.HISTORY -> R.string.sync_content_history
	SyncContent.BOOKMARKS -> R.string.sync_content_bookmarks
	SyncContent.FEED -> R.string.sync_content_feed
	SyncContent.TRACKING -> R.string.sync_content_tracking
	SyncContent.STATS -> R.string.sync_content_stats
	SyncContent.SETTINGS -> R.string.sync_content_settings
	SyncContent.CUSTOM_COVERS -> R.string.sync_content_covers
}
