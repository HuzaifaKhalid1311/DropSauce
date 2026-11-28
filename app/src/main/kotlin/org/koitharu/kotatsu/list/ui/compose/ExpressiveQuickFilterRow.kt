package org.koitharu.kotatsu.list.ui.compose

import android.content.Context
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.lerp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.toChipModel
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect
import org.koitharu.kotatsu.list.domain.ListFilterOption
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Measures a single [Chip] configured identically to `ChipsView.DataChip` (`item_quick_filter.xml`),
 * so Compose quick-filter rows match Favourites to the exact pixel on every density and font scale.
 */
fun measureFilterChipHeightPx(context: Context): Int {
	val probe = Chip(context).apply {
		setChipDrawable(ChipDrawable.createFromAttributes(context, null, 0, R.style.Widget_Kotatsu_Chip_Filter))
		isChipIconVisible = false
		setEnsureMinTouchTargetSize(false)
		isElegantTextHeight = false
		text = "Ag"
		setChipIconResource(R.drawable.ic_storage)
		isChipIconVisible = true
		measure(
			View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
			View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
		)
	}
	return probe.measuredHeight
}

@Composable
fun rememberFilterChipHeightPx(): Int {
	val context = LocalContext.current
	val config = LocalConfiguration.current
	return remember(context, config.fontScale, config.densityDpi) {
		measureFilterChipHeightPx(context)
	}
}

/**
 * Shared M3 Expressive quick-filter chip strip used by History and Feed: pixel-matched to [ChipsView]
 * at rest, with an optimistic instant flip and a spring press expansion that nudges neighbour chips.
 */
@Composable
fun ExpressiveQuickFilterRow(
	chips: List<ChipsView.ChipModel>,
	imageLoader: ImageLoader,
	onFilterOptionClick: (ListFilterOption) -> Unit,
	modifier: Modifier = Modifier,
	startPadding: Dp = dimensionResource(R.dimen.grid_spacing_outer),
	endPadding: Dp = startPadding,
	topPadding: Dp = 2.dp,
	bottomPadding: Dp = 6.dp,
	chipHeightPx: Int = rememberFilterChipHeightPx(),
) {
	val density = LocalDensity.current
	val chipHeight = with(density) { chipHeightPx.toDp() }
	val topPadPx = with(density) { topPadding.roundToPx() }
	val bottomPadPx = with(density) { bottomPadding.roundToPx() }
	val startPadPx = with(density) { startPadding.roundToPx() }
	val endPadPx = with(density) { endPadding.roundToPx() }
	val scrollState = rememberScrollState()
	val pulses = remember { LinkedHashMap<Any, Animatable<Float, AnimationVector1D>>() }
	val activeKeys = remember(chips) { chips.mapTo(HashSet(chips.size)) { chipKey(it) } }
	pulses.keys.retainAll(activeKeys)

	// A tapped chip flips right away; the list behind it catches up once the new query is mapped.
	var pending by remember { mutableStateOf<Map<Any, Pair<ChipsView.ChipModel, ChipsView.ChipModel>>>(emptyMap()) }
	val activePending = if (pending.isEmpty()) {
		emptyMap()
	} else {
		pending.filter { (key, flip) ->
			val (before, after) = flip
			chips.any { chipKey(it) == key && it == before && it != after }
		}
	}
	LaunchedEffect(chips) {
		if (activePending.size != pending.size) {
			pending = activePending
		}
	}

	Layout(
		content = {
			chips.forEach { chip ->
				val key = chipKey(chip)
				key(key) {
					val pulse = remember {
						Animatable(0f).also { pulses[key] = it }
					}
					pulses[key] = pulse
					val shown = activePending[key]?.second ?: chip
					QuickFilterChip(
						model = shown,
						chipHeight = chipHeight,
						pulse = pulse,
						imageLoader = imageLoader,
					) {
						val next = nextChipModel(shown)
						val updated = activePending.toMutableMap()
						if (next == chip) updated.remove(key) else updated[key] = chip to next
						if (next.isChecked) {
							chips.forEach { other ->
								val otherKey = chipKey(other)
								val shownOther = updated[otherKey]?.second ?: other
								if (otherKey != key && shownOther.isChecked && conflictsWith(shownOther.data, next.data)) {
									val cleared = shownOther.copy(isChecked = false)
									if (cleared == other) updated.remove(otherKey) else updated[otherKey] = other to cleared
								}
							}
						}
						pending = updated
						(chip.data as? ListFilterOption)?.let(onFilterOptionClick)
					}
				}
			}
		},
		modifier = modifier
			.fillMaxWidth()
			.height(with(density) { (chipHeightPx + topPadPx + bottomPadPx).toDp() })
			.horizontalScroll(scrollState),
	) { measurables, constraints ->
		val spacingPx = ChipSpacing.roundToPx()
		val expandPx = ClickExpandWidth.toPx()
		val childConstraints = constraints.copy(
			minWidth = 0,
			maxWidth = Constraints.Infinity,
			minHeight = chipHeightPx,
			maxHeight = chipHeightPx,
		)
		val placeables = measurables.map { it.measure(childConstraints) }
		var leftShift = 0
		val lastIndex = chips.lastIndex
		chips.forEachIndexed { index, chip ->
			val p = pulses[chipKey(chip)]?.value ?: 0f
			if (p != 0f && lastIndex > 0) {
				val share = when (index) {
					0 -> 0f
					lastIndex -> 1f
					else -> 0.5f
				}
				leftShift += (expandPx * p * share).roundToInt()
			}
		}
		val originX = (startPadPx - leftShift).coerceAtLeast(-scrollState.value)
		val contentWidth = if (placeables.isEmpty()) {
			0
		} else {
			originX + placeables.sumOf { it.width } + spacingPx * (placeables.size - 1) + endPadPx
		}
		val width = max(contentWidth, constraints.minWidth)
		val height = chipHeightPx + topPadPx + bottomPadPx
		layout(width, height) {
			var x = originX
			placeables.forEach { placeable ->
				placeable.placeRelative(x, topPadPx)
				x += placeable.width + spacingPx
			}
		}
	}
}

