package org.koitharu.kotatsu.favourites.ui.categories.select

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import com.google.android.material.checkbox.MaterialCheckBox
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import coil3.ImageLoader
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

	@Inject
	lateinit var imageLoader: ImageLoader

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
		val duplicatesState by viewModel.duplicatesState.collectAsState()

		val duplicatesList = duplicatesState
		if (!duplicatesList.isNullOrEmpty()) {
			DuplicateMangaDialog(
				duplicatesList = duplicatesList,
				imageLoader = imageLoader,
				onAddAnyway = { viewModel.confirmAddDuplicate() },
				onAddIndividualAnyway = { target -> viewModel.confirmAddIndividualAnyway(target) },
				onSkipIndividual = { target -> viewModel.skipIndividualDuplicate(target) { dismiss() } },
				onOpenManga = { manga ->
					router.openDetails(manga)
				},
				onMigrateManga = { targetManga, existingManga ->
					viewModel.migrateDuplicate(targetManga, existingManga) { dismiss() }
				},
				onDismissRequest = { viewModel.dismissDuplicate { dismiss() } },
			)
			return
		}


		val activeManga = remember(duplicatesState) { viewModel.getActiveManga() }
		val title = remember(activeManga) { activeManga.joinToStringWithLimit(context, 92) { it.title } }
		ExpressiveDialogCard(
			icon = painterResource(R.drawable.ic_heart),
			title = title,
		) {
			Column(modifier = Modifier.verticalScroll(rememberScrollState())) {

				content.forEach { model ->
					when (model) {
						is LoadingState -> Box(
							modifier = Modifier
								.fillMaxWidth()
								.padding(24.dp),
							contentAlignment = Alignment.Center,
						) {
							CircularProgressIndicator()
						}

						is EmptyState -> Text(
							text = stringResource(model.textPrimary),
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier
								.fillMaxWidth()
								.padding(vertical = 24.dp),
						)

						is MangaCategoryItem -> CategoryRow(model)
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

	@Composable
	private fun CategoryRow(item: MangaCategoryItem) {
		val toggle = {
			viewModel.setChecked(item.category.id, item.checkedState != MaterialCheckBox.STATE_CHECKED)
		}
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(min = 52.dp)
				.clip(RoundedCornerShape(16.dp))
				.clickable { toggle() }
				.padding(horizontal = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			TriStateCheckbox(
				state = when (item.checkedState) {
					MaterialCheckBox.STATE_CHECKED -> ToggleableState.On
					MaterialCheckBox.STATE_INDETERMINATE -> ToggleableState.Indeterminate
					else -> ToggleableState.Off
				},
				onClick = { toggle() },
			)
			Spacer(Modifier.size(8.dp))
			Text(
				text = item.category.title,
				style = MaterialTheme.typography.bodyLarge,
				color = MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.weight(1f),
			)
			if (item.isTrackerEnabled && item.category.isTrackingEnabled) {
				Icon(
					painter = painterResource(R.drawable.ic_notification),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier
						.padding(start = 8.dp)
						.size(18.dp),
				)
			}
			if (!item.category.isVisibleInLibrary) {
				Icon(
					painter = painterResource(R.drawable.ic_eye_off),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier
						.padding(start = 8.dp)
						.size(18.dp),
				)
			}
		}
	}
}
