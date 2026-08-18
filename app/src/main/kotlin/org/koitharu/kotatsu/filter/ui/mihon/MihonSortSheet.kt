package org.koitharu.kotatsu.filter.ui.mihon

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.updatePaddingRelative
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.transition.ChangeBounds
import androidx.transition.TransitionManager
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.consumeAll
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.ItemSortOptionBinding
import org.koitharu.kotatsu.databinding.ItemSortSectionBinding
import org.koitharu.kotatsu.databinding.SheetSortBinding
import org.koitharu.kotatsu.filter.ui.FilterCoordinator
import org.koitharu.kotatsu.filter.ui.mihon.model.SortOptionModel
import org.koitharu.kotatsu.filter.ui.mihon.model.SortSectionModel
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.model.ListModel

@AndroidEntryPoint
class MihonSortSheet : BaseAdaptiveSheet<SheetSortBinding>() {

	private val viewModel by viewModels<MihonSortViewModel>(
		extrasProducer = {
			defaultViewModelCreationExtras.withCreationCallback<MihonSortViewModel.Factory> { factory ->
				factory.create(FilterCoordinator.require(this))
			}
		},
	)

	private var isSectionAnimationPending = false

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetSortBinding {
		return SheetSortBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetSortBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		val adapter = BaseListAdapter<ListModel>()
			.addDelegate(ListItemType.MIHON_SORT_OPTION, sortOptionDelegate(viewModel::onOptionClick))
			.addDelegate(ListItemType.MIHON_SORT_SECTION, sortSectionDelegate(::toggleSection))
		binding.recyclerView.layoutManager = LinearLayoutManager(binding.root.context)
		// The rows are revealed by the growing sheet, so the per-item animator would only fight it.
		binding.recyclerView.itemAnimator = null
		binding.recyclerView.adapter = adapter
		// The list wraps its content inside a wrap_content sheet: adding rows resizes the sheet in a
		// single layout pass. Capturing the bounds here — after the differ has notified the adapter
		// but before that layout runs — is what turns the jump into a slide.
		adapter.addListListener { _, _ ->
			if (isSectionAnimationPending) {
				isSectionAnimationPending = false
				sceneRoot()?.let { TransitionManager.beginDelayedTransition(it, ChangeBounds().setDuration(250)) }
			}
		}
		viewModel.content.observe(viewLifecycleOwner, adapter)
	}

	private fun toggleSection() {
		isSectionAnimationPending = true
		viewModel.onSectionClick()
	}

	/**
	 * The bottom/side sheet container itself changes height, so the transition has to be rooted above
	 * it — animating inside the content view would leave the sheet edge snapping.
	 */
	private fun sceneRoot(): ViewGroup? {
		val content = viewBinding?.root ?: return null
		val container = content.parent as? ViewGroup ?: return null
		return container.parent as? ViewGroup ?: container
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		viewBinding?.recyclerView?.setPadding(barsInsets.left, 0, barsInsets.right, barsInsets.bottom)
		return insets.consumeAll(typeMask)
	}
}

private fun sortOptionDelegate(
	onClick: (SortOptionModel) -> Unit,
) = adapterDelegateViewBinding<SortOptionModel, ListModel, ItemSortOptionBinding>(
	{ inflater, parent -> ItemSortOptionBinding.inflate(inflater, parent, false) },
) {
	binding.layoutRoot.setOnClickListener { onClick(item) }
	val paddingStart = binding.layoutRoot.paddingStart
	val paddingStartNested = paddingStart + context.resources.getDimensionPixelOffset(R.dimen.margin_normal)
	bind {
		binding.layoutRoot.updatePaddingRelative(start = if (item.isInApp) paddingStart else paddingStartNested)
		binding.textViewTitle.text = item.title
		binding.textViewTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(item.iconResId, 0, 0, 0)
		when (item.indicator) {
			SortOptionModel.Indicator.NONE -> binding.imageViewArrow.isInvisible = true
			SortOptionModel.Indicator.ASCENDING -> {
				binding.imageViewArrow.isInvisible = false
				binding.imageViewArrow.setImageResource(R.drawable.ic_arrow_up)
				binding.imageViewArrow.rotation = 0f
			}

			SortOptionModel.Indicator.DESCENDING -> {
				binding.imageViewArrow.isInvisible = false
				binding.imageViewArrow.setImageResource(R.drawable.ic_arrow_up)
				binding.imageViewArrow.rotation = 180f
			}

			SortOptionModel.Indicator.SELECTED -> {
				binding.imageViewArrow.isInvisible = false
				binding.imageViewArrow.setImageResource(R.drawable.ic_check)
				binding.imageViewArrow.rotation = 0f
			}
		}
	}
}

private fun sortSectionDelegate(
	onClick: () -> Unit,
) = adapterDelegateViewBinding<SortSectionModel, ListModel, ItemSortSectionBinding>(
	{ inflater, parent -> ItemSortSectionBinding.inflate(inflater, parent, false) },
) {
	binding.layoutRoot.setOnClickListener { onClick() }
	bind {
		binding.textViewTitle.text = item.title
		binding.imageViewChevron.rotation = if (item.isExpanded) 180f else 0f
	}
}