private fun chipKey(chip: ChipsView.ChipModel): Any = when (val data = chip.data) {
	is ListFilterOption.State -> KEY_STATE
	ListFilterOption.Macro.COMPLETED, ListFilterOption.NOT_COMPLETED -> KEY_COMPLETED
	null -> chip.titleResId
	else -> data
}

private fun nextChipModel(chip: ChipsView.ChipModel): ChipsView.ChipModel = when (val data = chip.data) {
	is ListFilterOption.State -> {
		val cycle = ListFilterOption.State.CYCLE
		val nextState = cycle.getOrNull(cycle.indexOf(data.state) + 1)
		val nextOption = ListFilterOption.State(nextState)
		chip.copy(
			title = null,
			titleResId = nextOption.titleResId,
			icon = nextOption.iconResId,
			isChecked = nextState != null,
			data = nextOption,
		)
	}

	ListFilterOption.Macro.COMPLETED, ListFilterOption.NOT_COMPLETED -> when {
		!chip.isChecked -> ListFilterOption.Macro.COMPLETED.toChipModel(isChecked = true).copy(isCheckedIconVisible = false)
		data == ListFilterOption.Macro.COMPLETED -> ListFilterOption.NOT_COMPLETED.toChipModel(isChecked = true).copy(isCheckedIconVisible = false)
		else -> ListFilterOption.Macro.COMPLETED.toChipModel(isChecked = false).copy(isCheckedIconVisible = false)
	}

	else -> chip.copy(isChecked = !chip.isChecked)
}

private fun conflictsWith(other: Any?, applied: Any?): Boolean =
	(applied is ListFilterOption.Inverted && other == applied.option) ||
		(other is ListFilterOption.Inverted && other.option == applied)

private const val KEY_STATE = "state"
private const val KEY_COMPLETED = "completed"

private val MinChipWidth = 48.dp
private val IconOnlyRestWidth = 32.dp
private val ChipSpacing = 8.dp
private val SquareCorner = 8.dp
private val LeadingSlot = 24.dp
private val ClickExpandWidth = 12.dp

