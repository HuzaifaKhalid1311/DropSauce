package org.koitharu.kotatsu.favourites.ui.categories.select

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.ComposeAlertDialogFragment
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerAction
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveOptionGroupItem
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import javax.inject.Inject

/** Picks the one category the details screen's quick-add button puts titles into, or turns the button off. */
@AndroidEntryPoint
class DefaultCategoryDialog : ComposeAlertDialogFragment() {

	@Inject
	lateinit var repository: FavouritesRepository

	@Composable
	override fun Content() {
		val categories by remember { repository.observeCategories() }.collectAsState(emptyList())
		val haptic = rememberHapticEffect()
		var isEnabled by remember { mutableStateOf(repository.isQuickAddEnabled) }
		ExpressiveDialogCard(
			icon = painterResource(R.drawable.ic_bolt),
			title = stringResource(R.string.default_category),
			cornerAction = DialogCornerAction(
				icon = painterResource(R.drawable.ic_close),
				label = stringResource(android.R.string.cancel),
				onClick = ::dismiss,
			),
		) {
			// The switch sits apart from the categories: it turns the quick-add button on and off.
			val toggle = {
				haptic(if (isEnabled) HapticEffect.TOGGLE_OFF else HapticEffect.TOGGLE_ON)
				isEnabled = !isEnabled
				repository.isQuickAddEnabled = isEnabled
			}
			ExpressiveOptionGroupItem(index = 0, count = 1, onClick = toggle) {
				Text(
					text = stringResource(R.string.quick_add_to_favourites),
					style = MaterialTheme.typography.bodyLarge,
					color = MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.weight(1f),
				)
				Switch(checked = isEnabled, onCheckedChange = { toggle() })
			}
			Spacer(Modifier.height(12.dp))
			categories.forEachIndexed { index, category ->
				Option(
					text = category.title,
					isSelected = isEnabled && category.id == repository.defaultCategoryId,
					isEnabled = isEnabled,
					index = index,
					count = categories.size,
				) {
					haptic(HapticEffect.CONFIRM)
					repository.defaultCategoryId = category.id
					setFragmentResult(REQUEST_KEY, bundleOf())
					dismiss()
				}
			}
		}
	}

	@Composable
	private fun Option(
		text: String,
		isSelected: Boolean,
		isEnabled: Boolean,
		index: Int,
		count: Int,
		onClick: () -> Unit,
	) {
		ExpressiveOptionGroupItem(
			index = index,
			count = count,
			modifier = Modifier.alpha(if (isEnabled) 1f else 0.38f),
			enabled = isEnabled,
			onClick = onClick,
		) {
			RadioButton(selected = isSelected, onClick = null)
			Spacer(Modifier.width(12.dp))
			Text(
				text = text,
				style = MaterialTheme.typography.bodyLarge,
				fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
				color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.weight(1f),
			)
		}
	}

	companion object {
		const val REQUEST_KEY = "default_category"
	}
}
