package org.koitharu.kotatsu.reader.ui.tts

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerAction
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveOptionGroupItem
import org.koitharu.kotatsu.core.ui.dialog.showComposeDialog

private val SLEEP_TIMER_MINUTES = intArrayOf(15, 30, 45, 60)

fun showTtsSleepTimerDialog(context: Context, tts: ReaderTts) {
	showComposeDialog(context) { dismiss ->
		val current by tts.sleepTimer.collectAsState()
		val colors = MaterialTheme.colorScheme
		val options = buildList {
			add(TimerOption(label = context.getString(R.string.none), selected = current is ReaderTts.SleepTimer.Off) {
				tts.cancelSleepTimer()
			})
			SLEEP_TIMER_MINUTES.forEach { mins ->
				add(
					TimerOption(
						label = context.resources.getQuantityString(R.plurals.minutes, mins, mins),
						selected = false,
					) {
						tts.setSleepTimerMinutes(mins)
					},
				)
			}
			add(
				TimerOption(
					label = context.getString(R.string.tts_sleep_timer_eoc),
					selected = current is ReaderTts.SleepTimer.EndOfChapter,
				) {
					tts.setSleepTimerEndOfChapter()
				},
			)
		}
		ExpressiveDialogCard(
			icon = painterResource(R.drawable.ic_sleep_timer),
			title = stringResource(R.string.tts_sleep_timer),
			cornerAction = DialogCornerAction(
				icon = painterResource(R.drawable.ic_close),
				label = stringResource(android.R.string.cancel),
				onClick = dismiss,
			),
		) {
			Column(modifier = Modifier.fillMaxWidth()) {
				options.forEachIndexed { index, option ->
					ExpressiveOptionGroupItem(
						index = index,
						count = options.size,
						onClick = {
							option.onSelect()
							dismiss()
						},
					) {
						Text(
							text = option.label,
							style = MaterialTheme.typography.bodyLarge,
							color = colors.onSurface,
							modifier = Modifier
								.weight(1f)
								.padding(vertical = 8.dp),
						)
						RadioButton(selected = option.selected, onClick = null)
					}
				}
			}
		}
	}
}

private class TimerOption(
	val label: String,
	val selected: Boolean,
	val onSelect: () -> Unit,
)
