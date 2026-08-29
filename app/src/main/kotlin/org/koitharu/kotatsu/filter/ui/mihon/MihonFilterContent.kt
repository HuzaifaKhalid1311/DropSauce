package org.koitharu.kotatsu.filter.ui.mihon

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.model.Filter
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.sheet.SheetChip
import org.koitharu.kotatsu.core.ui.sheet.SheetChips
import org.koitharu.kotatsu.core.ui.sheet.SheetContentPadding
import org.koitharu.kotatsu.core.ui.sheet.SheetSelectorField
import org.koitharu.kotatsu.filter.ui.mihon.model.MihonFilterItem

/** Left inset added per nesting level, matching the old adapter's indentation. */
private val IndentStep = 16.dp

/**
 * The dynamic per-extension filter list. Recycles through a [LazyColumn] because a source's filter
 * tree can hold thousands of options (e.g. publisher taxonomies).
 *
 * [onContentHeight] reports the pixel height the list needs when every row fits on screen, or null
 * when it doesn't — the sheet uses it to shrink its resting height for short filter sets.
 */
@Composable
fun MihonFilterContent(
	items: List<MihonFilterItem>,
	isLoading: Boolean,
	isEmpty: Boolean,
	listener: MihonFilterListener,
	contentPadding: PaddingValues,
	onContentHeight: (Int?) -> Unit,
) {
	if (isLoading || isEmpty) {
		LaunchedEffect(isLoading, isEmpty) { onContentHeight(null) }
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(SheetContentPadding),
			contentAlignment = Alignment.Center,
		) {
			if (isLoading) {
				CircularProgressIndicator()
			} else {
				Text(
					text = stringResource(R.string.no_filters_available),
					style = MaterialTheme.typography.bodyLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		return
	}
	val listState = rememberLazyListState()
	LazyColumn(
		state = listState,
		// Bridges this Compose scroll into the View nested-scroll chain, so BottomSheetBehavior
		// only takes over a downward drag once the list itself is back at the top. Without it the
		// behaviour grabbed every drag and dragged the sheet shut mid-list.
		modifier = Modifier
			.fillMaxSize()
			.nestedScroll(rememberNestedScrollInteropConnection()),
		contentPadding = contentPadding,
	) {
		items(items.size) { index ->
			FilterRow(items[index], listener)
		}
	}
	ReportContentHeight(listState, items.size, onContentHeight)
}

/**
 * Every row being laid out at once means the whole list fits, and its summed height is what the
 * sheet can shrink to; anything else leaves the sheet at its default half-expanded height.
 */
@Composable
private fun ReportContentHeight(
	listState: LazyListState,
	itemCount: Int,
	onContentHeight: (Int?) -> Unit,
) {
	LaunchedEffect(listState, itemCount) {
		snapshotFlow {
			val info = listState.layoutInfo
			if (itemCount > 0 && info.visibleItemsInfo.size == itemCount) {
				info.visibleItemsInfo.sumOf { it.size }
			} else {
				null
			}
		}.collect(onContentHeight)
	}
}

@Composable
private fun FilterRow(item: MihonFilterItem, listener: MihonFilterListener) {
	val indent = SheetContentPadding + IndentStep * item.depth
	when (item) {
		is MihonFilterItem.Header -> Text(
			text = item.title,
			style = MaterialTheme.typography.titleSmall,
			color = MaterialTheme.colorScheme.primary,
			modifier = Modifier.padding(start = indent, end = SheetContentPadding, top = 16.dp, bottom = 4.dp),
		)

		is MihonFilterItem.Separator -> HorizontalDivider(
			modifier = Modifier.padding(horizontal = SheetContentPadding, vertical = 8.dp),
		)

		is MihonFilterItem.CheckBox -> FilterRowContainer(
			indent = indent,
			onClick = { listener.onCheckBoxClick(item) },
		) {
			Checkbox(checked = item.isChecked, onCheckedChange = { listener.onCheckBoxClick(item) })
			Spacer(Modifier.size(8.dp))
			RowLabel(item.title)
		}

		is MihonFilterItem.TriState -> FilterRowContainer(
			indent = indent,
			onClick = { listener.onTriStateClick(item) },
		) {
			Icon(
				painter = painterResource(
					when (item.state) {
						Filter.TriState.STATE_INCLUDE -> R.drawable.ic_filter_tri_include
						Filter.TriState.STATE_EXCLUDE -> R.drawable.ic_filter_tri_exclude
						else -> R.drawable.ic_filter_tri_ignore
					},
				),
				contentDescription = null,
				tint = if (item.state == Filter.TriState.STATE_IGNORE) {
					MaterialTheme.colorScheme.onSurfaceVariant
				} else {
					MaterialTheme.colorScheme.primary
				},
				modifier = Modifier.size(24.dp),
			)
			Spacer(Modifier.size(16.dp))
			RowLabel(item.title)
		}

		is MihonFilterItem.Text -> {
			// Held locally while typing and pushed to the filter on Done or focus loss, so a keystroke
			// doesn't rebuild the whole filter tree — the same commit points as the old EditText.
			var value by remember(item.path) { mutableStateOf(item.value) }
			OutlinedTextField(
				value = value,
				onValueChange = { value = it },
				label = { Text(item.title) },
				singleLine = true,
				shape = RoundedCornerShape(16.dp),
				keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
				keyboardActions = KeyboardActions(onDone = { listener.onTextChanged(item, value) }),
				modifier = Modifier
					.fillMaxWidth()
					.padding(start = indent, end = indent, top = 4.dp, bottom = 4.dp)
					.onFocusChanged { if (!it.isFocused) listener.onTextChanged(item, value) },
			)
		}

		is MihonFilterItem.Select -> Column(
			modifier = Modifier.padding(start = indent, end = indent, top = 8.dp, bottom = 4.dp),
		) {
			Text(
				text = item.title,
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(bottom = 4.dp),
			)
			SheetSelectorField(
				current = item.options.getOrNull(item.selectedIndex).orEmpty(),
				items = item.options,
				onSelect = { index -> listener.onSelectChanged(item, index) },
			)
		}

		is MihonFilterItem.CheckBoxChips -> SheetChips(
			chips = item.chips.map { SheetChip(title = it.title, isChecked = it.checked) },
			onClick = { index ->
				item.chips.getOrNull(index)?.let { listener.onCheckBoxChipClick(it.path) }
			},
			modifier = Modifier.padding(start = indent, end = SheetContentPadding, top = 4.dp, bottom = 4.dp),
		)

		is MihonFilterItem.ExpandableHeader -> FilterRowContainer(
			indent = indent,
			onClick = { listener.onExpandClick(item) },
		) {
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = item.title,
					style = MaterialTheme.typography.bodyLarge,
					color = MaterialTheme.colorScheme.onSurface,
				)
				val summary = item.activeSummary
				if (!summary.isNullOrEmpty()) {
					Text(
						text = summary,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.primary,
					)
				}
			}
			Icon(
				painter = painterResource(R.drawable.ic_expand_more),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier
					.padding(start = 8.dp)
					.size(24.dp)
					.rotate(animateFloatAsState(if (item.isExpanded) 180f else 0f, label = "expand").value),
			)
		}

		is MihonFilterItem.SortOption -> FilterRowContainer(
			indent = indent,
			onClick = { listener.onSortOptionClick(item) },
		) {
			// The unselected options carry a dot so the selected row's arrow reads as a direction
			// rather than as the only decorated row.
			val isAscending = item.isAscending
			if (isAscending == null) {
				Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
					Box(
						modifier = Modifier
							.size(6.dp)
							.clip(CircleShape)
							.background(MaterialTheme.colorScheme.outlineVariant),
					)
				}
			} else {
				Icon(
					painter = painterResource(R.drawable.ic_arrow_up),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier
						.size(24.dp)
						.rotate(if (isAscending) 0f else 180f),
				)
			}
			Spacer(Modifier.size(16.dp))
			RowLabel(item.title)
		}
	}
}

/** Tappable list row honouring the item's [indent]. */
@Composable
private fun FilterRowContainer(
	indent: Dp,
	onClick: () -> Unit,
	content: @Composable RowScope.() -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(min = 52.dp)
			.clickable(onClick = onClick)
			.padding(start = indent, end = SheetContentPadding, top = 4.dp, bottom = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		content = content,
	)
}

@Composable
private fun RowScope.RowLabel(text: String) {
	Text(
		text = text,
		style = MaterialTheme.typography.bodyLarge,
		color = MaterialTheme.colorScheme.onSurface,
		modifier = Modifier.weight(1f),
	)
}
