package org.koitharu.kotatsu.favourites.ui.categories

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
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
import org.koitharu.kotatsu.list.ui.sort.SortRow
import org.koitharu.kotatsu.settings.compose.DropSauceTheme

/**
 * Default sort for favourite categories, opened from the categories manager. Looks and taps like the
 * favourites sort sheet, with a note up top since a pick rewrites every category's own sort.
 */
@AndroidEntryPoint
class CategoriesSortSheet : BaseAdaptiveSheet<SheetSortOrderBinding>() {

	private val viewModel by viewModels<CategoriesSortViewModel>()

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = SheetSortOrderBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: SheetSortOrderBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.headerBar.setTitle(R.string.default_category_sort)
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
			Text(
				text = stringResource(R.string.default_category_sort_summary),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(start = SheetContentPadding, end = SheetContentPadding, bottom = 8.dp),
			)
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
