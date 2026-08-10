package org.koitharu.kotatsu.settings.reader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderControl
import org.koitharu.kotatsu.main.ui.nav.rememberAnyDrawablePainter
import org.koitharu.kotatsu.reader.ui.ReaderActionsView
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import javax.inject.Inject

/**
 * Picks which controls the reader's bottom bar shows and in which order. The preview at the top is
 * the real [ReaderActionsView] reading the same preference, so it always matches the reader.
 */
@AndroidEntryPoint
class ReaderBarSettingsFragment :
	BaseComposeSettingsFragment(R.string.reader_controls_in_bottom_bar) {

	@Inject
	lateinit var settings: AppSettings

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				ReaderBarScreen(
					initial = settings.readerControls,
					onChanged = { settings.readerControls = it },
				)
			}
		}
	}
}

@Composable
private fun ReaderBarScreen(
	initial: List<ReaderControl>,
	onChanged: (List<ReaderControl>) -> Unit,
) {
	var controls by remember { mutableStateOf(initial) }
	val apply: (List<ReaderControl>) -> Unit = { value ->
		controls = value
		onChanged(value)
	}
	val available = ReaderControl.entries.filterNot { it in controls }

	SettingsScaffold {
		item { BottomBarPreview() }
		item { Spacer(Modifier.height(16.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = "In the bar") {
				if (controls.isEmpty()) {
					item { pos ->
						SettingsItem(
							title = stringResource(R.string.reader_controls_none),
							shape = pos.shape,
						)
					}
				}
				controls.forEachIndexed { index, control ->
					item { pos ->
						ControlRow(
							control = control,
							shape = pos.shape,
							isShown = true,
							canMoveUp = index > 0,
							canMoveDown = index < controls.lastIndex,
							onMoveUp = { apply(controls.swapped(index, index - 1)) },
							onMoveDown = { apply(controls.swapped(index, index + 1)) },
							onToggle = { apply(controls - control) },
						)
					}
				}
			}
		}
		if (available.isNotEmpty()) {
			item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
			item {
				SettingsGroup(title = "Hidden") {
					available.forEach { control ->
						item { pos ->
							ControlRow(
								control = control,
								shape = pos.shape,
								isShown = false,
								onToggle = { apply(controls + control) },
							)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun BottomBarPreview() {
	AndroidView(
		modifier = Modifier.fillMaxWidth(),
		factory = { ctx ->
			LayoutInflater.from(ctx).inflate(R.layout.view_reader_bar_preview, null).also { root ->
				root.findViewById<ReaderActionsView>(R.id.actionsView).apply {
					isSliderEnabled = true
					setSliderValue(value = 4, max = 12)
				}
			}
		},
	)
}

@Composable
private fun ControlRow(
	control: ReaderControl,
	shape: Shape,
	isShown: Boolean,
	onToggle: () -> Unit,
	canMoveUp: Boolean = false,
	canMoveDown: Boolean = false,
	onMoveUp: () -> Unit = {},
	onMoveDown: () -> Unit = {},
) {
	SettingsItem(
		title = stringResource(control.titleResId),
		icon = control.iconResId,
		shape = shape,
		onClick = onToggle,
		trailing = {
			Row(verticalAlignment = Alignment.CenterVertically) {
				if (isShown) {
					ReorderButton(R.drawable.ic_arrow_up, canMoveUp, onMoveUp)
					ReorderButton(R.drawable.ic_arrow_down, canMoveDown, onMoveDown)
				}
				Switch(checked = isShown, onCheckedChange = { onToggle() })
			}
		},
	)
}

@Composable
private fun ReorderButton(
	@DrawableRes icon: Int,
	enabled: Boolean,
	onClick: () -> Unit,
) {
	IconButton(
		onClick = onClick,
		enabled = enabled,
		modifier = Modifier.size(36.dp),
	) {
		Image(
			painter = rememberAnyDrawablePainter(icon),
			contentDescription = null,
			modifier = Modifier.size(20.dp),
			colorFilter = ColorFilter.tint(
				MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.3f),
			),
		)
	}
}

private fun List<ReaderControl>.swapped(a: Int, b: Int): List<ReaderControl> = toMutableList().apply {
	val tmp = this[a]
	this[a] = this[b]
	this[b] = tmp
}
