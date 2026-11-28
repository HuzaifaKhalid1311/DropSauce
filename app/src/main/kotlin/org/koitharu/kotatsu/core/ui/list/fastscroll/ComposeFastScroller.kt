package org.koitharu.kotatsu.core.ui.list.fastscroll

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ancestors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.util.SpringPopAnimator
import org.koitharu.kotatsu.core.util.ext.animatorDurationScale
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.main.ui.owners.BottomNavOwner
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private const val SCROLLBAR_HIDE_DELAY_MS = 1000L
private const val TRACK_SNAP_RANGE_PX = 5f
private const val SECTION_TICK_INTERVAL_MS = 80L

/**
 * Compose twin of [FastScroller], matching History's scrollbar (`e10b724a`) in metrics, colours,
 * springs, right-pivoted label pill, nav-pill clearance, and haptic ticks. Only grabs touches on
 * the thumb itself (± touch slop) so buttons and swipe gestures near the right edge stay clickable.
 */
@Composable
fun ComposeFastScroller(
	listState: LazyListState,
	totalItemsCount: Int,
	sectionTitleAt: (Int) -> String?,
	topContentPadding: Dp,
	bottomContentPadding: Dp,
	endInset: Dp = 0.dp,
	enabled: Boolean = true,
	onDragStateChanged: (Boolean) -> Unit = {},
	modifier: Modifier = Modifier,
) {
	if (!enabled) return

	val context = LocalContext.current
	val view = LocalView.current
	val density = LocalDensity.current
	val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
	val scope = rememberCoroutineScope()
	val currentSectionTitleAt by rememberUpdatedState(sectionTitleAt)
	val currentOnDragStateChanged by rememberUpdatedState(onDragStateChanged)
	val touchSlopPx = remember(context) { ViewConfiguration.get(context).scaledTouchSlop.toFloat() }

	val handleHeight = dimensionResource(R.dimen.fastscroll_handle_height)
	val thinWidth = dimensionResource(R.dimen.fastscroll_handle_width)
	val thickWidth = dimensionResource(R.dimen.fastscroll_handle_width_grabbed)
	val scrollbarPaddingStart = dimensionResource(R.dimen.fastscroll_scrollbar_padding_start)
	val scrollbarPaddingEnd = dimensionResource(R.dimen.fastscroll_scrollbar_padding_end)
	val scrollerOffset = dimensionResource(R.dimen.grid_spacing_outer)
	val marginTop = dimensionResource(R.dimen.fastscroll_scrollbar_margin_top)
	val marginBottom = dimensionResource(R.dimen.fastscroll_scrollbar_margin_bottom)
	val bubbleMinSize = dimensionResource(R.dimen.fastscroll_bubble_height_small)
	val bubblePadHorizontal = dimensionResource(R.dimen.fastscroll_bubble_padding_small)

	val handleHeightPx = with(density) { handleHeight.toPx() }
	val thinWidthPx = with(density) { thinWidth.toPx() }
	val thickWidthPx = with(density) { thickWidth.toPx() }
	val thumbSlotWidthPx = ceil(thickWidthPx * 1.25f)
	val thumbSlotWidth = with(density) { thumbSlotWidthPx.toDp() }
	val scrollbarSlidePx = with(density) { scrollbarPaddingEnd.toPx() }
	val topPadPx = with(density) { (topContentPadding + marginTop).toPx() }
	val bottomPadBasePx = with(density) { bottomContentPadding.toPx() }
	val marginBottomPx = with(density) { marginBottom.toPx() }

	val colors = MaterialTheme.colorScheme
	val thumbColor = colors.primary
	val bubbleColor = colors.primary
	val bubbleTextColor = colors.onPrimary

	var containerHeightPx by remember { mutableIntStateOf(0) }
	var navOverlapInsetPx by remember { mutableIntStateOf(0) }

	// Re-measure live window coordinates every pre-draw so non-touch flings and programmatic app-bar
	// expansions keep the thumb above both floating and legacy bottom navigation bars.
	DisposableEffect(view, containerHeightPx) {
		val locationBuf = IntArray(2)
		val preDraw = ViewTreeObserver.OnPreDrawListener {
			if (containerHeightPx <= 0 || !view.isAttachedToWindow) return@OnPreDrawListener true
			val tagged = view.ancestors.firstNotNullOfOrNull {
				(it as? View)?.getTag(R.id.tag_bottom_nav_overlap) as? Int
			}
			val navBarPx = tagged ?: run {
				val bar = (context.findActivity() as? BottomNavOwner)?.bottomNav
				if (bar != null && bar.isShown) {
					val parentHeight = (bar.parent as? View)?.height ?: 0
					(parentHeight - bar.top - bar.translationY).roundToInt().coerceAtLeast(0)
				} else {
					0
				}
			}
			view.getLocationInWindow(locationBuf)
			val windowTopPx = locationBuf[1]
			val rootHeight = view.rootView.height
			val overlap = (navBarPx - (rootHeight - windowTopPx - containerHeightPx)).coerceAtLeast(0)
			if (overlap != navOverlapInsetPx) {
				navOverlapInsetPx = overlap
			}
			true
		}
		view.viewTreeObserver.addOnPreDrawListener(preDraw)
		onDispose {
			view.viewTreeObserver.removeOnPreDrawListener(preDraw)
		}
	}

	val insetTopPx = topPadPx.coerceAtMost(containerHeightPx.toFloat())
	val insetBottomPx = (maxOf(bottomPadBasePx, navOverlapInsetPx.toFloat()) + marginBottomPx)
		.coerceIn(0f, (containerHeightPx - insetTopPx).coerceAtLeast(0f))
	val trackHeightPx = (containerHeightPx - insetTopPx - insetBottomPx).coerceAtLeast(0f)

	val canScroll by remember(listState) {
		derivedStateOf { listState.canScrollForward || listState.canScrollBackward }
	}
	val isScrolling by remember(listState) {
		derivedStateOf { listState.isScrollInProgress }
	}

	var isGrabbed by remember { mutableStateOf(false) }
	var isScrollbarShown by remember { mutableStateOf(false) }
	var isBubbleShown by remember { mutableStateOf(false) }
	var bubbleText by remember { mutableStateOf("") }
	var bubbleHeightPx by remember { mutableIntStateOf(with(density) { bubbleMinSize.roundToPx() }) }
	var dragThumbY by remember { mutableFloatStateOf(0f) }

	LaunchedEffect(isScrolling, isGrabbed, canScroll) {
		if (!canScroll && !isGrabbed) {
			isScrollbarShown = false
			return@LaunchedEffect
		}
		if (isScrolling || isGrabbed) {
			isScrollbarShown = true
		} else if (isScrollbarShown) {
			delay(SCROLLBAR_HIDE_DELAY_MS)
			isScrollbarShown = false
		}
	}

	val scrollbarAnimMs = remember(context) {
		(context.resources.getInteger(R.integer.config_defaultAnimTime) * context.animatorDurationScale)
			.toInt()
			.coerceAtLeast(0)
	}
	val scrollbarProgress by animateFloatAsState(
		targetValue = if (isScrollbarShown) 1f else 0f,
		animationSpec = tween(durationMillis = scrollbarAnimMs),
		label = "scrollbarVisibility",
	)

	val thumbExpansion = remember { Animatable(0f) }
	LaunchedEffect(isGrabbed) {
		thumbExpansion.animateTo(
			targetValue = if (isGrabbed) 1f else 0f,
			animationSpec = spring(
				dampingRatio = SpringPopAnimator.FAST_SPATIAL_DAMPING,
				stiffness = SpringPopAnimator.FAST_SPATIAL_STIFFNESS,
				visibilityThreshold = 1f / 64f,
			),
		)
	}

	val bubbleScale = remember { Animatable(0.5f) }
	val bubbleAlpha = remember { Animatable(0f) }
	LaunchedEffect(isBubbleShown) {
		if (isBubbleShown) {
			launch {
				bubbleScale.animateTo(
					targetValue = 1f,
					animationSpec = spring(
						dampingRatio = SpringPopAnimator.FAST_SPATIAL_DAMPING,
						stiffness = SpringPopAnimator.FAST_SPATIAL_STIFFNESS,
					),
				)
			}
			launch {
				bubbleAlpha.animateTo(
					targetValue = 1f,
					animationSpec = spring(
						dampingRatio = Spring.DampingRatioNoBouncy,
						stiffness = SpringPopAnimator.FAST_EFFECTS_STIFFNESS,
					),
				)
			}
		} else {
			launch {
				bubbleScale.animateTo(
					targetValue = 0.5f,
					animationSpec = spring(
						dampingRatio = Spring.DampingRatioNoBouncy,
						stiffness = SpringPopAnimator.FAST_EFFECTS_STIFFNESS,
					),
				)
			}
			launch {
				bubbleAlpha.animateTo(
					targetValue = 0f,
					animationSpec = spring(
						dampingRatio = Spring.DampingRatioNoBouncy,
						stiffness = SpringPopAnimator.FAST_EFFECTS_STIFFNESS,
					),
				)
			}
		}
	}

	val listProportion by remember(listState, totalItemsCount) {
		derivedStateOf {
			computeLazyScrollMetrics(listState, totalItemsCount)?.proportion ?: 0f
		}
	}

	val maxThumbTravel = (trackHeightPx - handleHeightPx).coerceAtLeast(0f)
	val thumbOffsetInTrack = if (isGrabbed) {
		dragThumbY.coerceIn(0f, maxThumbTravel)
	} else {
		(listProportion * maxThumbTravel).coerceIn(0f, maxThumbTravel)
	}
	val thumbTopPx = insetTopPx + thumbOffsetInTrack
	val thumbCenterY = thumbTopPx + handleHeightPx / 2f
	val bubbleTopPx = if (trackHeightPx >= bubbleHeightPx) {
		(thumbCenterY - bubbleHeightPx / 2f).coerceIn(
			insetTopPx,
			(insetTopPx + trackHeightPx - bubbleHeightPx).coerceAtLeast(insetTopPx),
		)
	} else {
		insetTopPx
	}

	Box(
		modifier = modifier
			.fillMaxSize()
			.padding(end = endInset)
			.onSizeChanged { containerHeightPx = it.height },
	) {
		if (bubbleAlpha.value > 0.005f && bubbleText.isNotEmpty()) {
			Box(
				modifier = Modifier
					.align(Alignment.TopEnd)
					.padding(end = scrollerOffset + thumbSlotWidth + scrollbarPaddingStart)
					.onSizeChanged { bubbleHeightPx = it.height }
					.graphicsLayer {
						translationY = bubbleTopPx
						scaleX = bubbleScale.value
						scaleY = bubbleScale.value
						alpha = bubbleAlpha.value.coerceIn(0f, 1f)
						transformOrigin = TransformOrigin(if (isRtl) 0f else 1f, 0.5f)
					}
					.defaultMinSize(minWidth = bubbleMinSize, minHeight = bubbleMinSize)
					.widthIn(max = 240.dp)
					.background(bubbleColor, CircleShape)
					.padding(horizontal = bubblePadHorizontal),
				contentAlignment = Alignment.Center,
			) {
				Text(
					text = bubbleText,
					style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
					color = bubbleTextColor,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					textAlign = TextAlign.Center,
				)
			}
		}

		val touchZoneWidth = scrollbarPaddingStart + thumbSlotWidth + scrollerOffset
		val currentTrackHeightPx by rememberUpdatedState(trackHeightPx)
		val currentInsetTopPx by rememberUpdatedState(insetTopPx)
		val currentThumbTopPx by rememberUpdatedState(thumbTopPx)
		val currentTotalItems by rememberUpdatedState(totalItemsCount)
		val currentIsVisible by rememberUpdatedState(scrollbarProgress > 0.05f)

		Box(
			modifier = Modifier
				.align(Alignment.TopEnd)
				.fillMaxHeight()
				.width(touchZoneWidth)
				.pointerInput(listState) {
					var dragSection: String? = null
					var grabOffsetInThumb = handleHeightPx / 2f
					var lastTickAt = 0L

					fun applyTouchY(localY: Float) {
						val usableTrack = currentTrackHeightPx
						if (usableTrack <= 0f) return
						val maxTravel = (usableTrack - handleHeightPx).coerceAtLeast(0f)
						val clampedThumb = (localY - currentInsetTopPx - grabOffsetInThumb).coerceIn(0f, maxTravel)
						dragThumbY = clampedThumb

						val proportion = when {
							maxTravel <= 0f -> 0f
							clampedThumb <= 0f -> 0f
							clampedThumb >= maxTravel - TRACK_SNAP_RANGE_PX -> 1f
							else -> (clampedThumb / maxTravel).coerceIn(0f, 1f)
						}

						val count = currentTotalItems
						if (count > 0) {
							val targetIndex = (proportion * (count - 1)).roundToInt().coerceIn(0, count - 1)
							val section = currentSectionTitleAt(targetIndex).orEmpty()
							if (section.isEmpty()) {
								isBubbleShown = false
							} else {
								val now = SystemClock.uptimeMillis()
								if (dragSection != null && section != dragSection && now - lastTickAt >= SECTION_TICK_INTERVAL_MS) {
									lastTickAt = now
									view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
								}
								dragSection = section
								bubbleText = section
								isBubbleShown = true
							}

							scope.launch {
								scrollLazyListToProportion(listState, proportion, count)
							}
						}
					}

					awaitEachGesture {
						val down = awaitFirstDown(requireUnconsumed = false)
						val thumbStart = currentThumbTopPx - touchSlopPx
						val thumbEnd = currentThumbTopPx + handleHeightPx + touchSlopPx
						// Only grab when pressing the thumb itself: taps on Read buttons, tip [X], or
						// swipe gestures elsewhere along the right edge pass through unconsumed.
						if (!currentIsVisible || down.position.y !in thumbStart..thumbEnd) {
							return@awaitEachGesture
						}
						grabOffsetInThumb = (down.position.y - currentThumbTopPx).coerceIn(0f, handleHeightPx)
						down.consume()
						view.parent?.requestDisallowInterceptTouchEvent(true)
						isGrabbed = true
						currentOnDragStateChanged(true)
						dragSection = null
						view.performHapticFeedback(
							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
								HapticFeedbackConstants.GESTURE_START
							} else {
								HapticFeedbackConstants.VIRTUAL_KEY
							},
						)
						applyTouchY(down.position.y)

						try {
							while (true) {
								val event = awaitPointerEvent()
								val change = event.changes.firstOrNull { it.id == down.id } ?: break
								if (!change.pressed) {
									change.consume()
									break
								}
								change.consume()
								applyTouchY(change.position.y)
							}
						} finally {
							view.parent?.requestDisallowInterceptTouchEvent(false)
							isGrabbed = false
							isBubbleShown = false
							currentOnDragStateChanged(false)
						}
					}
				},
		) {
			if (scrollbarProgress > 0.005f && trackHeightPx >= handleHeightPx) {
				val slideSign = if (isRtl) -1f else 1f
				Box(
					modifier = Modifier
						.align(Alignment.TopEnd)
						.padding(end = scrollerOffset)
						.width(thumbSlotWidth)
						.height(handleHeight)
						.graphicsLayer {
							translationY = thumbTopPx
							translationX = (1f - scrollbarProgress) * scrollbarSlidePx * slideSign
							alpha = scrollbarProgress
						}
						.drawBehind {
							val w = (thinWidthPx + (thickWidthPx - thinWidthPx) * thumbExpansion.value).coerceAtLeast(0f)
							val left = (size.width - w) / 2f
							val r = w / 2f
							drawRoundRect(
								color = thumbColor,
								topLeft = Offset(left, 0f),
								size = Size(w, size.height),
								cornerRadius = CornerRadius(r, r),
							)
						},
				)
			}
		}
	}
}