private val ExpandSpring = spring<Float>(
	dampingRatio = Spring.DampingRatioNoBouncy,
	stiffness = 2200f,
	visibilityThreshold = 0.1f,
)
private val SettleSpring = spring<Float>(dampingRatio = 0.5f, stiffness = 900f)
private fun <T> morphSpring() = spring<T>(dampingRatio = 0.55f, stiffness = 1000f)
private val ChipColorSpec = spring<Color>(stiffness = Spring.StiffnessMedium)

@Composable
private fun QuickFilterChip(
	model: ChipsView.ChipModel,
	chipHeight: Dp,
	pulse: Animatable<Float, AnimationVector1D>,
	imageLoader: ImageLoader,
	onClick: () -> Unit,
) {
	val colors = MaterialTheme.colorScheme
	val haptic = rememberHapticEffect()
	val interactionSource = remember { MutableInteractionSource() }
	val isPressed by interactionSource.collectIsPressedAsState()

	LaunchedEffect(interactionSource) {
		val presses = ArrayList<PressInteraction.Press>(2)
		var animJob: Job? = null
		interactionSource.interactions.collect { interaction ->
			when (interaction) {
				is PressInteraction.Press -> {
					presses.add(interaction)
					animJob?.cancel()
					animJob = launch { pulse.animateTo(1f, ExpandSpring) }
				}

				is PressInteraction.Release -> {
					presses.remove(interaction.press)
					if (presses.isEmpty()) {
						animJob?.cancel()
						animJob = launch {
							if (pulse.value < 0.9f) {
								pulse.animateTo(1f, ExpandSpring)
							}
							pulse.animateTo(0f, SettleSpring)
						}
					}
				}

				is PressInteraction.Cancel -> {
					presses.remove(interaction.press)
					if (presses.isEmpty()) {
						animJob?.cancel()
						animJob = launch {
							pulse.animateTo(0f, SettleSpring)
						}
					}
				}
			}
		}
	}

	val isChecked = model.isChecked
	val showCheck = isChecked && model.isCheckedIconVisible && !model.isDropdown
	val hasIcon = model.isLoading || model.iconData != null || model.icon != 0
	val pulseValue = pulse.value
	val check by animateFloatAsState(
		targetValue = if (showCheck) 1f else 0f,
		animationSpec = morphSpring(),
		label = "check",
	)
	val pillCorner = chipHeight / 2
	val corner by animateDpAsState(
		targetValue = when {
			isChecked -> pillCorner
			isPressed -> (SquareCorner + pillCorner) / 2
			else -> SquareCorner
		},
		animationSpec = morphSpring(),
		label = "corner",
	)
	val tint = if (model.tint != 0) colorResource(model.tint) else null
	val baseOutline = tint ?: colors.outline
	val container by animateColorAsState(
		if (isChecked) colors.secondaryContainer else colors.surface,
		ChipColorSpec,
		label = "container",
	)
	val stroke by animateColorAsState(
		if (isChecked) baseOutline.copy(alpha = 0f) else baseOutline,
		ChipColorSpec,
		label = "stroke",
	)
	val textColor = tint ?: if (isChecked) colors.onSecondaryContainer else colors.onSurface
	val iconTint = if (isChecked) colors.onSecondaryContainer else tint ?: colors.onSurface
	val hasTrailing = model.isDropdown || model.isCloseable
	val title = model.title?.toString() ?: if (model.titleResId != 0) stringResource(model.titleResId) else ""
	val hasText = title.isNotEmpty() || model.counter > 0
	val clipShapePath = remember { Path() }
	Box(
		modifier = Modifier
			.height(chipHeight)
			.then(
				if (model.isIconOnly) {
					Modifier.width((IconOnlyRestWidth + ClickExpandWidth * pulseValue).coerceAtLeast(24.dp))
				} else {
					Modifier.widthIn(min = if (hasText || hasTrailing) MinChipWidth else IconOnlyRestWidth)
				},
			)
			// Drawn directly on the canvas so every spring frame invalidates the View's display list.
			.drawWithContent {
				if (pulseValue.isNaN() || check.isNaN()) return@drawWithContent
				val r = corner.toPx().coerceIn(0f, size.minDimension / 2f)
				val fill = container
				val border = stroke
				clipShapePath.rewind()
				clipShapePath.addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r)))
				clipPath(clipShapePath) {
					drawRoundRect(color = fill, cornerRadius = CornerRadius(r, r))
					this@drawWithContent.drawContent()
				}
				if (border.alpha > 0f) {
					val sw = 1.dp.toPx()
					val half = sw / 2f
					val sr = (r - half).coerceAtLeast(0f)
					drawRoundRect(
						color = border,
						topLeft = Offset(half, half),
						size = Size((size.width - sw).coerceAtLeast(0f), (size.height - sw).coerceAtLeast(0f)),
						cornerRadius = CornerRadius(sr, sr),
						style = Stroke(sw),
					)
				}
			}
			.clickable(
				interactionSource = interactionSource,
				indication = ripple(),
				role = Role.Checkbox,
				onClick = {
					haptic(if (isChecked) HapticEffect.TOGGLE_OFF else HapticEffect.TOGGLE_ON)
					onClick()
				},
			)
			.semantics {
				toggleableState = ToggleableState(isChecked)
			},
		contentAlignment = Alignment.Center,
	) {
		if (model.isIconOnly) {
			val iconProgress = (1f - check).coerceIn(0f, 1f)
			if (iconProgress > 0.001f && hasIcon) {
				ChipIcon(model, imageLoader, iconTint, scale = iconProgress, alpha = iconProgress)
			}
			val checkAlpha = check.coerceIn(0f, 1f)
			if (checkAlpha > 0.001f) {
				val checkScale = check.coerceAtLeast(0f)
				Icon(
					painter = painterResource(R.drawable.ic_check),
					contentDescription = null,
					tint = textColor.copy(alpha = textColor.alpha * checkAlpha),
					modifier = Modifier
						.size(18.dp)
						.drawWithContent { scale(checkScale, checkScale) { this@drawWithContent.drawContent() } },
				)
			}
		} else {
			Row(verticalAlignment = Alignment.CenterVertically) {
				val leadingProgress = if (hasIcon) 1f else check.coerceIn(0f, 1f)
				val extraEachSide = (ClickExpandWidth / 2) * pulseValue
				val startPad = ((if (hasText) lerp(16.dp, 8.dp, leadingProgress) else 7.dp) + extraEachSide).coerceAtLeast(2.dp)
				val baseSlot = if (hasText) LeadingSlot else 18.dp
				val slotWidth = if (hasIcon) baseSlot else (baseSlot * check).coerceAtLeast(0.dp)
				val endPad = ((when {
					hasTrailing -> 10.dp
					hasText -> 16.dp
					else -> 7.dp
				}) + extraEachSide).coerceAtLeast(2.dp)
				Spacer(Modifier.width(startPad))
				Box(
					modifier = Modifier.layout { measurable, constraints ->
						val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
						val width = slotWidth.roundToPx().coerceAtLeast(0)
						layout(width, placeable.height) { placeable.placeRelative(0, 0) }
					},
					contentAlignment = Alignment.CenterStart,
				) {
					if (hasIcon) {
						val iconProgress = (1f - check).coerceIn(0f, 1f)
						if (iconProgress > 0.001f) {
							ChipIcon(
								model = model,
								imageLoader = imageLoader,
								tint = iconTint,
								scale = iconProgress,
								alpha = iconProgress,
							)
						}
					}
					val checkAlpha = check.coerceIn(0f, 1f)
					if (checkAlpha > 0.001f) {
						val checkScale = check.coerceAtLeast(0f)
						Icon(
							painter = painterResource(R.drawable.ic_check),
							contentDescription = null,
							tint = textColor.copy(alpha = textColor.alpha * checkAlpha),
							modifier = Modifier
								.size(18.dp)
								.drawWithContent {
									scale(checkScale, checkScale) { this@drawWithContent.drawContent() }
								},
						)
					}
				}
				Text(
					text = if (model.counter > 0) {
						buildAnnotatedString {
							append(title)
							append("  ")
							withStyle(SpanStyle(fontSize = 0.74.em, color = colors.onSurfaceVariant)) {
								append(model.counter.toString())
							}
						}
					} else {
						AnnotatedString(title)
					},
					style = MaterialTheme.typography.labelLarge,
					color = textColor,
					maxLines = 1,
					modifier = SpringWidthElement,
				)
				if (hasTrailing) {
					Icon(
						painter = painterResource(if (model.isDropdown) R.drawable.ic_expand_more else R.drawable.ic_close),
						contentDescription = null,
						tint = textColor,
						modifier = Modifier
							.padding(start = 4.dp)
							.size(18.dp),
					)
				}
				Spacer(Modifier.width(endPad))
			}
		}
	}
}

