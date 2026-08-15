package org.koitharu.kotatsu.stats.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePaddingRelative
import coil3.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.util.ext.end
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.start
import org.koitharu.kotatsu.databinding.ActivityStatsBinding
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import javax.inject.Inject

/**
 * Reading statistics, rendered with Jetpack Compose inside the same medium collapsing app bar the
 * settings screens use — see [StatsScreen] for the content. The activity only owns the window: the
 * toolbar, the destructive "clear" dialog and the undo snackbar.
 */
@AndroidEntryPoint
class StatsActivity : BaseActivity<ActivityStatsBinding>() {

	@Inject
	lateinit var coil: ImageLoader

	private val viewModel: StatsViewModel by viewModels()

	private val bottomInset = mutableIntStateOf(0)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityStatsBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		setTitle(R.string.reading_stats)
		viewBinding.composeView.setViewCompositionStrategy(
			ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
		)
		viewBinding.composeView.setContent {
			DropSauceTheme {
				val density = LocalDensity.current
				val stats by viewModel.stats.collectAsState()
				val isLoading by viewModel.isLoading.collectAsState()
				val period by viewModel.period.collectAsState()
				val selectedCategories by viewModel.selectedCategories.collectAsState()
				val categories by viewModel.favoriteCategories.collectAsState(emptyList())

				StatsScreen(
					stats = stats,
					isLoading = isLoading,
					period = period,
					categories = categories,
					selectedCategories = selectedCategories,
					imageLoader = coil,
					bottomInset = with(density) { bottomInset.intValue.toDp() },
					onPeriodChange = { viewModel.period.value = it },
					onCategoryToggle = viewModel::toggleCategory,
					onCategoriesClear = viewModel::clearCategories,
					onMangaClick = { router.openDetails(it) },
				)
			}
		}
		viewModel.onActionDone.observeEvent(this, ReversibleActionObserver(viewBinding.composeView))
	}

	override fun onCreateOptionsMenu(menu: Menu?): Boolean {
		menuInflater.inflate(R.menu.opt_stats, menu)
		return super.onCreateOptionsMenu(menu)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
		R.id.action_clear -> {
			showClearConfirmDialog()
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.appbar.updatePaddingRelative(
			start = bars.start(v),
			top = bars.top,
			end = bars.end(v),
		)
		bottomInset.intValue = bars.bottom
		return insets
	}

	private fun showClearConfirmDialog() {
		buildAlertDialog(this, isCentered = true) {
			setMessage(R.string.clear_stats_confirm)
			setTitle(R.string.clear_stats)
			setIcon(R.drawable.ic_delete_all)
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.clear) { _, _ -> viewModel.clearStats() }
		}.show()
	}
}