private class LazyScrollMetrics(
	val offset: Float,
	val extent: Float,
	val range: Float,
) {
	val proportion: Float
		get() {
			val scrollable = range - extent
			return if (scrollable > 0f) (offset / scrollable).coerceIn(0f, 1f) else 0f
		}
}

/**
 * Accounts for `LazyColumn`'s content padding: item `0` starts at `offset == 0` at rest, and at the
 * bottom of the list the last item's bottom rests at `viewportEndOffset - afterContentPadding`.
 */
private fun computeLazyScrollMetrics(state: LazyListState, totalItemsCount: Int): LazyScrollMetrics? {
	if (totalItemsCount <= 0) return null
	if (!state.canScrollBackward && !state.canScrollForward) {
		return LazyScrollMetrics(offset = 0f, extent = 1f, range = 1f)
	}
	val layoutInfo = state.layoutInfo
	val visible = layoutInfo.visibleItemsInfo
	if (visible.isEmpty()) return null
	val first = visible.first()
	val last = visible.last()
	val itemSpan = (last.index - first.index + 1).coerceAtLeast(1)
	val laidOutArea = ((last.offset + last.size) - first.offset).coerceAtLeast(1)
	val avgRowPx = laidOutArea.toFloat() / itemSpan
	val offset = (first.index * avgRowPx - first.offset).coerceAtLeast(0f)
	val visibleSpanPx = (layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding).coerceAtLeast(1)
	val extent = visibleSpanPx.toFloat()
	val estimatedTotal = avgRowPx * totalItemsCount
	val range = if (!state.canScrollForward) {
		offset + extent
	} else {
		max(estimatedTotal, offset + extent + 1f)
	}
	return LazyScrollMetrics(offset = offset, extent = extent, range = range)
}