@Composable
private fun ChipIcon(
	model: ChipsView.ChipModel,
	imageLoader: ImageLoader,
	tint: Color,
	scale: Float = 1f,
	alpha: Float = 1f,
) {
	val iconCorner = dimensionResource(R.dimen.chip_icon_corner)
	val clipPath = remember { Path() }
	val modifier = Modifier
		.size(18.dp)
		.drawWithContent {
			if (scale <= 0f || alpha <= 0f) return@drawWithContent
			scale(scale, scale) {
				if (model.iconData != null) {
					val r = iconCorner.toPx()
					clipPath.rewind()
					clipPath.addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r)))
					clipPath(clipPath) { this@drawWithContent.drawContent() }
				} else {
					this@drawWithContent.drawContent()
				}
			}
		}
	when {
		model.isLoading -> LoadingIndicator(modifier)
		model.iconData != null -> {
			val context = LocalContext.current
			val sizePx = with(LocalDensity.current) { 18.dp.roundToPx() }
			val fallback = if (model.icon != 0) painterResource(model.icon) else null
			AsyncImage(
				model = remember(model.iconData) {
					ImageRequest.Builder(context).data(model.iconData).size(sizePx).build()
				},
				imageLoader = imageLoader,
				contentDescription = null,
				placeholder = fallback,
				error = fallback,
				fallback = fallback,
				alpha = alpha,
				modifier = modifier,
			)
		}

		else -> Icon(
			painter = painterResource(model.icon),
			contentDescription = null,
			tint = tint.copy(alpha = tint.alpha * alpha),
			modifier = modifier,
		)
	}
}

