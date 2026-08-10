package org.koitharu.kotatsu.settings.reader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderControl
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect
import org.koitharu.kotatsu.main.ui.nav.rememberAnyDrawablePainter
import org.koitharu.kotatsu.reader.ui.ReaderActionsView
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.SettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.groupItemShape
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Customises the reader's bottom bar: one list holding every control, toggled with a switch and
 * reordered by dragging its handle. The preview on top is the real [ReaderActionsView] reading the
 * same preference, so it always matches the reader.
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
					initial = settings.readerControlsLayout,
					onChanged = { settings.readerControlsLayout = it },
				)
			}
		}
	}
}

private typealias BarLayout = List<Pair<ReaderControl, Boolean>>

@Composable
private fun ReaderBarScreen(
	initial: BarLayout,
	onChanged: (BarLayout) -> Unit,
) {
	var layout by remember { mutableStateOf(initial) }
	val apply: (BarLayout) -> Unit = { value ->
		layout = value
		onChanged(value)
	}
	SettingsScaffold {
		item { BottomBarPreview() }
		item { Spacer(Modifier.height(16.dp).fillMaxWidth()) }
		item { ControlList(layout = layout, onChange = apply) }
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

/**
 * The whole bar as one reorderable settings group. Rows are uniform height, so a drag is turned
 * into an index shift by dividing the accumulated offset by the row pitch.
 */
@Composable
private fun ControlList(
	layout: BarLayout,
	onChange: (BarLayout) -> Unit,
) {
	val haptic = rememberHapticEffect()
	val gap = with(LocalDensity.current) { GROUP_GAP.toPx() }
	var rowPitch by remember { mutableFloatStateOf(0f) }
	var dragIndex by remember { mutableIntStateOf(-1) }
	var dragOffset by remember { mutableFloatStateOf(0f) }

	val onDrag: (Float) -> Unit = { dy ->
		dragOffset += dy
		if (rowPitch > 0f && dragIndex >= 0) {
			val target = (dragIndex + (dragOffset / rowPitch).roundToInt())
				.coerceIn(0, layout.lastIndex)
			if (target != dragIndex) {
				onChange(layout.toMutableList().apply { add(target, removeAt(dragIndex)) })
				dragOffset -= (target - dragIndex) * rowPitch
				dragIndex = target
				haptic(HapticEffect.LIGHT_TICK)
			}
		}
	}

	Column {
		SettingsGroupTitle(stringResource(R.string.customize))
		layout.forEachIndexed { index, (control, isShown) ->
			// Keyed by control so each row's state — and its slide animation — follows the item as
			// the list reorders instead of staying with the slot.
			key(control) {
				val isDragging = index == dragIndex
				val slide = rememberReorderSlide(index, rowPitch, isDragging)
				ControlRow(
					control = control,
					isShown = isShown,
					shape = groupItemShape(index, layout.size),
					modifier = Modifier
						.zIndex(if (isDragging) 1f else 0f)
						.graphicsLayer {
							translationY = if (isDragging) dragOffset else slide.floatValue
							shadowElevation = if (isDragging) DRAG_ELEVATION.toPx() else 0f
						}
						.onSizeChanged { rowPitch = it.height + gap },
					onToggle = {
						onChange(layout.toMutableList().apply { this[index] = control to !isShown })
					},
					onDragStart = {
						dragIndex = index
						dragOffset = 0f
						haptic(HapticEffect.GESTURE_START)
					},
					onDrag = onDrag,
					onDragEnd = {
						dragIndex = -1
						dragOffset = 0f
						haptic(HapticEffect.GESTURE_END)
					},
				)
			}
			if (index < layout.lastIndex) {
				Spacer(Modifier.height(GROUP_GAP))
			}
		}
	}
}

/**
 * Rows displaced by a drag are re-composed straight into their new slot, which reads as a jump.
 * Offset the row back to where it used to be and spring it into place so it visibly travels.
 * The dragged row is excluded — it already follows the finger.
 *
 * The displacement is seeded during composition, not from the effect: an effect runs after the
 * frame is composed, so the row would be drawn once at its destination before jumping back to
 * start the animation — which is exactly the flicker this avoids.
 */
@Composable
private fun rememberReorderSlide(
	index: Int,
	pitch: Float,
	isDragging: Boolean,
): FloatState {
	val slide = remember { mutableFloatStateOf(0f) }
	val previousIndex = remember { mutableIntStateOf(index) }
	if (previousIndex.intValue != index) {
		if (pitch > 0f && !isDragging) {
			slide.floatValue += (previousIndex.intValue - index) * pitch
		}
		previousIndex.intValue = index
	}
	LaunchedEffect(index) {
		if (slide.floatValue != 0f) {
			animate(
				initialValue = slide.floatValue,
				targetValue = 0f,
				animationSpec = spring(
					dampingRatio = Spring.DampingRatioNoBouncy,
					stiffness = Spring.StiffnessMediumLow,
				),
			) { value, _ -> slide.floatValue = value }
		}
	}
	return slide
}

@Composable
private fun SettingsGroupTitle(title: String) {
	Text(
		text = title.uppercase(),
		style = MaterialTheme.typography.labelMedium,
		fontWeight = FontWeight.SemiBold,
		color = MaterialTheme.colorScheme.primary,
		modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 8.dp),
	)
}

@Composable
private fun ControlRow(
	control: ReaderControl,
	isShown: Boolean,
	shape: Shape,
	modifier: Modifier,
	onToggle: () -> Unit,
	onDragStart: () -> Unit,
	onDrag: (Float) -> Unit,
	onDragEnd: () -> Unit,
) {
	SettingsItem(
		title = stringResource(control.titleResId),
		icon = control.iconResId,
		shape = shape,
		modifier = modifier,
		onClick = onToggle,
		trailing = {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Switch(checked = isShown, onCheckedChange = { onToggle() })
				DragHandle(onDragStart = onDragStart, onDrag = onDrag, onDragEnd = onDragEnd)
			}
		},
	)
}

@Composable
private fun DragHandle(
	onDragStart: () -> Unit,
	onDrag: (Float) -> Unit,
	onDragEnd: () -> Unit,
) {
	// The gesture outlives recompositions of the list, so always call through to the latest
	// lambdas — a captured one would reorder against a stale list.
	val currentOnDragStart by rememberUpdatedState(onDragStart)
	val currentOnDrag by rememberUpdatedState(onDrag)
	val currentOnDragEnd by rememberUpdatedState(onDragEnd)
	Box(
		modifier = Modifier
			.size(44.dp)
			.pointerInput(Unit) {
				detectDragGestures(
					onDragStart = { currentOnDragStart() },
					onDragEnd = { currentOnDragEnd() },
					onDragCancel = { currentOnDragEnd() },
					onDrag = { change, amount ->
						change.consume()
						currentOnDrag(amount.y)
					},
				)
			},
		contentAlignment = Alignment.Center,
	) {
		Image(
			painter = rememberAnyDrawablePainter(R.drawable.ic_reorder_handle),
			contentDescription = null,
			modifier = Modifier.size(24.dp),
			colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant),
		)
	}
}

private val GROUP_GAP = 2.dp
private val DRAG_ELEVATION = 8.dp
