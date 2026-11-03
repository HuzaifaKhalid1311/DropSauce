package org.koitharu.kotatsu.main.ui.nav

import android.graphics.PorterDuff
import android.os.SystemClock
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tanh
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect

data class FloatingNavBarItem(
	@IdRes val id: Int,
	val titleRes: Int,
	@DrawableRes val icon: Int,
	val badgeCount: Int = 0,
)

data class FloatingNavBarColors(
	val container: Int,
	val selectedContainer: Int,
	val selectedContent: Int,
	val unselectedContent: Int,
)

// Smooth S-curve easings with zero initial velocity spike so starting from rest (tap launch,
// hold swell, label morph, and drag drop settle) never snaps abruptly on the first frames.
private val SmoothLiquidEasing = CubicBezierEasing(0.32f, 0f, 0.14f, 1f)
private val SmoothSettleEasing = CubicBezierEasing(0.26f, 0f, 0.12f, 1f)
private val LabelMorphEasing = CubicBezierEasing(0.16f, 0f, 0.12f, 1f)
private val SmoothFadeInEasing = CubicBezierEasing(0.12f, 0f, 0.2f, 1f)
private val SmoothFadeOutEasing = CubicBezierEasing(0.28f, 0f, 0.6f, 1f)

private val MotionSizeSpec: FiniteAnimationSpec<IntSize> =
	tween(durationMillis = 400, easing = SmoothLiquidEasing)

// Expand and shrink use the exact same duration and S-curve so outgoing and incoming labels
// trade width 1:1 on every frame without making the outer bar jerk wider or narrower.
private val LabelExpandSizeSpec: FiniteAnimationSpec<IntSize> =
	tween(durationMillis = 330, easing = LabelMorphEasing)

private val LabelShrinkSizeSpec: FiniteAnimationSpec<IntSize> =
	tween(durationMillis = 330, easing = LabelMorphEasing)

private val LabelFadeInSpec: FiniteAnimationSpec<Float> =
	tween(durationMillis = 210, easing = SmoothFadeInEasing)

private val LabelFadeOutSpec: FiniteAnimationSpec<Float> =
	tween(durationMillis = 220, easing = SmoothFadeOutEasing)

private val MotionAlphaSpec: FiniteAnimationSpec<Float>
	@Composable get() = MaterialTheme.motionScheme.defaultEffectsSpec()

private val MotionColorSpec: FiniteAnimationSpec<Color>
	@Composable get() = MaterialTheme.motionScheme.defaultEffectsSpec()

private const val HOVER_HYSTERESIS = 0.58f
private const val HOLD_PICKUP_DELAY_MS = 145L
private const val MAGNIFY_IN_DURATION_MS = 420
private const val MAGNIFY_OUT_DURATION_MS = 460
private const val DRAG_SETTLE_DURATION_MS = 440
private const val WALL_RELEASE_DURATION_MS = 480
private const val TAP_MIN_DURATION_MS = 350
private const val TAP_MAX_DURATION_MS = 400
private const val TAP_PER_TILE_MS = 16f
private const val TAP_LABEL_SWAP_PROGRESS = 0.36f
private const val DRAG_FOLLOW_RATE = 16f
private const val WALL_FOLLOW_RATE = 14f
private const val MAX_WALL_SQUISH_Y = 0.045f
private const val MAG_CONTENT_SCALE = 0.13f

private val BarShape = RoundedCornerShape(50)
private val MaxWallPullDp = 85.dp
private val MaxWallStretchDp = 22.dp
private val MaxWallTranslateDp = 6.dp
private val MaxPillWallPressDp = 4.dp
private val MagPillExtraWidthDp = 16.dp
private val MagPillExtraHeightDp = 17.dp

private class NavBarLayoutState(count: Int) {
	var rowCoords: LayoutCoordinates? = null
	private var lastRowSize: Long = Long.MIN_VALUE
	val itemCoords = arrayOfNulls<LayoutCoordinates>(count)
	private val lastPos = LongArray(count) { Long.MIN_VALUE }
	private val lastSize = LongArray(count) { Long.MIN_VALUE }
	val geomStart = FloatArray(4)
	val geomEnd = FloatArray(4)
	var layoutTick by mutableIntStateOf(0)

	fun onRowPlaced(coords: LayoutCoordinates) {
		val size = coords.size.packedValue
		if (rowCoords !== coords || lastRowSize != size) {
			rowCoords = coords
			lastRowSize = size
			layoutTick++
		}
	}

	fun onItemPlaced(index: Int, coords: LayoutCoordinates) {
		if (index !in itemCoords.indices) return
		val rc = rowCoords
		val pos = if (rc != null && rc.isAttached) {
			rc.localPositionOf(coords, Offset.Zero).packedValue
		} else {
			coords.positionInParent().packedValue
		}
		val size = coords.size.packedValue
		if (itemCoords[index] !== coords || lastPos[index] != pos || lastSize[index] != size) {
			itemCoords[index] = coords
			lastPos[index] = pos
			lastSize[index] = size
			layoutTick++
		}
	}

	fun itemCenterX(index: Int): Float? {
		val rc = rowCoords ?: return null
		if (!rc.isAttached || index !in itemCoords.indices) return null
		val ic = itemCoords[index] ?: return null
		if (!ic.isAttached) return null
		return rc.localPositionOf(ic, Offset.Zero).x + ic.size.width * 0.5f
	}

	fun slotToCenterX(slot: Float, count: Int): Float? {
		if (count <= 0) return null
		val clamped = slot.coerceIn(0f, (count - 1).toFloat())
		val i0 = clamped.toInt().coerceIn(0, count - 1)
		val i1 = (i0 + 1).coerceAtMost(count - 1)
		val c0 = itemCenterX(i0) ?: return null
		val c1 = itemCenterX(i1) ?: return null
		return lerp(c0, c1, clamped - i0)
	}

