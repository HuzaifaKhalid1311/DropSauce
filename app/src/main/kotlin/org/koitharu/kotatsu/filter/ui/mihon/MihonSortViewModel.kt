package org.koitharu.kotatsu.filter.ui.mihon

import android.content.Context
import androidx.annotation.DrawableRes
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.model.titleRes
import org.koitharu.kotatsu.filter.ui.FilterCoordinator
import org.koitharu.kotatsu.filter.ui.FilterCoordinator.SortState
import org.koitharu.kotatsu.filter.ui.mihon.model.SortOptionModel
import org.koitharu.kotatsu.filter.ui.mihon.model.SortSectionModel
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.SortOrder

@HiltViewModel(assistedFactory = MihonSortViewModel.Factory::class)
class MihonSortViewModel @AssistedInject constructor(
	@Assisted private val filter: FilterCoordinator,
	@ApplicationContext private val context: Context,
) : BaseViewModel() {

	private var sortState: SortState? = null

	// The source's own options start visible only when one of them is what's currently applied.
	private var isSourceSectionExpanded = false
	private val contentFlow = MutableStateFlow<List<ListModel>>(emptyList())

	val content: StateFlow<List<ListModel>> = contentFlow

	init {
		launchLoadingJob(Dispatchers.Default) {
			val state = filter.loadSortState()
			sortState = state
			isSourceSectionExpanded = state.source != null && state.inAppSelected == null
			rebuild()
		}
	}

	fun onOptionClick(model: SortOptionModel) {
		val state = sortState ?: return
		if (model.isInApp) {
			val order = state.inAppOptions.firstOrNull { it.ordinal == model.id } ?: return
			filter.applyInAppSort(order)
			sortState = state.copy(
				inAppSelected = order,
				source = state.source?.copy(selectedIndex = -1, isAscending = false),
			)
			rebuild()
			return
		}
		val source = state.source ?: return
		val index = model.id
		if (source.supportsDirection) {
			// Tapping the selected option flips direction; a new option keeps ascending.
			val ascending = if (index == source.selectedIndex) !source.isAscending else true
			filter.applySourceSort(index, ascending)
			sortState = state.copy(
				inAppSelected = null,
				source = source.copy(selectedIndex = index, isAscending = ascending),
			)
		} else {
			filter.applySourceSort(index, false)
			sortState = state.copy(inAppSelected = null, source = source.copy(selectedIndex = index))
		}
		rebuild()
	}

	fun onSectionClick() {
		isSourceSectionExpanded = !isSourceSectionExpanded
		rebuild()
	}

	private fun rebuild() {
		val state = sortState ?: return
		val source = state.source
		contentFlow.value = buildList {
			state.inAppOptions.forEach { order ->
				add(
					SortOptionModel(
						id = order.ordinal,
						title = context.getString(order.pickerTitleRes()),
						indicator = if (order == state.inAppSelected) {
							SortOptionModel.Indicator.SELECTED
						} else {
							SortOptionModel.Indicator.NONE
						},
						isInApp = true,
						iconResId = order.pickerIconRes(),
					),
				)
			}
			if (source == null) {
				return@buildList
			}
			add(
				SortSectionModel(
					title = context.getString(R.string.sort_section_source),
					isExpanded = isSourceSectionExpanded,
				),
			)
			if (!isSourceSectionExpanded) {
				return@buildList
			}
			source.options.forEachIndexed { index, label ->
				add(
					SortOptionModel(
						id = index,
						title = label,
						indicator = when {
							index != source.selectedIndex -> SortOptionModel.Indicator.NONE
							!source.supportsDirection -> SortOptionModel.Indicator.SELECTED
							source.isAscending -> SortOptionModel.Indicator.ASCENDING
							else -> SortOptionModel.Indicator.DESCENDING
						},
						isInApp = false,
					),
				)
			}
		}
	}

	@DrawableRes
	private fun SortOrder.pickerIconRes(): Int = when {
		!filter.isDynamicFilter -> 0
		this == SortOrder.UPDATED -> R.drawable.ic_sort_latest
		this == SortOrder.POPULARITY -> R.drawable.ic_sort_popular
		else -> 0
	}

	/** "Latest" reads better than the library-wide "Updated" for the in-app listing of a source. */
	private fun SortOrder.pickerTitleRes(): Int = when {
		this == SortOrder.UPDATED && filter.isDynamicFilter -> R.string.latest
		else -> titleRes
	}

	@AssistedFactory
	interface Factory {
		fun create(filter: FilterCoordinator): MihonSortViewModel
	}
}
