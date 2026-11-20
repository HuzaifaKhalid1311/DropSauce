package org.koitharu.kotatsu.list.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.ui.sheet.SheetContentPadding
import org.koitharu.kotatsu.core.ui.sheet.SheetRows
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.databinding.SheetListModeBinding
import org.koitharu.kotatsu.list.ui.GridColumns
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.PreviewChoiceRow
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SliderSettingsItem
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.listModePreviewOptions

@AndroidEntryPoint
class ListConfigBottomSheet : BaseAdaptiveSheet<SheetListModeBinding>() {

	private val viewModel by viewModels<ListConfigViewModel>()

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = SheetListModeBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: SheetListModeBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.headerBar.setCompactDragHandle()
		binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		binding.composeView.setContent {
			DropSauceTheme {
				SheetRows {
					Content()
				}
			}
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		viewBinding?.scrollView?.updatePadding(
			bottom = insets.getInsets(typeMask).bottom,
		)
		return insets.consume(v, typeMask, bottom = true)
	}

	// Built from the same rows as Settings > Appearance, so the two always look alike.
	@Composable
	private fun Content() {
		// The view model exposes plain settings-backed properties rather than flows, so the sheet holds
		// its own state and writes straight through on change.
		var mode by remember { mutableStateOf(viewModel.listMode) }
		var isTitleOverCover by remember { mutableStateOf(viewModel.isTitleOverCover) }
		var isGridSpacingIncreased by remember { mutableStateOf(viewModel.isGridSpacingIncreased) }
		val gridWidth = remember { GridColumns.referenceWidth(resources) }
		val maxColumns = remember { GridColumns.maxColumns(resources, gridWidth) }
		var columns by remember {
			mutableIntStateOf(GridColumns.columnsFor(resources, gridWidth, viewModel.gridSize / 100f))
		}
		var isGroupingEnabled by remember { mutableStateOf(viewModel.isGroupingEnabled) }

		Column(
			modifier = Modifier
				.padding(horizontal = SheetContentPadding)
				.padding(top = 4.dp, bottom = 16.dp),
		) {
			// The tiles sit straight on the sheet: a card behind them only added a box around boxes.
			SettingsGroup(title = stringResource(R.string.list_mode)) {
				item {
					PreviewChoiceRow(
						options = listModePreviewOptions(LIST_MODES.map { stringResource(it.second) }),
						selectedIndex = LIST_MODES.indexOfFirst { it.first == mode }.coerceAtLeast(0),
						onSelected = { index ->
							val value = LIST_MODES[index].first
							mode = value
							viewModel.listMode = value
						},
					)
				}
			}
			if (viewModel.isGroupingSupported) {
				SwitchSettingsItem(
					title = stringResource(R.string.group),
					checked = isGroupingEnabled,
					onCheckedChange = {
						isGroupingEnabled = it
						viewModel.isGroupingEnabled = it
					},
					modifier = Modifier.padding(top = 12.dp),
					icon = R.drawable.ic_list_group,
					shape = RoundedCornerShape(24.dp),
					enabled = viewModel.isGroupingAvailable,
				)
			}

			// Cover options only mean something in the two cover-based modes; in the list modes they
			// stay where they are, greyed out, so the sheet keeps its height.
			val isGridMode = mode == ListMode.GRID || mode == ListMode.COVER_ONLY
			SettingsGroup(title = stringResource(R.string.grid)) {
				item { pos ->
					// Bigger covers to the right: the rightmost stop is one per row.
					SliderSettingsItem(
						title = stringResource(R.string.grid_size),
						value = maxColumns + 1 - columns,
						valueFrom = 1,
						valueTo = maxColumns,
						stepSize = 1,
						valueLabel = { position ->
							val count = maxColumns + 1 - position
							resources.getQuantityString(R.plurals.items_per_row, count, count)
						},
						// Reported once the thumb settles, so the list behind doesn't re-lay out
						// mid-drag. The saved size is left alone unless the count really changed.
						onValueChange = { position ->
							val count = maxColumns + 1 - position
							if (count != columns) {
								columns = count
								viewModel.gridSize = GridColumns.gridSizeFor(resources, gridWidth, count)
							}
						},
						icon = R.drawable.ic_grid,
						shape = pos.shape,
						enabled = isGridMode,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.title_over_cover),
						checked = isTitleOverCover,
						onCheckedChange = {
							isTitleOverCover = it
							viewModel.isTitleOverCover = it
						},
						icon = R.drawable.ic_title,
						shape = pos.shape,
						enabled = isGridMode,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.increase_cover_spacing),
						checked = isGridSpacingIncreased,
						onCheckedChange = {
							isGridSpacingIncreased = it
							viewModel.isGridSpacingIncreased = it
						},
						icon = R.drawable.ic_images,
						shape = pos.shape,
						enabled = isGridMode,
					)
				}
			}
		}
	}

	private companion object {

		/** Modes in display order, each with its label. */
		val LIST_MODES = listOf(
			ListMode.LIST to R.string.compact,
			ListMode.DETAILED_LIST to R.string.details,
			ListMode.GRID to R.string.grid,
			ListMode.COVER_ONLY to R.string.cover_only,
		)
	}
}
