package org.koitharu.kotatsu.reader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.dialog.DialogButtonSpec
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerAction
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerButton
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveButtonGrid
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressivePillButton
import org.koitharu.kotatsu.core.ui.dialog.showComposeDialog

/**
 * M3 Expressive popup shown when the user opens a chapter away from their saved progress:
 * peek into it without touching history, or move the reading progress there.
 */
fun showChapterJumpDialog(
	activity: FragmentActivity,
	onPeek: () -> Unit,
	onMoveProgress: () -> Unit,
	onDisable: () -> Unit,
) {
	showComposeDialog(activity) { dismiss ->
		ChapterJumpContent(
			onPeek = {
				dismiss()
				onPeek()
			},
			onMoveProgress = {
				dismiss()
				onMoveProgress()
			},
			onDisable = {
				dismiss()
				onDisable()
				onMoveProgress()
			},
		)
	}
}

@Composable
private fun ChapterJumpContent(
	onPeek: () -> Unit,
	onMoveProgress: () -> Unit,
	onDisable: () -> Unit,
) {
	var menuExpanded by remember { mutableStateOf(false) }
	var showDisableConfirm by remember { mutableStateOf(false) }

	if (showDisableConfirm) {
		ExpressiveDialogCard(
			icon = painterResource(R.drawable.ic_eye_off),
			title = stringResource(R.string.chapter_jump_disable_title),
			message = stringResource(R.string.chapter_jump_disable_message),
			cornerAction = DialogCornerAction(
				icon = painterResource(R.drawable.ic_close),
				label = stringResource(android.R.string.cancel),
				onClick = { showDisableConfirm = false },
			),
			footer = {
				ExpressivePillButton(
					text = stringResource(R.string.chapter_jump_disable_confirm),
					primary = true,
					onClick = onDisable,
				)
			},
		)
		return
	}

	val moreLabel = stringResource(R.string.more)
	ExpressiveDialogCard(
		icon = painterResource(R.drawable.ic_eye),
		title = stringResource(R.string.chapter_jump_title),
		message = stringResource(R.string.chapter_jump_message),
		headerTrailing = {
			Box {
				DialogCornerButton(
					action = DialogCornerAction(
						icon = painterResource(R.drawable.ic_more_vert),
						label = moreLabel,
						onClick = { menuExpanded = true },
					),
				)
				DropdownMenu(
					expanded = menuExpanded,
					onDismissRequest = { menuExpanded = false },
				) {
					DropdownMenuItem(
						text = { Text(stringResource(R.string.chapter_jump_dont_show_again)) },
						onClick = {
							menuExpanded = false
							showDisableConfirm = true
						},
					)
				}
			}
		},
		footer = {
			ExpressiveButtonGrid(
				buttons = listOf(
					DialogButtonSpec(
						label = stringResource(R.string.chapter_jump_peek),
						icon = painterResource(R.drawable.ic_eye),
						primary = true,
						onClick = onPeek,
					),
					DialogButtonSpec(
						label = stringResource(R.string.chapter_jump_move),
						icon = painterResource(R.drawable.ic_eye_check),
						primary = false,
						onClick = onMoveProgress,
					),
				),
			)
		},
	)
}