private suspend fun scrollLazyListToProportion(
	state: LazyListState,
	proportion: Float,
	totalItemsCount: Int,
) {
	if (totalItemsCount <= 0) return
	if (proportion <= 0f) {
		state.scrollToItem(0, 0)
		return
	}
	if (proportion >= 1f) {
		state.scrollToItem(totalItemsCount - 1, Int.MAX_VALUE / 4)
		return
	}
	val metrics = computeLazyScrollMetrics(state, totalItemsCount)
	if (metrics != null && metrics.range > metrics.extent) {
		val targetOffset = proportion * (metrics.range - metrics.extent)
		val delta = targetOffset - metrics.offset
		val viewportHeight = (state.layoutInfo.viewportEndOffset - state.layoutInfo.viewportStartOffset).coerceAtLeast(1)
		if (abs(delta) <= viewportHeight * 2f) {
			state.scroll { scrollBy(delta) }
			return
		}
		val pos = ((targetOffset / metrics.range) * totalItemsCount).toInt().coerceIn(0, totalItemsCount - 1)
		state.scrollToItem(pos, 0)
	} else {
		val pos = (proportion * (totalItemsCount - 1)).roundToInt().coerceIn(0, totalItemsCount - 1)
		state.scrollToItem(pos, 0)
	}
}
