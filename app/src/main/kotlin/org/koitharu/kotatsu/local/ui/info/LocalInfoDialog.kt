package org.koitharu.kotatsu.local.ui.info

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.ComposeAlertDialogFragment
import org.koitharu.kotatsu.core.ui.dialog.DialogButtonSpec
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerAction
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveButtonGrid
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.showConfirmDialog
import org.koitharu.kotatsu.core.util.FileSize
import org.koitharu.kotatsu.core.util.KotatsuColors
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import androidx.appcompat.R as appcompatR

@AndroidEntryPoint
class LocalInfoDialog : ComposeAlertDialogFragment() {

	private val viewModel: LocalInfoViewModel by viewModels()

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.onCleanedUp.observeEvent(viewLifecycleOwner, ::onCleanedUp)
		viewModel.onDeletedAll.observeEvent(viewLifecycleOwner) { isLocal ->
			// A manga opened straight from local storage no longer exists, so its details page goes too
			if (isLocal) activity?.finishAfterTransition()
			dismissAllowingStateLoss()
		}
		viewModel.isCleaningUp.observe(viewLifecycleOwner) { loading ->
			isCancelable = !loading
		}
	}

	@Composable
	override fun Content() {
		val path by viewModel.path.collectAsState()
		val size by viewModel.size.collectAsState()
		val available by viewModel.availableSize.collectAsState()
		val isCleaningUp by viewModel.isCleaningUp.collectAsState()
		val context = LocalContext.current
		val colors = MaterialTheme.colorScheme
		val usedColor = remember(context) {
			Color(KotatsuColors.segmentColor(context, appcompatR.attr.colorPrimary))
		}
		val hasSizes = size >= 0 && available >= 0 && size + available > 0
		ExpressiveDialogCard(
			icon = painterResource(R.drawable.ic_storage),
			title = stringResource(R.string.saved_manga),
			cornerAction = DialogCornerAction(
				icon = painterResource(R.drawable.ic_close),
				label = stringResource(R.string.close),
				// Back and outside taps already wait for the delete; closing mid-way would cut it off.
				enabled = !isCleaningUp,
				onClick = ::dismiss,
			),
			footer = {
				ExpressiveButtonGrid(
					buttons = listOf(
						DialogButtonSpec(
							label = stringResource(R.string.delete_read_chapters),
							icon = painterResource(R.drawable.ic_delete),
							enabled = !isCleaningUp,
							onClick = viewModel::cleanup,
						),
						DialogButtonSpec(
							label = stringResource(R.string.delete_all_chapters),
							icon = painterResource(R.drawable.ic_delete_forever),
							destructive = true,
							enabled = !isCleaningUp,
							onClick = ::confirmDeleteAll,
						),
					),
				)
			},
		) {
			Text(
				text = stringResource(R.string.location),
				style = MaterialTheme.typography.labelMedium,
				color = colors.onSurfaceVariant,
			)
			Spacer(Modifier.height(4.dp))
			SelectionContainer {
				Text(
					text = path.orEmpty(),
					style = MaterialTheme.typography.bodyMedium,
					color = colors.onSurface,
				)
			}
			Spacer(Modifier.height(12.dp))
			val fraction by animateFloatAsState(
				targetValue = if (hasSizes) size.toFloat() / (size + available).toFloat() else 0f,
				label = "localUsage",
			)
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.height(18.dp)
					.clip(CircleShape)
					.background(colors.secondaryContainer),
			) {
				if (hasSizes) {
					Box(
						modifier = Modifier
							.widthIn(min = 18.dp)
							.fillMaxWidth(fraction)
							.fillMaxHeight()
							.clip(CircleShape)
							.background(usedColor),
					)
				}
			}
			Spacer(Modifier.height(16.dp))
			UsageLegend(
				color = usedColor,
				text = if (hasSizes) {
					stringResource(
						R.string.memory_usage_pattern,
						stringResource(R.string.this_manga),
						FileSize.BYTES.format(context, size),
					)
				} else {
					stringResource(R.string.this_manga)
				},
			)
			Spacer(Modifier.height(8.dp))
			UsageLegend(
				color = colors.secondaryContainer,
				text = if (hasSizes) {
					stringResource(
						R.string.memory_usage_pattern,
						stringResource(R.string.available),
						FileSize.BYTES.format(context, available),
					)
				} else {
					stringResource(R.string.available)
				},
			)
		}
	}

	@Composable
	private fun UsageLegend(color: Color, text: String) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Box(
				modifier = Modifier
					.size(12.dp)
					.background(color, RoundedCornerShape(3.dp)),
			)
			Spacer(Modifier.width(12.dp))
			Text(
				text = text,
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurface,
			)
		}
	}

	private fun confirmDeleteAll() {
		showConfirmDialog(
			context = requireContext(),
			icon = R.drawable.ic_delete_forever,
			title = getString(R.string.delete_all_chapters),
			message = getString(R.string.text_delete_local_manga, viewModel.title),
			confirmLabel = getString(R.string.delete),
			destructive = true,
			onConfirm = viewModel::deleteAll,
		)
	}

	private fun onCleanedUp(result: Pair<Int, Long>) {
		val c = context ?: return
		val text = if (result.first == 0 && result.second == 0L) {
			c.getString(R.string.no_chapters_deleted)
		} else {
			c.getString(
				R.string.chapters_deleted_pattern,
				c.resources.getQuantityStringSafe(R.plurals.chapters, result.first, result.first),
				FileSize.BYTES.format(c, result.second),
			)
		}
		Toast.makeText(c, text, Toast.LENGTH_SHORT).show()
	}
}
