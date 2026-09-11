package org.koitharu.kotatsu.favourites.ui.categories.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.list.domain.ListSortOrder
import org.koitharu.kotatsu.list.ui.sort.SortRow
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem

/**
 * Content of [FavouritesCategoryEditActivity]. The activity still owns the toolbar and its Save
 * button; everything below it lives here.
 */
@Composable
fun FavouritesCategoryEditScreen(
	name: String,
	onNameChange: (String) -> Unit,
	sortOrder: ListSortOrder,
	onSortOrderChange: (ListSortOrder) -> Unit,
	isTrackerSectionVisible: Boolean,
	isTrackerEnabled: Boolean,
	onTrackerChange: (Boolean) -> Unit,
	isDownloadEnabled: Boolean,
	onDownloadChange: (Boolean) -> Unit,
	isShelfEnabled: Boolean,
	onShelfChange: (Boolean) -> Unit,
	error: String?,
	enabled: Boolean,
	bottomInset: Dp,
) {
	var isSortSheetVisible by rememberSaveable { mutableStateOf(false) }

	Column(
		modifier = Modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(start = 16.dp, end = 16.dp, top = 8.dp)
			.padding(bottom = bottomInset + 24.dp),
	) {
		OutlinedTextField(
			value = name,
			onValueChange = { if (it.length <= NAME_MAX_LENGTH) onNameChange(it) },
			modifier = Modifier.fillMaxWidth(),
			label = { Text(stringResource(R.string.name)) },
			singleLine = true,
			enabled = enabled,
			shape = RoundedCornerShape(20.dp),
			keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
		)
		Spacer(Modifier.height(16.dp))
		SettingsItem(
			title = stringResource(R.string.sort_order),
			subtitle = sortOrder.displayLabel(),
			icon = R.drawable.ic_sort,
			shape = RoundedCornerShape(24.dp),
			enabled = enabled,
			modifier = Modifier.fillMaxWidth(),
			onClick = { isSortSheetVisible = true },
		)
		Spacer(Modifier.height(8.dp))
		SettingsGroup(
			modifier = Modifier.fillMaxWidth(),
			title = stringResource(R.string.options),
		) {
			if (isTrackerSectionVisible) {
				item { position ->
					SwitchSettingsItem(
						title = stringResource(R.string.check_for_new_chapters),
						subtitle = stringResource(R.string.category_tracker_summary),
						icon = R.drawable.ic_updated,
						checked = isTrackerEnabled,
						onCheckedChange = onTrackerChange,
						shape = position.shape,
						enabled = enabled,
						modifier = Modifier.fillMaxWidth(),
					)
				}
				item { position ->
					SwitchSettingsItem(
						title = stringResource(R.string.download_new_chapters),
						subtitle = stringResource(R.string.category_download_summary),
						icon = R.drawable.ic_download,
						checked = isDownloadEnabled,
						onCheckedChange = onDownloadChange,
						shape = position.shape,
						// Downloading new chapters is meaningless while the category is not tracked.
						enabled = enabled && isTrackerEnabled,
						modifier = Modifier.fillMaxWidth(),
					)
				}
			}
			item { position ->
				SwitchSettingsItem(
					title = stringResource(R.string.show_on_shelf),
					subtitle = stringResource(R.string.category_shelf_summary),
					icon = R.drawable.ic_visibility,
					checked = isShelfEnabled,
					onCheckedChange = onShelfChange,
					shape = position.shape,
					enabled = enabled,
					modifier = Modifier.fillMaxWidth(),
				)
			}
		}
		if (!error.isNullOrEmpty()) {
			Spacer(Modifier.height(16.dp))
			Text(
				text = error,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.error,
			)
		}
	}

	if (isSortSheetVisible) {
		SortOrderSheet(
			current = sortOrder,
			onSelect = onSortOrderChange,
			onDismiss = { isSortSheetVisible = false },
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortOrderSheet(
	current: ListSortOrder,
	onSelect: (ListSortOrder) -> Unit,
	onDismiss: () -> Unit,
) {
	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
		shape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER),
		containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
	) {
		Column(Modifier.padding(bottom = 24.dp)) {
			Text(
				text = stringResource(R.string.sort_order),
				style = MaterialTheme.typography.headlineSmall,
				fontWeight = FontWeight.SemiBold,
				modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
			)
			ListSortOrder.FAVORITES.forEach { type ->
				SortRow(
					title = stringResource(type.titleResId),
					isSelected = current.type == type,
					isAscending = current.isAscending,
					// Mihon's rule: tapping the active column flips direction, tapping another keeps it.
					onClick = {
						val isAscending = if (current.type == type) !current.isAscending else current.isAscending
						onSelect(type.toSortOrder(isAscending))
					},
				)
			}
		}
	}
}

/** "Date added (descending)" — the direction has to be spelled out wherever the arrow isn't shown. */
@Composable
private fun ListSortOrder.displayLabel(): String = stringResource(
	if (isAscending) R.string.sort_ascending else R.string.sort_descending,
	stringResource(titleResId),
)

private const val NAME_MAX_LENGTH = 120
private val SHEET_CORNER = 28.dp
