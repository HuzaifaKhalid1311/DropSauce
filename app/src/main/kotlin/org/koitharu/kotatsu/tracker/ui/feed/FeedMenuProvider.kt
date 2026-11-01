package org.koitharu.kotatsu.tracker.ui.feed

import android.content.Context
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.dialog.DialogButtonSpec
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerAction
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveButtonGrid
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveOptionGroupItem
import org.koitharu.kotatsu.core.ui.dialog.showComposeDialog

class FeedMenuProvider(
	private val snackbarHost: View,
	private val viewModel: FeedViewModel,
	private val router: AppRouter,
) : MenuProvider {

	private val context: Context
		get() = snackbarHost.context

	override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
		menuInflater.inflate(R.menu.opt_feed, menu)
	}

	override fun onPrepareMenu(menu: Menu) {
		menu.findItem(R.id.action_update)?.setTitle(
			if (viewModel.isRunning.value) R.string.stop_update else R.string.update,
		)
	}

	override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
		R.id.action_update -> {
			if (viewModel.isRunning.value) {
				viewModel.stopUpdate()
			} else {
				viewModel.update()
			}
			true
		}

		R.id.action_tracker_debug -> {
			router.openTrackerDebug()
			true
		}

		R.id.action_clear_feed -> {
			showClearFeedDialog()
			true
		}

		else -> false
	}

	private fun showClearFeedDialog() {
		showComposeDialog(context) { dismiss ->
			var clearCounters by remember { mutableStateOf(true) }
			val colors = MaterialTheme.colorScheme
			ExpressiveDialogCard(
				icon = painterResource(R.drawable.ic_clear_all),
				title = stringResource(R.string.clear_updates_feed),
				message = stringResource(R.string.text_clear_updates_feed_prompt),
				destructive = true,
				cornerAction = DialogCornerAction(
					icon = painterResource(R.drawable.ic_close),
					label = stringResource(android.R.string.cancel),
					onClick = dismiss,
				),
				footer = {
					ExpressiveButtonGrid(
						buttons = listOf(
							DialogButtonSpec(
								label = stringResource(R.string.clear),
								primary = true,
								destructive = true,
								onClick = {
									dismiss()
									viewModel.clearFeed(clearCounters)
								},
							),
						),
					)
				},
			) {
				ExpressiveOptionGroupItem(
					index = 0,
					count = 1,
					onClick = { clearCounters = !clearCounters },
				) {
					Checkbox(checked = clearCounters, onCheckedChange = null)
					Spacer(Modifier.width(12.dp))
					Text(
						text = stringResource(R.string.clear_new_chapters_counters),
						style = MaterialTheme.typography.bodyLarge,
						fontWeight = if (clearCounters) FontWeight.Medium else FontWeight.Normal,
						color = if (clearCounters) colors.primary else colors.onSurface,
						modifier = Modifier.weight(1f),
					)
				}
			}
		}
	}
}
