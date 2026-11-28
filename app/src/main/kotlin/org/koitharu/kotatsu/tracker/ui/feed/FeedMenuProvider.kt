package org.koitharu.kotatsu.tracker.ui.feed

import android.content.Context
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
		val running = viewModel.isRunning.value
		menu.findItem(R.id.action_update)?.apply {
			setTitle(if (running) R.string.stop_update else R.string.update)
			setIcon(if (running) R.drawable.ic_stop else R.drawable.ic_retry)
		}
		menu.findItem(R.id.action_mark_all_read)?.isVisible = viewModel.unreadCount.value > 0
		menu.findItem(R.id.action_clear_feed)?.isEnabled = viewModel.totalCount.value > 0
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

		R.id.action_mark_all_read -> {
			viewModel.markAllAsRead()
			true
		}

		R.id.action_clear_feed -> {
			showClearFeedDialog()
			true
		}

		R.id.action_tracker_settings -> {
			context.startActivity(AppRouter.trackerSettingsIntent(context))
			true
		}

		R.id.action_tracker_debug -> {
			router.openTrackerDebug()
			true
		}

		else -> false
	}

	private fun showClearFeedDialog() {
		val total = viewModel.totalCount.value
		val unread = viewModel.unreadCount.value
		val hasRead = (total - unread) > 0
		val defaultScope = if (hasRead && unread > 0) SCOPE_READ_ONLY else SCOPE_ALL

		showComposeDialog(context) { dismiss ->
			var scope by remember { mutableIntStateOf(defaultScope) }
			var clearCounters by remember { mutableStateOf(true) }
			val colors = MaterialTheme.colorScheme
			val motion = MaterialTheme.motionScheme

			ExpressiveDialogCard(
				icon = painterResource(R.drawable.ic_clear_all),
				title = stringResource(R.string.clear_updates_feed),
				message = if (hasRead) null else stringResource(R.string.text_clear_updates_feed_prompt),
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
									if (hasRead && scope == SCOPE_READ_ONLY) {
										viewModel.clearReadEntries()
									} else {
										viewModel.clearFeed(clearCounters)
									}
								},
							),
						),
					)
				},
			) {
				if (hasRead) {
					val scopeLabels = listOf(
						stringResource(R.string.feed_clear_read_only),
						stringResource(R.string.feed_clear_everything),
					)
					scopeLabels.forEachIndexed { index, label ->
						val selected = scope == index
						ExpressiveOptionGroupItem(
							index = index,
							count = scopeLabels.size,
							onClick = { scope = index },
						) {
							RadioButton(selected = selected, onClick = null)
							Spacer(Modifier.width(12.dp))
							Text(
								text = label,
								style = MaterialTheme.typography.bodyLarge,
								fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
								color = if (selected) colors.primary else colors.onSurface,
								modifier = Modifier.weight(1f),
							)
						}
					}

					AnimatedVisibility(
						visible = scope == SCOPE_ALL,
						enter = expandVertically(animationSpec = motion.fastSpatialSpec(), expandFrom = Alignment.Top) +
							fadeIn(animationSpec = motion.fastEffectsSpec()),
						exit = shrinkVertically(animationSpec = motion.fastSpatialSpec(), shrinkTowards = Alignment.Top) +
							fadeOut(animationSpec = motion.fastEffectsSpec()),
					) {
						Column(modifier = Modifier.fillMaxWidth()) {
							Spacer(Modifier.height(8.dp))
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
				} else {
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

	private companion object {
		const val SCOPE_READ_ONLY = 0
		const val SCOPE_ALL = 1
	}
}
