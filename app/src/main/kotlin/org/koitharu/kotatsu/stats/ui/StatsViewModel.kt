package org.koitharu.kotatsu.stats.ui

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.stats.data.StatsRepository
import org.koitharu.kotatsu.stats.domain.ReadingStats
import org.koitharu.kotatsu.stats.domain.StatsPeriod
import javax.inject.Inject

@HiltViewModel
class StatsViewModel @Inject constructor(
	private val repository: StatsRepository,
	favouritesRepository: FavouritesRepository,
) : BaseViewModel() {

	val period = MutableStateFlow(StatsPeriod.ALL)
	val selectedCategories = MutableStateFlow<Set<Long>>(emptySet())
	val onActionDone = MutableEventFlow<ReversibleAction>()
	val favoriteCategories = favouritesRepository.observeCategories()

	val stats = MutableStateFlow(ReadingStats())

	init {
		launchJob(Dispatchers.Default) {
			combine(period, selectedCategories, ::Pair).collectLatest { (p, categories) ->
				stats.value = withLoading { repository.getStatsSnapshot(p, categories) }
			}
		}
	}

	fun toggleCategory(category: FavouriteCategory) {
		val snapshot = selectedCategories.value
		selectedCategories.value = if (category.id in snapshot) {
			snapshot - category.id
		} else {
			snapshot + category.id
		}
	}

	fun clearCategories() {
		selectedCategories.value = emptySet()
	}

	fun clearStats() {
		launchLoadingJob(Dispatchers.Default) {
			repository.clearStats()
			stats.value = ReadingStats(period = period.value)
			onActionDone.call(ReversibleAction(R.string.stats_cleared, null))
		}
	}
}
