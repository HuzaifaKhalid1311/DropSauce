package org.koitharu.kotatsu.main.ui.welcome

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.titleResId
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.getDisplayName
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.tryLaunch
import org.koitharu.kotatsu.databinding.SheetWelcomeBinding
import org.koitharu.kotatsu.filter.ui.model.FilterProperty
import org.koitharu.kotatsu.parsers.model.ContentType
import java.util.Locale

@AndroidEntryPoint
class WelcomeSheet : BaseAdaptiveSheet<SheetWelcomeBinding>(), ChipsView.OnChipClickListener, View.OnClickListener {

	private val viewModel by viewModels<WelcomeViewModel>()

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetWelcomeBinding {
		return SheetWelcomeBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetWelcomeBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.textViewWelcomeTitle.isGone = resources.getBoolean(R.bool.is_tablet)
		binding.chipsLocales.onChipClickListener = this
		binding.chipsType.onChipClickListener = this
		binding.chipDirectories.setOnClickListener(this)
		binding.chipExtensions.setOnClickListener(this)

		viewModel.locales.observe(viewLifecycleOwner, ::onLocalesChanged)
		viewModel.types.observe(viewLifecycleOwner, ::onTypesChanged)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		viewBinding?.scrollView?.updatePadding(
			bottom = insets.getInsets(typeMask).bottom,
		)
		return insets.consume(v, typeMask, bottom = true)
	}

	override fun onChipClick(chip: Chip, data: Any?) {
		when (data) {
			is ContentType -> viewModel.setTypeChecked(data, !chip.isChecked)
			is Locale -> viewModel.setLocaleChecked(data, !chip.isChecked)
		}
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.chip_directories -> {
				router.openDirectoriesSettings()
			}

			R.id.chip_extensions -> {
				router.openSourcesCatalog()
				dismiss()
			}
		}
	}

	private fun onLocalesChanged(value: FilterProperty<Locale>) {
		val chips = viewBinding?.chipsLocales ?: return
		chips.setChips(
			value.availableItems.map {
				ChipsView.ChipModel(
					title = it.getDisplayName(chips.context),
					isChecked = it in value.selectedItems,
					data = it,
				)
			},
		)
	}

	private fun onTypesChanged(value: FilterProperty<ContentType>) {
		val chips = viewBinding?.chipsType ?: return
		chips.setChips(
			value.availableItems.map {
				ChipsView.ChipModel(
					title = getString(it.titleResId),
					isChecked = it in value.selectedItems,
					data = it,
				)
			},
		)
	}
}
