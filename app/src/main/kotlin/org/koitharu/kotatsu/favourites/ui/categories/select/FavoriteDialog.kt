package org.koitharu.kotatsu.favourites.ui.categories.select

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import com.google.android.material.checkbox.MaterialCheckBox
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.ComposeAlertDialogFragment
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerAction
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveOptionGroupItem
import org.koitharu.kotatsu.core.ui.dialog.ExpressivePillButton
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.joinToStringWithLimit
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.favourites.ui.categories.select.model.MangaCategoryItem
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.LoadingState
import javax.inject.Inject

@AndroidEntryPoint
class FavoriteDialog : ComposeAlertDialogFragment() {

	private val viewModel by viewModels<FavoriteDialogViewModel>()

	@Inject
	lateinit var settings: AppSettings

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.onError.observeEvent(viewLifecycleOwner) { e ->
			Toast.makeText(context ?: return@observeEvent, e.getDisplayMessage(resources), Toast.LENGTH_SHORT).show()
		}
	}

	@Composable
	override fun Content() {
		val context = LocalContext.current
		val content by viewModel.content.collectAsState()
		val mangaTitles = remember { viewModel.manga.joinToStringWithLimit(context, 92) { it.title } }
		ExpressiveDialogCard(
			icon = painterResource(R.drawable.ic_heart_outline),
			title = stringResource(R.string.favorite),
			message = mangaTitles,
			cornerAction = DialogCornerAction(
				icon = painterResource(R.drawable.ic_check),
				label = stringResource(R.string.done),
				onClick = ::dismiss,
			),
			footer = {
				ExpressivePillButton(
					text = stringResource(R.string.manage),
					icon = painterResource(R.drawable.ic_settings),
					primary = false,
				) {
					dismiss()
					router.openFavoriteCategories()
				}
			},
		) {
			val categories = content.filterIsInstance<MangaCategoryItem>()
			categories.forEachIndexed { index, item -> CategoryRow(item, index, categories.size) }
			QuickAddNote()
			content.forEach { model ->
				when (model) {
					is LoadingState -> Box(
						modifier = Modifier
							.fillMaxWidth()
							.padding(24.dp),
						contentAlignment = Alignment.Center,
					) {
						LoadingIndicator()
					}

					is EmptyState -> Text(
						text = stringResource(model.textPrimary),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier
							.fillMaxWidth()
							.padding(vertical = 24.dp),
					)

					else -> Unit
				}
			}
		}
	}

	/** Ticked = holds every picked manga; a dash marks a category that holds only some of them. */
	@Composable
	private fun CategoryRow(item: MangaCategoryItem, index: Int, count: Int) {
		val colors = MaterialTheme.colorScheme
		val isTracked = item.isTrackerEnabled && item.category.isTrackingEnabled
		val isHidden = !item.category.isVisibleInLibrary
		val checked = item.checkedState == MaterialCheckBox.STATE_CHECKED
		ExpressiveOptionGroupItem(
			index = index,
			count = count,
			onClick = { viewModel.setChecked(item.category.id, !checked) },
		) {
			TriStateCheckbox(
				state = when (item.checkedState) {
					MaterialCheckBox.STATE_CHECKED -> ToggleableState.On
					MaterialCheckBox.STATE_INDETERMINATE -> ToggleableState.Indeterminate
					else -> ToggleableState.Off
				},
				onClick = null,
			)
			Spacer(Modifier.width(12.dp))
			Text(
				text = item.category.title,
				style = MaterialTheme.typography.bodyLarge,
				fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal,
				color = if (checked) colors.primary else colors.onSurface,
				modifier = Modifier.weight(1f),
			)
			if (isTracked) RowIcon(R.drawable.ic_notification)
			if (isHidden) RowIcon(R.drawable.ic_eye_off)
		}
	}

	/** Shown once to people updating, until closed, while quick add is still off. */
	@Composable
	private fun QuickAddNote() {
		var isVisible by remember {
			mutableStateOf(settings.isTipEnabled(AppSettings.TIP_QUICK_ADD) && !settings.isFavouritesQuickAddEnabled)
		}
		if (!isVisible) return
		val colors = MaterialTheme.colorScheme
		Spacer(Modifier.height(12.dp))
		Surface(
			shape = RoundedCornerShape(20.dp),
			color = colors.surfaceContainerHighest,
			modifier = Modifier.fillMaxWidth(),
		) {
			Row(
				modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Icon(
					painter = painterResource(R.drawable.ic_bolt),
					contentDescription = null,
					tint = colors.primary,
					modifier = Modifier.size(20.dp),
				)
				Spacer(Modifier.width(12.dp))
				Text(
					text = stringResource(R.string.quick_add_tip),
					style = MaterialTheme.typography.bodySmall,
					color = colors.onSurfaceVariant,
					modifier = Modifier
						.weight(1f)
						.padding(vertical = 8.dp),
				)
				IconButton(
					onClick = {
						settings.closeTip(AppSettings.TIP_QUICK_ADD)
						isVisible = false
					},
				) {
					Icon(
						painter = painterResource(R.drawable.ic_close),
						contentDescription = stringResource(R.string.close),
						modifier = Modifier.size(18.dp),
					)
				}
			}
		}
	}

	@Composable
	private fun RowIcon(@DrawableRes icon: Int) {
		Icon(
			painter = painterResource(icon),
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.padding(start = 8.dp).size(18.dp),
		)
	}
}