	fun slotGeometry(slot: Float, count: Int, out: FloatArray): Boolean {
		val rc = rowCoords ?: return false
		if (!rc.isAttached || count <= 0) return false
		val lastIdx = count - 1
		val clamped = slot.coerceIn(0f, lastIdx.toFloat())
		val i0 = clamped.toInt().coerceIn(0, lastIdx)
		val i1 = (i0 + 1).coerceAtMost(lastIdx)
		val frac = (clamped - i0).coerceIn(0f, 1f)
		val c0 = itemCoords[i0]
		val c1 = itemCoords[i1]
		if (c0 == null || !c0.isAttached || c1 == null || !c1.isAttached) return false
		val p0 = rc.localPositionOf(c0, Offset.Zero)
		val p1 = rc.localPositionOf(c1, Offset.Zero)
		val w0 = c0.size.width.toFloat()
		val h0 = c0.size.height.toFloat()
		val w1 = c1.size.width.toFloat()
		val h1 = c1.size.height.toFloat()
		if (w0 <= 0f || h0 <= 0f) return false
		val easedFrac = smoothstep(frac)
		out[0] = lerp(p0.x + w0 * 0.5f, p1.x + w1 * 0.5f, frac)
		out[1] = lerp(p0.y, p1.y, easedFrac)
		out[2] = lerp(w0, w1, easedFrac)
		out[3] = lerp(h0, h1, easedFrac)
		return true
	}
}

private fun findTouchedItemIndex(
	localX: Float,
	selectedIndex: Int,
	edgeMarginPx: Float,
	layoutState: NavBarLayoutState,
	itemCount: Int,
): Int {
	val rCoords = layoutState.rowCoords
	if (rCoords == null || !rCoords.isAttached) return selectedIndex.coerceIn(0, itemCount - 1)
	if (selectedIndex in 0 until itemCount) {
		val selCoords = layoutState.itemCoords[selectedIndex]
		if (selCoords != null && selCoords.isAttached) {
			val selLeft = rCoords.localPositionOf(selCoords, Offset.Zero).x
			val selRight = selLeft + selCoords.size.width
			if (localX in (selLeft - edgeMarginPx)..(selRight + edgeMarginPx)) {
				return selectedIndex
			}
		}
	}
	var closestIdx = selectedIndex.coerceIn(0, itemCount - 1)
	var closestDist = Float.MAX_VALUE
	for (i in 0 until itemCount) {
		val center = layoutState.itemCenterX(i) ?: continue
		val dist = abs(localX - center)
		if (dist < closestDist) {
			closestDist = dist
			closestIdx = i
		}
	}
	return closestIdx
}

/**
 * Maps a desired pill center X (in [NavBarLayoutState.rowCoords] space) onto:
 * - piecewise fractional slot index in `[0, itemCount - 1]` across actual measured item centers
 *   (handles both variable-width labeled tabs and uniform-width unlabeled tabs, in LTR and RTL),
 * - normalized wall stretch in `[-1, +1]` when pulling past the leftmost or rightmost item.
 */
private fun projectDragCenter(
	desiredCenterX: Float,
	layoutState: NavBarLayoutState,
	itemCount: Int,
	maxWallPullPx: Float,
	out: FloatArray,
): Boolean {
	if (itemCount < 2) return false
	val firstCenter = layoutState.itemCenterX(0) ?: return false
	val lastCenter = layoutState.itemCenterX(itemCount - 1) ?: return false
	val isLtr = firstCenter <= lastCenter
	val minX = min(firstCenter, lastCenter)
	val maxX = max(firstCenter, lastCenter)

	val rawWallPx = when {
		desiredCenterX < minX -> desiredCenterX - minX
		desiredCenterX > maxX -> desiredCenterX - maxX
		else -> 0f
	}
	val wallStretch = if (rawWallPx == 0f || maxWallPullPx <= 0f) {
		0f
	} else {
		sign(rawWallPx) * tanh(abs(rawWallPx) / maxWallPullPx)
	}

	val clampedX = desiredCenterX.coerceIn(minX, maxX)
	var slot = 0f
	if (isLtr) {
		if (clampedX <= firstCenter) {
			slot = 0f
		} else if (clampedX >= lastCenter) {
			slot = (itemCount - 1).toFloat()
		} else {
			for (i in 0 until itemCount - 1) {
				val c0 = layoutState.itemCenterX(i) ?: return false
				val c1 = layoutState.itemCenterX(i + 1) ?: return false
				if (clampedX <= c1) {
					val span = (c1 - c0).coerceAtLeast(1f)
					slot = i + ((clampedX - c0) / span).coerceIn(0f, 1f)
					break
				}
			}
		}
	} else {
		if (clampedX >= firstCenter) {
			slot = 0f
		} else if (clampedX <= lastCenter) {
			slot = (itemCount - 1).toFloat()
		} else {
			for (i in 0 until itemCount - 1) {
				val c0 = layoutState.itemCenterX(i) ?: return false
				val c1 = layoutState.itemCenterX(i + 1) ?: return false
				if (clampedX >= c1) {
					val span = (c0 - c1).coerceAtLeast(1f)
					slot = i + ((c0 - clampedX) / span).coerceIn(0f, 1f)
					break
				}
			}
		}
	}
	out[0] = slot
	out[1] = wallStretch
	return true
}

private fun computeHoveredIndex(slot: Float, current: Int, count: Int): Int {
	val clamped = slot.coerceIn(0f, (count - 1).toFloat())
	if (abs(clamped - current) <= HOVER_HYSTERESIS) return current
	return clamped.roundToInt().coerceIn(0, count - 1)
}

private fun smoothstep(x: Float): Float {
	val t = x.coerceIn(0f, 1f)
	return t * t * (3f - 2f * t)
}

