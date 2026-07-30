package org.koitharu.kotatsu.download.ui.dialog

import android.os.Bundle
import android.view.View
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentResultListener
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.DownloadFormat
import org.koitharu.kotatsu.core.ui.ComposeAlertDialogFragment
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogTextButton
import org.koitharu.kotatsu.core.ui.dialog.ExpressivePillButton
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.joinToStringWithLimit
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.main.ui.owners.BottomNavOwner
import org.koitharu.kotatsu.parsers.util.format
import org.koitharu.kotatsu.settings.storage.DirectoryModel

@AndroidEntryPoint
class DownloadDialogFragment : ComposeAlertDialogFragment() {

	private val viewModel by viewModels<DownloadDialogViewModel>()

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.onScheduled.observeEvent(viewLifecycleOwner, this::onDownloadScheduled)
		viewModel.onError.observeEvent(viewLifecycleOwner, this::onError)
	}

	@Composable
	override fun Content() {
		val context = LocalContext.current
		val options by viewModel.chaptersSelectOptions.collectAsState()
		val destinations by viewModel.availableDestinations.collectAsState()
		val defaultFormat by viewModel.defaultFormat.collectAsState()
		val isLoading by viewModel.isLoading.collectAsState()
		val isOptionsLoading by viewModel.isOptionsLoading.collectAsState()

		var selectedOption by rememberSaveable { mutableIntStateOf(OPTION_WHOLE_MANGA) }
		var moreExpanded by rememberSaveable { mutableStateOf(false) }
		var startNow by rememberSaveable { mutableStateOf(true) }
		var formatIndex by rememberSaveable { mutableIntStateOf(-1) }
		var destinationIndex by rememberSaveable { mutableIntStateOf(0) }

		LaunchedEffect(defaultFormat) { defaultFormat?.let { formatIndex = it.ordinal } }
		LaunchedEffect(destinations) {
			val i = destinations.indexOfFirst { it.isChecked }
			if (i >= 0) destinationIndex = i
		}
		val summary = remember { viewModel.manga.joinToStringWithLimit(context, 120) { it.title } }
		val formatLabels = stringArrayResource(R.array.download_formats)

		ExpressiveDialogCard(
			icon = painterResource(R.drawable.ic_download),
			title = stringResource(R.string.download),
			message = summary,
		) {
			Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
				// Whole manga
				OptionRow(
					selected = selectedOption == OPTION_WHOLE_MANGA,
					title = stringResource(R.string.download_option_whole_manga),
					subtitle = options.wholeManga.chaptersCount
						.takeIf { it > 0 }
						?.let { context.resources.getQuantityStringSafe(R.plurals.chapters, it, it) },
					onSelect = { selectedOption = OPTION_WHOLE_MANGA },
				)
				// All chapters for the selected branch
				options.wholeBranch?.let { branch ->
					val keys = branch.branches.keys.toList()
					OptionRow(
						selected = selectedOption == OPTION_WHOLE_BRANCH,
						title = stringResource(R.string.download_option_all_chapters, branch.selectedBranch ?: ""),
						subtitle = branch.chaptersCount
							.takeIf { it > 0 }
							?.let { context.resources.getQuantityStringSafe(R.plurals.chapters, it, it) },
						selectorLabel = branch.selectedBranch ?: stringResource(R.string.unknown),
						selectorItems = keys.map { it ?: stringResource(R.string.unknown) },
						onSelectItem = { viewModel.setSelectedBranch(keys.getOrNull(it)) },
						onSelect = { selectedOption = OPTION_WHOLE_BRANCH },
					)
				}
				// First N chapters
				options.firstChapters?.let { first ->
					val values = chaptersCount(first.maxAvailableCount).toList()
					OptionRow(
						selected = selectedOption == OPTION_FIRST_CHAPTERS,
						title = stringResource(
							R.string.download_option_first_n_chapters,
							context.resources.getQuantityStringSafe(
								R.plurals.chapters,
								first.chaptersCount,
								first.chaptersCount,
							),
						),
						subtitle = first.branch,
						selectorLabel = first.chaptersCount.format(),
						selectorItems = values.map { it.format() },
						onSelectItem = { values.getOrNull(it)?.let(viewModel::setFirstChaptersCount) },
						onSelect = { selectedOption = OPTION_FIRST_CHAPTERS },
					)
				}
				// Next N unread chapters
				options.unreadChapters?.let { unread ->
					val values = chaptersCount(unread.maxAvailableCount).toList()
					OptionRow(
						selected = selectedOption == OPTION_UNREAD_CHAPTERS,
						title = if (unread.chaptersCount == Int.MAX_VALUE) {
							stringResource(R.string.download_option_all_unread)
						} else {
							stringResource(
								R.string.download_option_next_unread_n_chapters,
								context.resources.getQuantityStringSafe(
									R.plurals.chapters,
									unread.chaptersCount,
									unread.chaptersCount,
								),
							)
						},
						subtitle = null,
						selectorLabel = if (unread.chaptersCount == Int.MAX_VALUE) {
							stringResource(R.string.chapters_all)
						} else {
							unread.chaptersCount.format()
						},
						selectorItems = values.map { it.format() } + stringResource(R.string.chapters_all),
						onSelectItem = {
							viewModel.setUnreadChaptersCount(values.getOrNull(it) ?: Int.MAX_VALUE)
						},
						onSelect = { selectedOption = OPTION_UNREAD_CHAPTERS },
					)
				}

				if (isOptionsLoading) {
					LinearProgressIndicator(
						modifier = Modifier
							.fillMaxWidth()
							.padding(vertical = 8.dp),
					)
				}
				if (viewModel.manga.size == 1) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 8.dp, vertical = 8.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Icon(
							painter = painterResource(R.drawable.ic_tap),
							contentDescription = null,
							tint = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.size(18.dp),
						)
						Spacer(Modifier.size(8.dp))
						Text(
							text = stringResource(R.string.chapter_selection_hint),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}

				// Start download now
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.heightIn(min = 48.dp)
						.clip(RoundedCornerShape(16.dp))
						.clickable { startNow = !startNow }
						.padding(horizontal = 8.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = stringResource(R.string.start_download),
						style = MaterialTheme.typography.bodyLarge,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.weight(1f),
					)
					Switch(checked = startNow, onCheckedChange = { startNow = it })
				}

				// More options toggle
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.heightIn(min = 48.dp)
						.clip(RoundedCornerShape(16.dp))
						.clickable { moreExpanded = !moreExpanded }
						.padding(horizontal = 8.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = stringResource(R.string.more_options),
						style = MaterialTheme.typography.bodyLarge,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				if (moreExpanded) {
					SelectorField(
						label = stringResource(R.string.destination_directory),
						current = destinations.getOrNull(destinationIndex)
							?.let { it.title ?: stringResource(it.titleRes) } ?: "",
						items = destinations.map { it.title ?: stringResource(it.titleRes) },
						onSelect = { destinationIndex = it },
					)
					SelectorField(
						label = stringResource(R.string.preferred_download_format),
						current = formatLabels.getOrNull(formatIndex) ?: "",
						items = formatLabels.toList(),
						onSelect = { formatIndex = it },
					)
				}
			}

			Spacer(Modifier.size(16.dp))
			ExpressivePillButton(
				text = stringResource(R.string.download),
				primary = true,
				enabled = !isLoading,
			) {
				router.askForDownloadOverMeteredNetwork { allowMetered ->
					schedule(
						allowMetered = allowMetered,
						selectedOption = selectedOption,
						startNow = startNow,
						formatIndex = formatIndex,
						destinationIndex = destinationIndex,
					)
				}
			}
			Spacer(Modifier.size(8.dp))
			ExpressiveDialogTextButton(text = stringResource(android.R.string.cancel)) { dialog?.cancel() }
		}
	}

	@Composable
	private fun OptionRow(
		selected: Boolean,
		title: String,
		subtitle: String?,
		onSelect: () -> Unit,
		selectorLabel: String? = null,
		selectorItems: List<String> = emptyList(),
		onSelectItem: (Int) -> Unit = {},
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(min = 52.dp)
				.clip(RoundedCornerShape(16.dp))
				.clickable { onSelect() }
				.padding(horizontal = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			RadioButton(selected = selected, onClick = onSelect)
			Spacer(Modifier.size(8.dp))
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = title,
					style = MaterialTheme.typography.bodyLarge,
					color = MaterialTheme.colorScheme.onSurface,
				)
				if (!subtitle.isNullOrEmpty()) {
					Text(
						text = subtitle,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
			if (selected && selectorLabel != null && selectorItems.isNotEmpty()) {
				Box {
					var expanded by remember { mutableStateOf(false) }
					TextButton(onClick = { expanded = true }) { Text(selectorLabel) }
					DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
						selectorItems.forEachIndexed { index, label ->
							DropdownMenuItem(
								text = { Text(label) },
								onClick = {
									expanded = false
									onSelectItem(index)
								},
							)
						}
					}
				}
			}
		}
	}

	@Composable
	private fun SelectorField(
		label: String,
		current: String,
		items: List<String>,
		onSelect: (Int) -> Unit,
	) {
		Column(modifier = Modifier.padding(top = 8.dp)) {
			Text(
				text = label,
				style = MaterialTheme.typography.titleSmall,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Box {
				var expanded by remember { mutableStateOf(false) }
				TextButton(
					onClick = { expanded = true },
					modifier = Modifier.fillMaxWidth(),
				) {
					Text(text = current, modifier = Modifier.weight(1f))
				}
				DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
					items.forEachIndexed { index, item ->
						DropdownMenuItem(
							text = { Text(item) },
							onClick = {
								expanded = false
								onSelect(index)
							},
						)
					}
				}
			}
		}
	}

	private fun schedule(
		allowMetered: Boolean,
		selectedOption: Int,
		startNow: Boolean,
		formatIndex: Int,
		destinationIndex: Int,
	) {
		val options = viewModel.chaptersSelectOptions.value
		val macro = when (selectedOption) {
			OPTION_WHOLE_MANGA -> options.wholeManga
			OPTION_WHOLE_BRANCH -> options.wholeBranch ?: return
			OPTION_FIRST_CHAPTERS -> options.firstChapters ?: return
			OPTION_UNREAD_CHAPTERS -> options.unreadChapters ?: return
			else -> return
		}
		viewModel.confirm(
			startNow = startNow,
			chaptersMacro = macro,
			format = DownloadFormat.entries.getOrNull(formatIndex),
			destination = viewModel.availableDestinations.value.getOrNull(destinationIndex),
			allowMetered = allowMetered,
		)
	}

	private fun onError(e: Throwable) {
		MaterialAlertDialogBuilder(context ?: return)
			.setNegativeButton(R.string.close, null)
			.setTitle(R.string.error)
			.setMessage(e.getDisplayMessage(resources))
			.show()
		dismiss()
	}

	private fun onDownloadScheduled(isStarted: Boolean) {
		val bundle = Bundle(1)
		bundle.putBoolean(ARG_STARTED, isStarted)
		setFragmentResult(RESULT_KEY, bundle)
		dismiss()
	}

	private fun chaptersCount(max: Int) = sequence {
		yield(1)
		var seed = 5
		var step = 5
		while (seed + step <= max) {
			yield(seed)
			step = when {
				seed < 20 -> 5
				seed < 60 -> 10
				else -> 20
			}
			seed += step
		}
		if (seed < max) {
			yield(max)
		}
	}

	private class SnackbarResultListener(
		private val host: View,
	) : FragmentResultListener {

		override fun onFragmentResult(requestKey: String, result: Bundle) {
			val isStarted = result.getBoolean(ARG_STARTED, true)
			val snackbar = Snackbar.make(
				host,
				if (isStarted) R.string.download_started else R.string.download_added,
				Snackbar.LENGTH_LONG,
			)
			(host.context.findActivity() as? BottomNavOwner)?.let {
				snackbar.anchorView = it.bottomNav
			}
			val router = AppRouter.from(host)
			if (router != null) {
				snackbar.setAction(R.string.details) { router.openDownloads() }
			}
			snackbar.show()
		}
	}

	companion object {

		private const val RESULT_KEY = "DOWNLOAD_STARTED"
		private const val ARG_STARTED = "started"

		private const val OPTION_WHOLE_MANGA = 0
		private const val OPTION_WHOLE_BRANCH = 1
		private const val OPTION_FIRST_CHAPTERS = 2
		private const val OPTION_UNREAD_CHAPTERS = 3

		fun registerCallback(
			fm: FragmentManager,
			lifecycleOwner: LifecycleOwner,
			snackbarHost: View
		) = fm.setFragmentResultListener(RESULT_KEY, lifecycleOwner, SnackbarResultListener(snackbarHost))

		fun unregisterCallback(fm: FragmentManager) = fm.clearFragmentResultListener(RESULT_KEY)
	}
}
