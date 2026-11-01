package org.koitharu.kotatsu.download.ui.dialog

import android.os.Bundle
import android.view.View
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentResultListener
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.DownloadFormat
import org.koitharu.kotatsu.core.ui.ComposeAlertDialogFragment
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerAction
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveOptionGroupItem
import org.koitharu.kotatsu.core.ui.dialog.ExpressivePillButton
import org.koitharu.kotatsu.core.ui.dialog.showComposeDialog
import org.koitharu.kotatsu.core.ui.dialog.expressiveGroupItemShape
import org.koitharu.kotatsu.core.ui.sheet.SheetSelectorField
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.joinToStringWithLimit
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.main.ui.owners.BottomNavOwner
import org.koitharu.kotatsu.parsers.util.format

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
		val colors = MaterialTheme.colorScheme
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

		val optionEntries = buildList {
			add(OPTION_WHOLE_MANGA)
			if (options.wholeBranch != null) add(OPTION_WHOLE_BRANCH)
			if (options.firstChapters != null) add(OPTION_FIRST_CHAPTERS)
			if (options.unreadChapters != null) add(OPTION_UNREAD_CHAPTERS)
		}

		ExpressiveDialogCard(
			icon = painterResource(R.drawable.ic_download),
			title = stringResource(R.string.download),
			message = summary,
			cornerAction = DialogCornerAction(
				icon = painterResource(R.drawable.ic_close),
				label = stringResource(android.R.string.cancel),
				onClick = { dialog?.cancel() },
			),
			footer = {
				ExpressivePillButton(
					text = stringResource(R.string.start),
					icon = painterResource(R.drawable.ic_download),
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
			},
		) {
			Column(modifier = Modifier.animateContentSize()) {
				optionEntries.forEachIndexed { index, optionId ->
					when (optionId) {
						OPTION_WHOLE_MANGA -> OptionRow(
							index = index,
							count = optionEntries.size,
							selected = selectedOption == OPTION_WHOLE_MANGA,
							title = stringResource(R.string.download_option_whole_manga),
							subtitle = options.wholeManga.chaptersCount
								.takeIf { it > 0 }
								?.let { context.resources.getQuantityStringSafe(R.plurals.chapters, it, it) },
							onSelect = { selectedOption = OPTION_WHOLE_MANGA },
						)

						OPTION_WHOLE_BRANCH -> options.wholeBranch?.let { branch ->
							val keys = branch.branches.keys.toList()
							OptionRow(
								index = index,
								count = optionEntries.size,
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

						OPTION_FIRST_CHAPTERS -> options.firstChapters?.let { first ->
							val values = chaptersCount(first.maxAvailableCount).toList()
							OptionRow(
								index = index,
								count = optionEntries.size,
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

						OPTION_UNREAD_CHAPTERS -> options.unreadChapters?.let { unread ->
							val values = chaptersCount(unread.maxAvailableCount).toList()
							OptionRow(
								index = index,
								count = optionEntries.size,
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
					}
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
							.padding(horizontal = 4.dp, vertical = 8.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Icon(
							painter = painterResource(R.drawable.ic_tap),
							contentDescription = null,
							tint = colors.onSurfaceVariant,
							modifier = Modifier.size(18.dp),
						)
						Spacer(Modifier.width(8.dp))
						Text(
							text = stringResource(R.string.chapter_selection_hint),
							style = MaterialTheme.typography.bodySmall,
							color = colors.onSurfaceVariant,
						)
					}
				} else {
					Spacer(Modifier.height(10.dp))
				}

				// Start download now + More options paired as a second expressive group
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.heightIn(min = 52.dp)
						.clip(expressiveGroupItemShape(0, 2))
						.background(colors.background)
						.clickable { startNow = !startNow }
						.padding(horizontal = 14.dp, vertical = 4.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = stringResource(R.string.start_download),
						style = MaterialTheme.typography.bodyLarge,
						color = colors.onSurface,
						modifier = Modifier.weight(1f),
					)
					Switch(checked = startNow, onCheckedChange = { startNow = it })
				}

				Column(
					modifier = Modifier
						.padding(top = 2.dp)
						.fillMaxWidth()
						.clip(expressiveGroupItemShape(1, 2))
						.background(colors.background),
				) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.heightIn(min = 52.dp)
							.clickable { moreExpanded = !moreExpanded }
							.padding(horizontal = 14.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = stringResource(R.string.more_options),
							style = MaterialTheme.typography.bodyLarge,
							color = colors.primary,
							modifier = Modifier.weight(1f),
						)
						Icon(
							painter = painterResource(R.drawable.ic_expand_more),
							contentDescription = null,
							tint = colors.primary,
							modifier = Modifier
								.padding(start = 8.dp)
								.size(24.dp)
								.rotate(
									animateFloatAsState(
										targetValue = if (moreExpanded) 180f else 0f,
										label = "moreOptionsExpand",
									).value,
								),
						)
					}
					if (moreExpanded) {
						Column(
							modifier = Modifier.padding(
								start = 12.dp,
								end = 12.dp,
								bottom = 12.dp,
							),
						) {
							SelectorField(
								label = stringResource(R.string.destination_directory),
								current = destinations.getOrNull(destinationIndex)
									?.let { it.title ?: stringResource(it.titleRes) } ?: "",
								items = destinations.map { it.title ?: stringResource(it.titleRes) },
								onSelect = { destinationIndex = it },
							)
							Spacer(Modifier.height(8.dp))
							SelectorField(
								label = stringResource(R.string.preferred_download_format),
								current = formatLabels.getOrNull(formatIndex) ?: "",
								items = formatLabels.toList(),
								onSelect = { formatIndex = it },
							)
						}
					}
				}
			}
		}
	}

	@Composable
	private fun OptionRow(
		index: Int,
		count: Int,
		selected: Boolean,
		title: String,
		subtitle: String?,
		onSelect: () -> Unit,
		selectorLabel: String? = null,
		selectorItems: List<String> = emptyList(),
		onSelectItem: (Int) -> Unit = {},
	) {
		val colors = MaterialTheme.colorScheme
		ExpressiveOptionGroupItem(
			index = index,
			count = count,
			onClick = onSelect,
		) {
			RadioButton(selected = selected, onClick = null)
			Spacer(Modifier.width(12.dp))
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = title,
					style = MaterialTheme.typography.bodyLarge,
					fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
					color = if (selected) colors.primary else colors.onSurface,
				)
				if (!subtitle.isNullOrEmpty()) {
					Text(
						text = subtitle,
						style = MaterialTheme.typography.bodySmall,
						color = if (selected) {
							colors.primary.copy(alpha = 0.8f)
						} else {
							colors.onSurfaceVariant
						},
					)
				}
			}
			if (selected && selectorLabel != null && selectorItems.isNotEmpty()) {
				Box {
					var expanded by remember { mutableStateOf(false) }
					TextButton(onClick = { expanded = true }) { Text(selectorLabel) }
					DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
						selectorItems.forEachIndexed { itemIndex, label ->
							DropdownMenuItem(
								text = { Text(label) },
								onClick = {
									expanded = false
									onSelectItem(itemIndex)
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
		Column {
			Text(
				text = label,
				style = MaterialTheme.typography.titleSmall,
				color = MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.padding(bottom = 4.dp),
			)
			SheetSelectorField(current = current, items = items, onSelect = onSelect)
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
		val context = context ?: return
		val message = e.getDisplayMessage(resources)
		showComposeDialog(context) { close ->
			ExpressiveDialogCard(
				icon = painterResource(R.drawable.ic_error_small),
				title = stringResource(R.string.error),
				message = message,
				cornerAction = DialogCornerAction(
					icon = painterResource(R.drawable.ic_close),
					label = stringResource(R.string.close),
					onClick = close,
				),
			)
		}
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
			snackbarHost: View,
		) = fm.setFragmentResultListener(RESULT_KEY, lifecycleOwner, SnackbarResultListener(snackbarHost))

		fun unregisterCallback(fm: FragmentManager) = fm.clearFragmentResultListener(RESULT_KEY)
	}
}
