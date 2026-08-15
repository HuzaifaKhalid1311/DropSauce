package org.koitharu.kotatsu.stats.ui.sheet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.databinding.SheetStatsMangaBinding
import org.koitharu.kotatsu.settings.compose.DropSauceTheme

@AndroidEntryPoint
class MangaStatsSheet : BaseAdaptiveSheet<SheetStatsMangaBinding>() {

	private val viewModel: MangaStatsViewModel by viewModels()

	private val bottomInset = mutableIntStateOf(0)

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetStatsMangaBinding {
		return SheetStatsMangaBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetStatsMangaBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.composeView.setViewCompositionStrategy(
			ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
		)
		binding.composeView.setContent {
			DropSauceTheme {
				val density = LocalDensity.current
				val context = androidx.compose.ui.platform.LocalContext.current
				val buckets by viewModel.buckets.collectAsState()
				val startDate by viewModel.startDate.collectAsState()
				val pages by viewModel.totalPagesRead.collectAsState()
				val days by viewModel.daysRead.collectAsState()

				MangaStatsContent(
					title = viewModel.manga.title,
					subtitle = startDate?.format(context),
					buckets = buckets,
					pagesRead = pages,
					daysRead = days,
					bottomInset = with(density) { bottomInset.intValue.toDp() },
					onOpenClick = { router.openDetails(viewModel.manga) },
				)
			}
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		bottomInset.intValue = insets.getInsets(typeMask).bottom
		return insets.consume(v, typeMask, bottom = true)
	}
}
