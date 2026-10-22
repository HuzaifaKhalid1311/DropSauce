package org.koitharu.kotatsu.settings.tracker.categories

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.SheetTrackerCategoriesBinding

@AndroidEntryPoint
class TrackerCategoriesConfigSheet : BaseAdaptiveSheet<SheetTrackerCategoriesBinding>() {

	private val viewModel by viewModels<TrackerCategoriesConfigViewModel>()

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetTrackerCategoriesBinding {
		return SheetTrackerCategoriesBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetTrackerCategoriesBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.headerBar.setTitle(R.string.favourites_categories)
		binding.chips.onChipClickListener = ChipsView.OnChipClickListener { _, data ->
			(data as? FavouriteCategory)?.let(viewModel::toggleItem)
		}
		viewModel.content.observe(viewLifecycleOwner) { categories ->
			binding.chips.setChips(
				categories.map {
					ChipsView.ChipModel(title = it.title, isChecked = it.isTrackingEnabled, data = it)
				},
			)
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		viewBinding?.scrollView?.updatePadding(
			bottom = insets.getInsets(typeMask).bottom,
		)
		return insets.consume(v, typeMask, bottom = true)
	}
}