/**
 * Springs the label width when a cycling chip swaps its text. It uses the same spring as the click settle,
 * which starts on the same frame, so the two add up to one smooth motion instead of fighting each other.
 * The new text is clipped to the animated width (a canvas clip, no layer) so it never spills past the chip.
 */
private data object SpringWidthElement : ModifierNodeElement<SpringWidthNode>() {
	override fun create() = SpringWidthNode()
	override fun update(node: SpringWidthNode) = Unit
}

private class SpringWidthNode : Modifier.Node(), LayoutModifierNode, DrawModifierNode {
	private var animatable: Animatable<Float, AnimationVector1D>? = null
	private var targetWidth = -1

	override fun onReset() {
		animatable = null
		targetWidth = -1
	}

	override fun onDetach() {
		animatable = null
		targetWidth = -1
	}

	override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
		val placeable = measurable.measure(constraints)
		val target = placeable.width
		val anim = animatable
		val width = if (anim == null || targetWidth < 0) {
			targetWidth = target
			animatable = Animatable(target.toFloat())
			target
		} else {
			if (target != targetWidth) {
				targetWidth = target
				coroutineScope.launch {
					anim.animateTo(target.toFloat(), SettleSpring)
				}
			}
			anim.value.roundToInt().coerceAtLeast(0)
		}
		return layout(constraints.constrainWidth(width), placeable.height) {
			placeable.placeRelative(0, 0)
		}
	}

	override fun ContentDrawScope.draw() {
		animatable?.value
		clipRect(0f, 0f, size.width, size.height) { this@draw.drawContent() }
	}
}
