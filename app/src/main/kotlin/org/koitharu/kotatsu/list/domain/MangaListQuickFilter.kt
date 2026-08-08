package org.koitharu.kotatsu.list.domain

import androidx.collection.ArraySet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.toChipModel
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.list.ui.model.QuickFilter
import org.koitharu.kotatsu.parsers.util.suspendlazy.getOrNull
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy

abstract class MangaListQuickFilter(
	private val settings: AppSettings,
) : QuickFilterListener {

	private val appliedFilter = MutableStateFlow<Set<ListFilterOption>>(emptySet())
	private val availableFilterOptions = suspendLazy {
		getAvailableFilterOptions()
	}

	val appliedOptions
		get() = appliedFilter.asStateFlow()

	/** Off for the updates feed, which groups by day and has no use for a publication-status filter. */
	var isStateFilterEnabled = true

	override fun setFilterOption(option: ListFilterOption, isApplied: Boolean) {
		appliedFilter.value = ArraySet(appliedFilter.value).also {
			if (isApplied) {
				it.addNoConflicts(option)
			} else {
				it.remove(option)
			}
		}
	}

	override fun toggleFilterOption(option: ListFilterOption) {
		if (option is ListFilterOption.State) {
			cycleStateFilter()
			return
		}
		appliedFilter.value = ArraySet(appliedFilter.value).also {
			if (option in it) {
				it.remove(option)
			} else {
				it.addNoConflicts(option)
			}
		}
	}

	override fun clearFilter() {
		appliedFilter.value = emptySet()
	}

	suspend fun filterItem(
		selectedOptions: Set<ListFilterOption>,
	): QuickFilter? {
		if (!settings.isQuickFilterEnabled) {
			return null
		}
		val extraChips = getAdditionalChips(selectedOptions)
		val availableOptions = availableFilterOptions.getOrNull()?.map { option ->
			option.toChipModel(isChecked = option in selectedOptions)
		}.orEmpty()
		val chips = extraChips + listOfNotNull(stateChip(selectedOptions)) + availableOptions
		return if (chips.isNotEmpty()) {
			QuickFilter(chips)
		} else {
			null
		}
	}

	protected abstract suspend fun getAvailableFilterOptions(): List<ListFilterOption>

	protected open suspend fun getAdditionalChips(
		selectedOptions: Set<ListFilterOption>,
	): List<ChipsView.ChipModel> = emptyList()

	/**
	 * A single chip carrying the state that is currently applied, or a neutral "Status" chip when
	 * none is. Tapping it goes to the next entry of [ListFilterOption.State.CYCLE].
	 */
	private fun stateChip(selectedOptions: Set<ListFilterOption>): ChipsView.ChipModel? {
		if (!isStateFilterEnabled) {
			return null
		}
		val current = selectedOptions.filterIsInstance<ListFilterOption.State>().firstOrNull()
		return ChipsView.ChipModel(
			titleResId = current?.titleResId ?: R.string.status,
			icon = current?.iconResId ?: R.drawable.ic_status,
			isChecked = current != null,
			isCheckedIconVisible = false,
			data = current ?: ListFilterOption.State(null),
		)
	}

	private fun cycleStateFilter() {
		val cycle = ListFilterOption.State.CYCLE
		val current = appliedFilter.value.filterIsInstance<ListFilterOption.State>().firstOrNull()?.state
		val next = cycle.getOrNull(cycle.indexOf(current) + 1)
		appliedFilter.value = ArraySet(appliedFilter.value).also { set ->
			set.removeIf { it is ListFilterOption.State }
			if (next != null) {
				set.add(ListFilterOption.State(next))
			}
		}
	}

	private fun ArraySet<ListFilterOption>.addNoConflicts(option: ListFilterOption) {
		add(option)
		if (option is ListFilterOption.Inverted) {
			remove(option.option)
		} else {
			removeIf { it is ListFilterOption.Inverted && it.option == option }
		}
	}
}
