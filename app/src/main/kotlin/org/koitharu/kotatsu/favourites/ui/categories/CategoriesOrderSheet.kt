package org.koitharu.kotatsu.favourites.ui.categories

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.databinding.SheetSortOrderBinding
import org.koitharu.kotatsu.favourites.domain.model.CategoriesOrder
import org.koitharu.kotatsu.list.ui.sort.SortRow
import org.koitharu.kotatsu.settings.compose.DropSauceTheme

/** Order of the favourite categories themselves, opened from the categories manager. */
@AndroidEntryPoint
class CategoriesOrderSheet : BaseAdaptiveSheet<SheetSortOrderBinding>() {

	private val viewModel by viewModels<CategoriesOrderViewModel>()

	override fun onCreateViewBinding(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = SheetSortOrderBinding.inflate(inflater, container, false)

	override fun onViewBindingCreated(binding: SheetSortOrderBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.headerBar.setTitle(R.string.category_order)
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
		val current by viewModel.order.collectAsState()
		Column(modifier = Modifier.padding(vertical = 8.dp)) {
			CategoriesOrder.Type.entries.forEach { type ->
				SortRow(
					title = stringResource(type.titleResId),
					isSelected = current.type == type,
					isAscending = current.isAscending,
					hasDirection = type != CategoriesOrder.Type.RANDOM,
					onClick = { viewModel.onTypeClick(type) },
				)
			}
		}
	}
}
