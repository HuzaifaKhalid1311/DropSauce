package org.koitharu.kotatsu.favourites.ui.categories.select

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import com.google.android.material.checkbox.MaterialCheckBox
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.ComposeAlertDialogFragment
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogTextButton
import org.koitharu.kotatsu.core.ui.dialog.ExpressivePillButton
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.joinToStringWithLimit
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.favourites.ui.categories.select.model.MangaCategoryItem
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.LoadingState

@AndroidEntryPoint
class FavoriteDialog : ComposeAlertDialogFragment() {

	private val viewModel by viewModels<FavoriteDialogViewModel>()

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
		val title = remember { viewModel.manga.joinToStringWithLimit(context, 92) { it.title } }
		ExpressiveDialogCard(
			icon = painterResource(R.drawable.ic_heart),
			title = title,
		) {
			Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
				val categories = content.filterIsInstance<MangaCategoryItem>()
				if (categories.isNotEmpty()) {
					FlowRow(
						horizontalArrangement = Arrangement.spacedBy(8.dp),
						verticalArrangement = Arrangement.spacedBy(8.dp),
					) {
						categories.forEach { CategoryChip(it) }
					}
				}
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
			Spacer(Modifier.size(16.dp))
			ExpressivePillButton(text = stringResource(R.string.manage), primary = true) {
				dismiss()
				router.openFavoriteCategories()
			}
			Spacer(Modifier.size(8.dp))
			ExpressiveDialogTextButton(text = stringResource(R.string.done)) { dismiss() }
		}
	}

	/** Selected = holds every picked manga; a dash marks a category that holds only some of them. */
	@Composable
	private fun CategoryChip(item: MangaCategoryItem) {
		val isTracked = item.isTrackerEnabled && item.category.isTrackingEnabled
		val isHidden = !item.category.isVisibleInLibrary
		FilterChip(
			selected = item.checkedState == MaterialCheckBox.STATE_CHECKED,
			onClick = {
				viewModel.setChecked(item.category.id, item.checkedState != MaterialCheckBox.STATE_CHECKED)
			},
			label = { Text(item.category.title) },
			leadingIcon = if (item.checkedState == MaterialCheckBox.STATE_INDETERMINATE) {
				{ ChipIcon(R.drawable.ic_partly_selected) }
			} else {
				null
			},
			trailingIcon = if (isTracked || isHidden) {
				{
					Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
						if (isTracked) ChipIcon(R.drawable.ic_notification)
						if (isHidden) ChipIcon(R.drawable.ic_eye_off)
					}
				}
			} else {
				null
			},
		)
	}

	@Composable
	private fun ChipIcon(@DrawableRes icon: Int) {
		Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
	}
}
