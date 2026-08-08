package org.koitharu.kotatsu.list.ui.sort

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.ui.sheet.SheetContentPadding
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.databinding.SheetSortOrderBinding
import org.koitharu.kotatsu.settings.compose.DropSauceTheme

/**
 * Sort picker for the Favourites and History lists. Rows work like Mihon's: tapping the active row
 * flips between ascending and descending, tapping another row moves the sort there.
 */
@AndroidEntryPoint
class ListSortSheet : BaseAdaptiveSheet<SheetSortOrderBinding>() {

	private val viewModel by viewModels<ListSortViewModel>()

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = SheetSortOrderBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: SheetSortOrderBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		binding.composeView.setContent {
			DropSauceTheme {
				Content()
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

	@Composable
	private fun Content() {
		val current by viewModel.sortOrder.collectAsState()
		Column(modifier = Modifier.padding(vertical = 8.dp)) {
			viewModel.types.forEach { type ->
				SortRow(
					title = stringResource(type.titleResId),
					isSelected = current.type == type,
					isAscending = current.isAscending,
					onClick = { viewModel.onTypeClick(type) },
				)
			}
		}
	}
}

@Composable
private fun SortRow(
	title: String,
	isSelected: Boolean,
	isAscending: Boolean,
	onClick: () -> Unit,
) {
	val color = if (isSelected) {
		MaterialTheme.colorScheme.primary
	} else {
		MaterialTheme.colorScheme.onSurfaceVariant
	}
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(min = 56.dp)
			.clip(RoundedCornerShape(16.dp))
			.clickable(onClick = onClick)
			.padding(horizontal = SheetContentPadding),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.bodyLarge,
			color = color,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.weight(1f),
		)
		Spacer(Modifier.size(16.dp))
		// The arrow keeps its slot when unselected so the labels don't shift as the sort moves.
		Icon(
			painter = painterResource(R.drawable.ic_arrow_up),
			contentDescription = null,
			tint = color,
			modifier = Modifier
				.size(24.dp)
				.alpha(if (isSelected) 1f else 0f)
				.rotate(if (isAscending) 0f else 180f),
		)
	}
}