@Composable
fun FloatingNavBar(
	items: List<FloatingNavBarItem>,
	selectedId: Int,
	showLabels: Boolean,
	colors: FloatingNavBarColors,
	onItemSelected: (Int) -> Unit,
	onItemReselected: (Int) -> Unit,
	modifier: Modifier = Modifier,
	showContinue: Boolean = false,
	onContinueClick: () -> Unit = {},
	onContinueLongClick: () -> Unit = {},
) {
	if (items.isEmpty()) return
	val cs = MaterialTheme.colorScheme
	val barColor = Color(colors.container)
	val pillColor by animateColorAsState(
		targetValue = Color(colors.selectedContainer),
		animationSpec = MotionColorSpec,
		label = "navPillContainer",
	)
	val selectedContentColor by animateColorAsState(
		targetValue = Color(colors.selectedContent),
		animationSpec = MotionColorSpec,
		label = "navSelectedContent",
	)
	val unselectedContentColor by animateColorAsState(
		targetValue = Color(colors.unselectedContent),
		animationSpec = MotionColorSpec,
		label = "navUnselectedContent",
	)
	val haptic = rememberHapticEffect()
	val view = LocalView.current
	val scope = rememberCoroutineScope()
	val itemIds = remember(items) { items.map { it.id } }
	val layoutState = remember(itemIds) { NavBarLayoutState(items.size) }

	val selectedIndex = items.indexOfFirst { it.id == selectedId }
	val initialIndex = selectedIndex.coerceAtLeast(0)
	var isDragging by remember { mutableStateOf(false) }
	var wasJustDragged by remember { mutableStateOf(false) }
	var hoveredIndex by remember { mutableIntStateOf(initialIndex) }
	var pendingSelectedId by remember { mutableStateOf<Int?>(null) }
	var unappliedDropId by remember { mutableStateOf<Int?>(null) }
	// Which tab currently displays its text label: stays on the previous active menu while the
	// selector pill travels across the bar (both on drag and on tap) and swaps once the pill arrives.
	var labelOwnerId by remember { mutableIntStateOf(selectedId) }

	var targetDragSlot by remember { mutableFloatStateOf(initialIndex.toFloat()) }
	var displaySlot by remember { mutableFloatStateOf(initialIndex.toFloat()) }
	var targetWallStretch by remember { mutableFloatStateOf(0f) }
	var displayWallStretch by remember { mutableFloatStateOf(0f) }

	var flightStartSlot by remember { mutableFloatStateOf(initialIndex.toFloat()) }
	var flightTargetSlot by remember { mutableFloatStateOf(initialIndex.toFloat()) }
	var flightFromDrag by remember { mutableStateOf(false) }
	var dropOffsetCenterX by remember { mutableFloatStateOf(0f) }
	var dropOffsetWidth by remember { mutableFloatStateOf(0f) }
	var dropOffsetHeight by remember { mutableFloatStateOf(0f) }
	val flightProgress = remember { Animatable(1f) }
	val wallStretchAnim = remember { Animatable(0f) }
	val magnifyAnim = remember { Animatable(0f) }
	var hasSeenDrag by remember { mutableStateOf(false) }
	var hasInitializedSlot by remember { mutableStateOf(selectedIndex >= 0) }
	var lastItemIds by remember { mutableStateOf(itemIds) }

	LaunchedEffect(isDragging) {
		if (isDragging) {
			hasSeenDrag = true
			val remaining = (1f - magnifyAnim.value).coerceIn(0f, 1f)
			if (remaining > 0.001f) {
				val durationMs = (MAGNIFY_IN_DURATION_MS * (0.35f + 0.65f * remaining)).roundToInt()
				magnifyAnim.animateTo(
					targetValue = 1f,
					animationSpec = tween(durationMillis = durationMs, easing = SmoothLiquidEasing),
				)
			} else {
				magnifyAnim.snapTo(1f)
			}
		} else if (hasSeenDrag) {
			val remaining = magnifyAnim.value.coerceIn(0f, 1f)
			if (remaining > 0.001f) {
				val durationMs = (MAGNIFY_OUT_DURATION_MS * (0.4f + 0.6f * remaining)).roundToInt()
				magnifyAnim.animateTo(
					targetValue = 0f,
					animationSpec = tween(durationMillis = durationMs, easing = SmoothSettleEasing),
				)
			} else {
				magnifyAnim.snapTo(0f)
			}
		}
	}

	LaunchedEffect(selectedId, pendingSelectedId) {
		val pending = pendingSelectedId ?: return@LaunchedEffect
		if (selectedId == pending) {
			pendingSelectedId = null
		} else {
			delay(300L)
			if (pendingSelectedId == pending) {
				pendingSelectedId = null
			}
		}
	}

	// Smooth frame-synced exponential ease-out while dragging so both pill tracking and container
	// wall-stretching feel fluid and eased rather than raw linear steps.
	LaunchedEffect(isDragging) {
		if (!isDragging) return@LaunchedEffect
		var lastFrameNanos = 0L
		while (isActive) {
			val converged = abs(targetDragSlot - displaySlot) < 0.0005f &&
				abs(targetWallStretch - displayWallStretch) < 0.0005f
			if (converged) {
				displaySlot = targetDragSlot
				displayWallStretch = targetWallStretch
				lastFrameNanos = 0L
				snapshotFlow { targetDragSlot to targetWallStretch }
					.first { (ts, tw) ->
						abs(ts - displaySlot) >= 0.0005f || abs(tw - displayWallStretch) >= 0.0005f
					}
				continue
			}
			withFrameNanos { nanos ->
				val dt = if (lastFrameNanos == 0L) {
					1f / 120f
				} else {
					((nanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
				}
				lastFrameNanos = nanos
				val slotAlpha = 1f - exp(-dt * DRAG_FOLLOW_RATE)
				val wallAlpha = 1f - exp(-dt * WALL_FOLLOW_RATE)
				displaySlot += (targetDragSlot - displaySlot) * slotAlpha
				displayWallStretch += (targetWallStretch - displayWallStretch) * wallAlpha
			}
		}
	}

	val activeMenuId = when {
		pendingSelectedId != null && items.any { it.id == pendingSelectedId } -> pendingSelectedId!!
		else -> selectedId
	}
	val activeMenuIndex = items.indexOfFirst { it.id == activeMenuId }.coerceAtLeast(0)
	if (labelOwnerId != activeMenuId && items.none { it.id == labelOwnerId }) {
		labelOwnerId = activeMenuId
	}

	LaunchedEffect(activeMenuId, activeMenuIndex, isDragging, itemIds) {
		if (isDragging) return@LaunchedEffect
		val itemsChanged = itemIds != lastItemIds
		lastItemIds = itemIds
		val target = activeMenuIndex.toFloat()
		if (!hasInitializedSlot || itemsChanged) {
			if (selectedIndex >= 0) {
				hasInitializedSlot = true
			}
			wasJustDragged = false
			flightFromDrag = false
			dropOffsetCenterX = 0f
			dropOffsetWidth = 0f
			dropOffsetHeight = 0f
			labelOwnerId = activeMenuId
			flightStartSlot = target
			flightTargetSlot = target
			flightProgress.snapTo(1f)
			return@LaunchedEffect
		}
		val fromDrag = wasJustDragged
		wasJustDragged = false
		if (fromDrag) {
			labelOwnerId = activeMenuId
			val needsSettle = abs(dropOffsetCenterX) > 0.25f ||
				abs(dropOffsetWidth) > 0.25f ||
				abs(flightStartSlot - target) > 0.001f
			flightTargetSlot = target
			if (needsSettle) {
				flightFromDrag = true
				flightProgress.snapTo(0f)
				flightProgress.animateTo(
					targetValue = 1f,
					animationSpec = tween(
						durationMillis = DRAG_SETTLE_DURATION_MS,
						easing = SmoothSettleEasing,
					),
				)
			} else {
				flightStartSlot = target
				flightProgress.snapTo(1f)
			}
			dropOffsetCenterX = 0f
			dropOffsetWidth = 0f
			dropOffsetHeight = 0f
			flightFromDrag = false
			return@LaunchedEffect
		}

		val currentPos = lerp(flightStartSlot, flightTargetSlot, flightProgress.value)
		if (abs(currentPos - target) <= 0.35f) {
			labelOwnerId = activeMenuId
		}
		if (abs(currentPos - target) > 0.001f) {
			val dist = abs(target - currentPos)
			val tapDurationMs = (TAP_MIN_DURATION_MS + (dist - 1f).coerceAtLeast(0f) * TAP_PER_TILE_MS)
				.roundToInt()
				.coerceIn(TAP_MIN_DURATION_MS, TAP_MAX_DURATION_MS)
			flightStartSlot = currentPos
			flightTargetSlot = target
			flightFromDrag = false
			flightProgress.snapTo(0f)
			flightProgress.animateTo(
				targetValue = 1f,
				animationSpec = tween(durationMillis = tapDurationMs, easing = SmoothLiquidEasing),
			) {
				if (labelOwnerId != activeMenuId && value >= TAP_LABEL_SWAP_PROGRESS) {
					labelOwnerId = activeMenuId
				}
			}
		} else {
			flightStartSlot = target
			flightTargetSlot = target
			flightProgress.snapTo(1f)
		}
		flightFromDrag = false
		labelOwnerId = activeMenuId
	}

	// Per-frame values are read through functions from draw/layer lambdas only, so the pill
	// animation redraws the bar without recomposing it every frame.
	fun liveSlot() = if (isDragging) {
		displaySlot
	} else {
		lerp(flightStartSlot, flightTargetSlot, flightProgress.value)
	}
	fun liveWallStretch() = if (isDragging) displayWallStretch else wallStretchAnim.value
	fun liveMagnify() = magnifyAnim.value

	val currentItems by rememberUpdatedState(items)
	val currentSelectedId by rememberUpdatedState(selectedId)
	val currentOnItemSelected by rememberUpdatedState(onItemSelected)
	val currentHaptic by rememberUpdatedState(haptic)
	val currentLayoutState by rememberUpdatedState(layoutState)

	Row(
		modifier = modifier.wrapContentWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		// Outer gesture box stays un-transformed so container wall-stretching never feeds back into
		// touch coordinates.
		Box(
			modifier = Modifier
				.wrapContentWidth()
				.pointerInput(Unit) {
					val edgeGrabMarginPx = 6.dp.toPx()
					val maxWallPullPx = MaxWallPullDp.toPx()
					val nearFlyingPillPx = 36.dp.toPx()
					val dragDropletStretchPx = 8.dp.toPx()
					val dragDropletSquishPx = 2.dp.toPx()
					val projection = FloatArray(2)
					val dropStartGeom = FloatArray(4)
					val dropTargetGeom = FloatArray(4)

					awaitEachGesture {
						val down = awaitFirstDown(requireUnconsumed = false)
						// Checked after the down: returning before it suspends makes awaitEachGesture spin forever.
						val itemsSnapshot = currentItems
						if (itemsSnapshot.size < 2) return@awaitEachGesture
						val pendingDrop = unappliedDropId
						if (pendingDrop != null) {
							unappliedDropId = null
							currentOnItemSelected(pendingDrop)
						}
						val pointerId = down.id
						val rCoords = currentLayoutState.rowCoords ?: return@awaitEachGesture
						if (!rCoords.isAttached) return@awaitEachGesture

						val startSelectedIdx = itemsSnapshot
							.indexOfFirst { it.id == currentSelectedId }
							.coerceAtLeast(0)
						val downIndex = findTouchedItemIndex(
							localX = down.position.x,
							selectedIndex = startSelectedIdx,
							edgeMarginPx = edgeGrabMarginPx,
							layoutState = currentLayoutState,
							itemCount = itemsSnapshot.size,
						)

						val pInitial = flightProgress.value
						val isMidTapInitial = !isDragging && !flightFromDrag && pInitial < 0.999f
						val isMidSettleInitial = !isDragging && flightFromDrag && pInitial < 0.999f
						val slotEstimate = if (isDragging) {
							displaySlot
						} else {
							lerp(flightStartSlot, flightTargetSlot, pInitial)
						}
						val pillCenterEstimate = when {
							isMidTapInitial -> {
								val cS = currentLayoutState.slotToCenterX(flightStartSlot, itemsSnapshot.size)
								val cE = currentLayoutState.slotToCenterX(flightTargetSlot, itemsSnapshot.size)
								if (cS != null && cE != null) lerp(cS, cE, pInitial) else null
							}
							isMidSettleInitial -> {
								val cT = currentLayoutState.slotToCenterX(flightTargetSlot, itemsSnapshot.size)
								cT?.plus(dropOffsetCenterX * (1f - pInitial))
							}
							else -> currentLayoutState.slotToCenterX(slotEstimate, itemsSnapshot.size)
						}
						// Holding on the currently active option (or on the pill itself) magnifies
						// and picks up the selector even before sliding horizontally.
						val canHoldPickup = downIndex == startSelectedIdx ||
							(pillCenterEstimate != null && abs(down.position.x - pillCenterEstimate) <= nearFlyingPillPx)
						val holdDeadlineUptime = SystemClock.uptimeMillis() + HOLD_PICKUP_DELAY_MS

						val touchSlop = viewConfiguration.touchSlop
						var dragStarted = false
						var dragOriginX = down.position.x
						var slopOffsetPx = 0f
						var anchorCenterX = 0f
						var latestPos = down.position

						while (!dragStarted) {
							val remainingHoldMs = if (canHoldPickup) {
								holdDeadlineUptime - SystemClock.uptimeMillis()
							} else {
								Long.MAX_VALUE
							}
							val heldLongEnough = canHoldPickup && remainingHoldMs <= 0L
							val triggerChange = if (heldLongEnough) {
								null
							} else {
								val event = if (canHoldPickup) {
									withTimeoutOrNull(remainingHoldMs.coerceAtLeast(1L)) {
										awaitPointerEvent(PointerEventPass.Initial)
									}
								} else {
									awaitPointerEvent(PointerEventPass.Initial)
								}
								if (event == null) {
									continue
								}
								val change = event.changes.firstOrNull { it.id == pointerId }
									?: return@awaitEachGesture
								if (!change.pressed) {
									return@awaitEachGesture
								}
								latestPos = change.position
								val dx = change.position.x - down.position.x
								val dy = change.position.y - down.position.y
								if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
									return@awaitEachGesture
								}
								if (abs(dx) > touchSlop) {
									slopOffsetPx = sign(dx) * touchSlop
									change
								} else {
									continue
								}
							}

							dragStarted = true
							dragOriginX = if (triggerChange == null) latestPos.x else down.position.x
							val pFlight = flightProgress.value
							val isMidTapFlight = !isDragging && !flightFromDrag && pFlight < 0.999f
							val isMidSettle = !isDragging && flightFromDrag && pFlight < 0.999f
							var currentSlotVal = if (isDragging) {
								displaySlot
							} else {
								lerp(flightStartSlot, flightTargetSlot, pFlight)
							}
							val currentWallVal = if (isDragging) displayWallStretch else wallStretchAnim.value
							val flyingCenter = when {
								isMidTapFlight -> {
									val cStart = currentLayoutState.slotToCenterX(flightStartSlot, itemsSnapshot.size)
									val cEnd = currentLayoutState.slotToCenterX(flightTargetSlot, itemsSnapshot.size)
									if (cStart != null && cEnd != null) lerp(cStart, cEnd, pFlight) else null
								}
								isMidSettle -> {
									val cTarget = currentLayoutState.slotToCenterX(flightTargetSlot, itemsSnapshot.size)
									cTarget?.plus(dropOffsetCenterX * (1f - pFlight))
								}
								else -> currentLayoutState.slotToCenterX(currentSlotVal, itemsSnapshot.size)
							}
							if ((isMidTapFlight || isMidSettle) && flyingCenter != null &&
								projectDragCenter(
									desiredCenterX = flyingCenter,
									layoutState = currentLayoutState,
									itemCount = itemsSnapshot.size,
									maxWallPullPx = maxWallPullPx,
									out = projection,
								)
							) {
								currentSlotVal = projection[0]
							}
							anchorCenterX = when {
								flyingCenter != null && abs(down.position.x - flyingCenter) <= nearFlyingPillPx ->
									flyingCenter
								downIndex == startSelectedIdx ->
									currentLayoutState.itemCenterX(startSelectedIdx) ?: down.position.x
								else ->
									currentLayoutState.itemCenterX(downIndex) ?: down.position.x
							}

							view.parent?.requestDisallowInterceptTouchEvent(true)
							triggerChange?.consume()

							displaySlot = currentSlotVal
							displayWallStretch = currentWallVal

							val postSlopDx = (latestPos.x - dragOriginX) - slopOffsetPx
							if (projectDragCenter(
									desiredCenterX = anchorCenterX + postSlopDx,
									layoutState = currentLayoutState,
									itemCount = itemsSnapshot.size,
									maxWallPullPx = maxWallPullPx,
									out = projection,
								)
							) {
								targetDragSlot = projection[0]
								targetWallStretch = projection[1]
							} else {
								targetDragSlot = downIndex.toFloat()
								targetWallStretch = 0f
							}

							val initialHover = if (downIndex == startSelectedIdx) {
								startSelectedIdx
							} else {
								downIndex.coerceIn(0, itemsSnapshot.lastIndex)
							}
							hoveredIndex = initialHover
							pendingSelectedId = null
							isDragging = true
							if (initialHover != startSelectedIdx) {
								currentHaptic(HapticEffect.TICK)
							}
						}

						var releasedNormally = false
						try {
							while (true) {
								val event = awaitPointerEvent(PointerEventPass.Initial)
								val change = event.changes.firstOrNull { it.id == pointerId } ?: break
								change.consume()

								val liveItems = currentItems
								if (liveItems.size < 2) break

								val postSlopDx = (change.position.x - dragOriginX) - slopOffsetPx
								if (projectDragCenter(
										desiredCenterX = anchorCenterX + postSlopDx,
										layoutState = currentLayoutState,
										itemCount = liveItems.size,
										maxWallPullPx = maxWallPullPx,
										out = projection,
									)
								) {
									targetDragSlot = projection[0]
									targetWallStretch = projection[1]
									val nextHover = computeHoveredIndex(
										slot = projection[0],
										current = hoveredIndex,
										count = liveItems.size,
									)
									if (nextHover != hoveredIndex) {
										hoveredIndex = nextHover
										currentHaptic(HapticEffect.TICK)
									}
								}

								if (!change.pressed) {
									releasedNormally = true
									break
								}
							}
						} finally {
							view.parent?.requestDisallowInterceptTouchEvent(false)
							val endSlot = displaySlot
							val endWall = displayWallStretch
							targetWallStretch = 0f
							val liveItems = currentItems
							val lastIdx = (liveItems.size - 1).coerceAtLeast(0)
							val clampedHover = hoveredIndex.coerceIn(0, lastIdx)
							val targetItem = liveItems.getOrNull(clampedHover)
							val resolvedTargetIdx = if (releasedNormally && targetItem != null) {
								clampedHover
							} else {
								liveItems.indexOfFirst { it.id == currentSelectedId }.coerceIn(0, lastIdx)
							}

							// Capture exact pixel delta between the dragged pill right before release
							// and the destination slot so dropping onto a tab whose label is about to
							// expand has 0px jump and eases smoothly into place.
							var capturedDx = 0f
							var capturedDw = 0f
							var capturedDh = 0f
							if (liveItems.isNotEmpty() &&
								currentLayoutState.slotGeometry(endSlot, liveItems.size, dropStartGeom) &&
								currentLayoutState.slotGeometry(resolvedTargetIdx.toFloat(), liveItems.size, dropTargetGeom)
							) {
								val clampedEnd = endSlot.coerceIn(0f, lastIdx.toFloat())
								val endFrac = (clampedEnd - clampedEnd.toInt().coerceIn(0, lastIdx)).coerceIn(0f, 1f)
								val endMid = sin(endFrac * PI.toFloat())
								capturedDx = dropStartGeom[0] - dropTargetGeom[0]
								capturedDw = (dropStartGeom[2] + endMid * dragDropletStretchPx) - dropTargetGeom[2]
								capturedDh = (dropStartGeom[3] - endMid * dragDropletSquishPx) - dropTargetGeom[3]
							}

							val switchedTab = releasedNormally && targetItem != null && targetItem.id != currentSelectedId
							val targetId = targetItem?.id ?: currentSelectedId
							if (releasedNormally && targetItem != null && !switchedTab) {
								currentHaptic(HapticEffect.GESTURE_END)
							}
							scope.launch(start = CoroutineStart.UNDISPATCHED) {
								dropOffsetCenterX = capturedDx
								dropOffsetWidth = capturedDw
								dropOffsetHeight = capturedDh
								flightStartSlot = endSlot
								flightTargetSlot = resolvedTargetIdx.toFloat()
								flightFromDrag = true
								wasJustDragged = true
								if (switchedTab) {
									pendingSelectedId = targetId
									unappliedDropId = targetId
									labelOwnerId = targetId
								}
								flightProgress.snapTo(0f)
								wallStretchAnim.snapTo(endWall)
								isDragging = false
								if (endWall != 0f) {
									launch {
										wallStretchAnim.animateTo(
											targetValue = 0f,
											animationSpec = tween(
												durationMillis = WALL_RELEASE_DURATION_MS,
												easing = SmoothSettleEasing,
											),
										)
									}
								}
								if (switchedTab) {
									// Let Compose commit the release frame and lock in animation start
									// timestamps for the label morph + pill settle before FragmentManager
									// commits the target tab on the main thread.
									withFrameNanos { }
									withFrameNanos { }
									if (unappliedDropId == targetId && !isDragging) {
										unappliedDropId = null
										currentOnItemSelected(targetId)
									}
								}
							}
						}
					}
				},
		) {
			CompositionLocalProvider(LocalContentColor provides cs.onSurface) {
				Box(
					modifier = Modifier
						.wrapContentWidth()
						.graphicsLayer {
							val w = size.width
							val liveWallStretch = liveWallStretch()
							if (w > 0f && liveWallStretch != 0f) {
								val mag = abs(liveWallStretch)
								val stretchPx = mag * MaxWallStretchDp.toPx()
								// Anchor on the opposite edge so the whole container stretches outward
								// in the direction of the wall the pill hit.
								transformOrigin = TransformOrigin(
									pivotFractionX = if (liveWallStretch < 0f) 1f else 0f,
									pivotFractionY = 0.5f,
								)
								scaleX = 1f + stretchPx / w
								scaleY = 1f - mag * MAX_WALL_SQUISH_Y
								translationX = liveWallStretch * MaxWallTranslateDp.toPx()
							} else {
								scaleX = 1f
								scaleY = 1f
								translationX = 0f
							}
						},
				) {
					// Draw the pill bar background and outline on a sibling backdrop rather than
					// Material Surface so the magnified selector pill can swell cleanly up to/past
					// the rim without Surface's RoundedCornerShape clip slicing its corners.
					Spacer(
						modifier = Modifier
							.matchParentSize()
							.background(color = barColor, shape = BarShape)
							.border(width = 1.dp, color = cs.outlineVariant, shape = BarShape),
					)
					Row(
						modifier = Modifier
							.heightIn(min = 64.dp)
							.onPlaced { layoutState.onRowPlaced(it) }
							.drawBehind {
								layoutState.layoutTick
								val rCoords = layoutState.rowCoords
								if (rCoords == null || !rCoords.isAttached || items.isEmpty()) return@drawBehind

								val liveSlot = liveSlot()
								val wallPressPx = liveWallStretch() * MaxPillWallPressDp.toPx()
								val mag = liveMagnify().coerceAtLeast(0f)
								val pFlight = flightProgress.value
								val isTapFlight = !isDragging && !flightFromDrag && pFlight < 0.999f
								val isDragSettle = !isDragging && flightFromDrag && pFlight < 0.999f
								val baseCenter: Float
								val baseTop: Float
								val baseWidth: Float
								val baseHeight: Float
								val stretchX: Float
								val squishY: Float

								when {
									isTapFlight -> {
										if (!layoutState.slotGeometry(flightStartSlot, items.size, layoutState.geomStart) ||
											!layoutState.slotGeometry(flightTargetSlot, items.size, layoutState.geomEnd)
										) {
											return@drawBehind
										}
										val p = pFlight.coerceIn(0f, 1f)
										val dist = abs(flightTargetSlot - flightStartSlot)
										val mid = sin(p * PI.toFloat())
										val extraTiles = (dist - 1f).coerceAtLeast(0f)
										val peakStretchDp = (6f + extraTiles * 6f).coerceAtMost(24f).dp
										val peakSquishDp = (1.5f + extraTiles * 0.7f).coerceAtMost(3.5f).dp
										stretchX = mid * peakStretchDp.toPx()
										squishY = mid * peakSquishDp.toPx()

										val s = layoutState.geomStart
										val e = layoutState.geomEnd
										val easedP = smoothstep(p)
										val linearWidth = lerp(s[2], e[2], easedP)
										val compactWidth = min(s[2], e[2])
										val multiTileFactor = (extraTiles / 1.5f).coerceIn(0f, 1f)

										baseCenter = lerp(s[0], e[0], p) + wallPressPx
										baseTop = lerp(s[1], e[1], easedP)
										baseWidth = lerp(linearWidth, compactWidth, mid * multiTileFactor * 0.50f)
										baseHeight = lerp(s[3], e[3], easedP)
									}
									isDragSettle -> {
										if (!layoutState.slotGeometry(flightTargetSlot, items.size, layoutState.geomEnd)) {
											return@drawBehind
										}
										val rem = (1f - pFlight).coerceIn(0f, 1f)
										val e = layoutState.geomEnd
										val dh = dropOffsetHeight * rem
										stretchX = 0f
										squishY = 0f
										baseCenter = e[0] + dropOffsetCenterX * rem + wallPressPx
										baseTop = e[1] - dh * 0.5f
										baseWidth = e[2] + dropOffsetWidth * rem
										baseHeight = e[3] + dh
									}
									else -> {
										if (!layoutState.slotGeometry(liveSlot, items.size, layoutState.geomStart)) {
											return@drawBehind
										}
										val lastIdx = items.lastIndex
										val clampedSlot = liveSlot.coerceIn(0f, lastIdx.toFloat())
										val frac = (clampedSlot - clampedSlot.toInt().coerceIn(0, lastIdx)).coerceIn(0f, 1f)
										// Subtle liquid droplet stretch while bridging two slots; zero at rest.
										val mid = sin(frac * PI.toFloat())
										stretchX = mid * 8.dp.toPx()
										squishY = mid * 2.dp.toPx()

										val g = layoutState.geomStart
										baseCenter = g[0] + wallPressPx
										baseTop = g[1]
										baseWidth = g[2]
										baseHeight = g[3]
									}
								}

								val magExtraW = mag * MagPillExtraWidthDp.toPx()
								val magExtraH = mag * MagPillExtraHeightDp.toPx()

								val pillWidth = (baseWidth + stretchX + magExtraW).coerceAtLeast(1f)
								val pillHeight = (baseHeight - squishY + magExtraH).coerceAtLeast(1f)
								val edgeInset = lerp(3.dp.toPx(), (-0.5).dp.toPx(), mag.coerceIn(0f, 1f))
								val minLeft = edgeInset
								val maxLeft = max(minLeft, size.width - pillWidth - edgeInset)
								val pillLeft = (baseCenter - pillWidth * 0.5f).coerceIn(minLeft, maxLeft)
								val pillTop = baseTop + squishY * 0.5f - magExtraH * 0.5f
								val radius = min(pillWidth, pillHeight) * 0.5f
								val cornerRadius = CornerRadius(radius, radius)

								if (mag > 0.005f) {
									val clampedMag = mag.coerceIn(0f, 1f)
									val haloPad = 2.dp.toPx() * clampedMag
									val haloRadius = radius + haloPad
									drawRoundRect(
										color = Color.Black.copy(alpha = 0.09f * clampedMag),
										topLeft = Offset(pillLeft - haloPad, pillTop - haloPad),
										size = Size(pillWidth + haloPad * 2f, pillHeight + haloPad * 2f),
										cornerRadius = CornerRadius(haloRadius, haloRadius),
									)
								}

								drawRoundRect(
									color = pillColor,
									topLeft = Offset(pillLeft, pillTop),
									size = Size(pillWidth, pillHeight),
									cornerRadius = cornerRadius,
								)

								if (mag > 0.005f) {
									drawRoundRect(
										color = selectedContentColor.copy(alpha = 0.14f * mag.coerceIn(0f, 1f)),
										topLeft = Offset(pillLeft, pillTop),
										size = Size(pillWidth, pillHeight),
										cornerRadius = cornerRadius,
										style = Stroke(width = 1.dp.toPx()),
									)
								}
							}
							.padding(horizontal = 8.dp, vertical = 8.dp),
						horizontalArrangement = Arrangement.spacedBy(4.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						items.forEachIndexed { index, item ->
							val iconChecked = if (isDragging) {
								index == hoveredIndex.coerceIn(0, items.lastIndex)
							} else {
								item.id == activeMenuId
							}
							val pillCoverage = {
								smoothstep(1f - (abs(liveSlot() - index) / 0.65f).coerceIn(0f, 1f))
							}
							key(item.id) {
								FloatingNavItem(
									item = item,
									selected = item.id == activeMenuId,
									iconChecked = iconChecked,
									showLabel = showLabels && item.id == labelOwnerId,
									contentColor = {
										lerpColor(unselectedContentColor, selectedContentColor, pillCoverage())
									},
									magnification = { liveMagnify().coerceAtLeast(0f) * pillCoverage() },
									modifier = Modifier.onPlaced { layoutState.onItemPlaced(index, it) },
									onClick = {
										val pendingDrop = unappliedDropId
										if (pendingDrop != null) {
											unappliedDropId = null
											onItemSelected(pendingDrop)
										}
										// Selection is routed through the host NavigationBarView, whose
										// listener (MainNavigationDelegate) already performs the CONFIRM
										// haptic — firing one here too would double-buzz. Reselecting the
										// current tab stays silent.
										if (item.id == activeMenuId || item.id == pendingDrop) {
											onItemReselected(item.id)
										} else {
											pendingSelectedId = null
											onItemSelected(item.id)
										}
									},
								)
							}
						}
					}
				}
			}
		}
		// A standalone, pill-coloured circular "continue reading" button living next to the
		// floating bar (like the search FAB in Tomato). It animates in/out smoothly and slides
		// along as the bar resizes, so it always feels part of the same floating toolbar.
		AnimatedVisibility(
			visible = showContinue,
			modifier = Modifier.graphicsLayer {
				val liveWallStretch = liveWallStretch()
				translationX = when {
					liveWallStretch > 0f ->
						liveWallStretch * (MaxWallStretchDp.toPx() + MaxWallTranslateDp.toPx())
					liveWallStretch < 0f ->
						liveWallStretch * MaxWallTranslateDp.toPx() * 0.35f
					else -> 0f
				}
			},
			enter = fadeIn(animationSpec = MotionAlphaSpec) +
				expandHorizontally(animationSpec = MotionSizeSpec, expandFrom = Alignment.Start),
			exit = fadeOut(animationSpec = MotionAlphaSpec) +
				shrinkHorizontally(animationSpec = MotionSizeSpec, shrinkTowards = Alignment.Start),
		) {
			FloatingContinueButton(
				colors = colors,
				onClick = {
					haptic(HapticEffect.CONFIRM)
					onContinueClick()
				},
				onLongClick = {
					haptic(HapticEffect.LONG_PRESS)
					onContinueLongClick()
				},
			)
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FloatingContinueButton(
	colors: FloatingNavBarColors,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
) {
	val container by animateColorAsState(
		targetValue = Color(colors.selectedContainer),
		animationSpec = MotionColorSpec,
		label = "continueContainer",
	)
	val content by animateColorAsState(
		targetValue = Color(colors.selectedContent),
		animationSpec = MotionColorSpec,
		label = "continueContent",
	)
	val label = stringResource(R.string.continue_reading)
	val tooltipState = rememberTooltipState()
	TooltipBox(
		positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
		tooltip = { PlainTooltip { Text(label) } },
		state = tooltipState,
	) {
		Surface(
			// M3 Expressive square FAB: 16dp corner on a 56dp container
			shape = RoundedCornerShape(16.dp),
			color = container,
			contentColor = content,
			border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
			modifier = Modifier
				.size(56.dp)
				.semantics { contentDescription = label },
		) {
			Box(
				contentAlignment = Alignment.Center,
				modifier = Modifier.combinedClickable(
					onClick = onClick,
					onLongClick = onLongClick,
				),
			) {
				Icon(
					painter = painterResource(R.drawable.ic_read),
					contentDescription = null,
					tint = content,
					modifier = Modifier.size(24.dp),
				)
			}
		}
	}
}

@Composable
private fun FloatingNavItem(
	item: FloatingNavBarItem,
	selected: Boolean,
	iconChecked: Boolean,
	showLabel: Boolean,
	contentColor: () -> Color,
	magnification: () -> Float,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val title = stringResource(item.titleRes)
	val interactionSource = remember { MutableInteractionSource() }

	Box(
		modifier = modifier
			.height(48.dp)
			.clickable(
				interactionSource = interactionSource,
				indication = null,
				onClick = onClick,
			)
			.semantics {
				this.selected = selected
				role = Role.Tab
				contentDescription = title
			},
		contentAlignment = Alignment.Center,
	) {
		Row(
			modifier = Modifier
				.graphicsLayer {
					val magnification = magnification()
					if (magnification > 0.001f) {
						val s = 1f + MAG_CONTENT_SCALE * magnification
						scaleX = s
						scaleY = s
					} else {
						scaleX = 1f
						scaleY = 1f
					}
				}
				.padding(horizontal = 14.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.Center,
		) {
			BadgedBox(
				badge = {
					if (item.badgeCount > 0) {
						Badge { Text(text = if (item.badgeCount > 99) "99+" else item.badgeCount.toString()) }
					} else if (item.badgeCount < 0) {
						Badge()
					}
				},
			) {
				// Use a real ImageView so the AnimatedStateListDrawable's enter/leave morphs
				// (avd_*_enter / avd_*_leave) actually tick. Painting an AVD onto Compose's
				// canvas via a custom Painter doesn't reliably drive the platform animator —
				// ImageView does, because it's the same path the native BottomNavigationView uses.
				NavIcon(
					resId = item.icon,
					selected = iconChecked,
					tint = contentColor,
					modifier = Modifier.size(24.dp),
				)
			}
			AnimatedVisibility(
				visible = showLabel,
				enter = expandHorizontally(
					animationSpec = LabelExpandSizeSpec,
					expandFrom = Alignment.Start,
				) + fadeIn(animationSpec = LabelFadeInSpec),
				exit = shrinkHorizontally(
					animationSpec = LabelShrinkSizeSpec,
					shrinkTowards = Alignment.Start,
				) + fadeOut(animationSpec = LabelFadeOutSpec),
			) {
				// BasicText's color lambda is read at draw time, so the label recolors without recomposing.
				BasicText(
					text = title,
					color = { contentColor() },
					style = LocalTextStyle.current.merge(fontSize = 14.sp, lineHeight = 20.sp),
					maxLines = 1,
					modifier = Modifier.padding(start = 8.dp),
				)
			}
		}
	}
}

private val SELECTOR_STATE_CHECKED = intArrayOf(android.R.attr.state_checked)
private val SELECTOR_STATE_UNCHECKED = intArrayOf(-android.R.attr.state_checked)

/**
 * Renders a selector drawable (state-list with `<animated-selector>` transitions) inside
 * Compose by hosting a real [ImageView]. We use ImageView rather than a custom [Painter]
 * because `AnimatedVectorDrawable`'s animator pipeline doesn't reliably tick when painted
 * onto Compose's generic canvas — wrapping in a View matches the path the platform
 * `BottomNavigationView` uses, where these morphs are known to work.
 *
 * The [selected] flag drives the drawable state via [ImageView.setImageState], which is
 * what triggers the `<transition>` AVD between the normal and checked items.
 */
@Composable
private fun NavIcon(
	@DrawableRes resId: Int,
	selected: Boolean,
	tint: () -> Color,
	modifier: Modifier = Modifier,
) {
	val lastTint = remember { intArrayOf(0) }
	AndroidView(
		modifier = modifier,
		factory = { ctx ->
			ImageView(ctx).apply {
				scaleType = ImageView.ScaleType.FIT_CENTER
				setImageResource(resId)
				// Prime initial state without a transition. Setting state twice (empty,
				// then the real state) suppresses the first-paint morph that would
				// otherwise fire on inflate.
				setImageState(IntArray(0), false)
				jumpDrawablesToCurrentState()
			}
		},
		update = { iv ->
			val targetResId = when {
				resId == R.drawable.ic_explore_selector && selected -> R.drawable.ic_explore_checked
				resId == R.drawable.ic_explore_selector -> R.drawable.ic_explore_normal
				else -> resId
			}
			val resourceChanged = iv.tag != targetResId
			if (resourceChanged) {
				iv.setImageResource(targetResId)
				iv.tag = targetResId
			}
			val targetState = if (selected) SELECTOR_STATE_CHECKED else SELECTOR_STATE_UNCHECKED
			if (resourceChanged || iv.isSelected != selected) {
				iv.isSelected = selected
				iv.isActivated = selected
				iv.setImageState(targetState, false)
			}
			// update re-runs on its own whenever tint() changes (AndroidView observes its reads).
			// The color filter overrides any drawable tint, so it alone decides the icon color.
			val tintColor = tint().toArgb()
			if (resourceChanged || lastTint[0] != tintColor || iv.colorFilter == null) {
				lastTint[0] = tintColor
				iv.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
			}
		},
	)
}
