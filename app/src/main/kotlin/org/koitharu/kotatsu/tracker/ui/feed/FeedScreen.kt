package org.koitharu.kotatsu.tracker.ui.feed

import android.content.Context
import android.content.res.Configuration
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp as lerpFloat
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.compose.MovingContainerComposeHost
import org.koitharu.kotatsu.core.ui.compose.RollingNumber
import org.koitharu.kotatsu.core.ui.compose.rememberCoordinatorBarScrollConnection
import org.koitharu.kotatsu.core.ui.list.fastscroll.ComposeFastScroller
import org.koitharu.kotatsu.core.ui.util.RoundedTopShape
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect
import org.koitharu.kotatsu.core.util.ext.stableMangaCoverKey
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.list.ui.compose.ExpressiveQuickFilterRow
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ErrorFooter
import org.koitharu.kotatsu.list.ui.model.ErrorState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingFooter
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.QuickFilter
import org.koitharu.kotatsu.list.ui.model.TipModel
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedSection
import org.koitharu.kotatsu.tracker.work.TrackerWorkState
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tanh
import androidx.appcompat.R as appcompatR

interface FeedActions {
	fun onRefresh()
	fun onFilterOptionClick(option: ListFilterOption)
	fun onClearFiltersClick()
	fun onTipDismiss()
	fun onItemClick(item: FeedItem)
	fun onItemLongClick(item: FeedItem)
	fun onToggleExpand(item: FeedItem)
	fun onReadClick(item: FeedItem)
	fun onSwipeMarkRead(item: FeedItem)
	fun onSwipeRemove(item: FeedItem)
	fun onRequestMoreItems()
}

private val OuterCorner = 24.dp
private val InnerCorner = 4.dp
private val JoinedGap = 2.dp
private val GroupBottomGap = 12.dp
private val PullActivationThreshold = 48.dp
private val PullIndicatorRestingBottom = 134.dp

private val SwipeDeleteBg = Color(0xFFD32F2F)
private val SwipeReadBg = Color(0xFF1976D2)
private val SwipeDisabledBg = Color(0xFF757575)
private val SwipeDisabledIconTint = Color(0xFFE0E0E0)

private const val SWIPE_COMMIT_THRESHOLD = 0.38f
private const val SWIPE_REJECT_RUBBER_LIMIT = 0.18f
private const val PAGINATION_PREFETCH_DISTANCE = 4

@Composable
fun FeedScreen(
	host: MovingContainerComposeHost,
	listState: LazyListState,
	items: List<ListModel>,
	selectedIds: Set<Long>,
	workState: TrackerWorkState,
	lastCheckTimeMillis: Long,
	undoEpoch: Int,
	isSwipeEnabled: Boolean,
	canLoadMore: Boolean,
	startInset: Dp,
	endInset: Dp,
	bottomInset: Dp,
	imageLoader: ImageLoader,
	actions: FeedActions,
) {
	val context = LocalContext.current
	val density = LocalDensity.current
	val haptic = rememberHapticEffect()
	val barScrollConnection = rememberCoordinatorBarScrollConnection(host)
	val pullRefreshState = rememberPullToRefreshState()
	// Tightly cap visual stretch past 1.0f so pulling further doesn't drag the spinner halfway down the screen.
	val indicatorState = remember(pullRefreshState) {
		object : PullToRefreshState by pullRefreshState {
			override val distanceFraction: Float
				get() {
					val f = pullRefreshState.distanceFraction
					return if (f <= 1f) f else 1f + 0.24f * tanh((f - 1f) * 1.2f)
				}
		}
	}

	val listSpacingNormal = dimensionResource(R.dimen.list_spacing_normal)
	val gridSpacingOuter = dimensionResource(R.dimen.grid_spacing_outer)
	val cornerLarge = dimensionResource(R.dimen.corner_large)

	val isSelecting = selectedIds.isNotEmpty()
	val isTrackerRunning = workState.isRunning
	var isFastScrolling by remember { mutableStateOf(false) }
	var viewportHeightPx by remember { mutableIntStateOf(0) }

	// Prevent PullToRefreshBox from pulling while selecting, fast-scrolling, or while the top bar is still collapsed.
	val blockRefreshConnection = remember(host, isSelecting, isFastScrolling) {
		object : NestedScrollConnection {
			override fun onPostScroll(
				consumed: Offset,
				available: Offset,
				source: NestedScrollSource,
			): Offset {
				if (source == NestedScrollSource.UserInput && available.y > 0f) {
					if (isSelecting || isFastScrolling || !host.isAppBarFullyExpanded()) {
						return Offset(0f, available.y)
					}
				}
				return Offset.Zero
			}
		}
	}

	// Paging: load the next slice when within 4 items of the bottom, or if the list doesn't fill the screen yet.
	val shouldLoadMore by remember(listState, items, canLoadMore) {
		derivedStateOf {
			if (!canLoadMore || items.isEmpty()) return@derivedStateOf false
			val layoutInfo = listState.layoutInfo
			val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
			lastVisible >= layoutInfo.totalItemsCount - 1 - PAGINATION_PREFETCH_DISTANCE || !listState.canScrollForward
		}
	}
	LaunchedEffect(shouldLoadMore, items) {
		if (shouldLoadMore) {
			actions.onRequestMoreItems()
		}
	}

	val startPad = startInset + listSpacingNormal
	val endPad = endInset + listSpacingNormal
	val topPad = listSpacingNormal
	val bottomPad = bottomInset + listSpacingNormal
	val stateMinHeight = if (viewportHeightPx > 0) {
		with(density) { (viewportHeightPx * 0.68f).toDp() }
	} else {
		320.dp
	}

	val hasFeedEntries = remember(items) { items.any { it is FeedItem } }

	PullToRefreshBox(
		isRefreshing = isTrackerRunning,
		onRefresh = {
			haptic(HapticEffect.GESTURE_END)
			actions.onRefresh()
		},
		state = pullRefreshState,
		threshold = PullActivationThreshold,
		indicator = {
			PullToRefreshDefaults.LoadingIndicator(
				state = indicatorState,
				isRefreshing = isTrackerRunning,
				maxDistance = PullIndicatorRestingBottom,
				modifier = Modifier.align(Alignment.TopCenter),
			)
		},
		modifier = Modifier.fillMaxSize(),
	) {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.onSizeChanged { viewportHeightPx = it.height }
				.nestedScroll(blockRefreshConnection)
				.nestedScroll(barScrollConnection),
		) {
			LazyColumn(
				state = listState,
				contentPadding = PaddingValues(top = topPad, bottom = bottomPad),
				modifier = Modifier
					.fillMaxSize()
					.clip(RoundedTopShape(radius = cornerLarge, startInset = startPad, endInset = endPad)),
			) {
				items.forEachIndexed { index, model ->
					when (model) {
						is TipModel -> item(key = "tip:${model.key}", contentType = "tip") {
							FeedTipCard(
								tip = model,
								onDismiss = actions::onTipDismiss,
								modifier = Modifier
									.padding(start = startPad, end = endPad, bottom = 8.dp)
									.animateItem(),
							)
						}

						is QuickFilter -> item(key = "quick_filter", contentType = "quick_filter") {
							ExpressiveQuickFilterRow(
								chips = model.items,
								imageLoader = imageLoader,
								onFilterOptionClick = actions::onFilterOptionClick,
								startPadding = startPad + gridSpacingOuter,
								endPadding = endPad + gridSpacingOuter,
								topPadding = 2.dp,
								bottomPadding = 6.dp,
								modifier = Modifier.animateItem(),
							)
						}

						is FeedUpdatedBar -> item(key = "updated_pill", contentType = "updated_pill") {
							Box(
								modifier = Modifier
									.fillMaxWidth()
									.padding(start = startPad, end = endPad, top = 2.dp, bottom = 2.dp)
									.animateItem(),
								contentAlignment = Alignment.Center,
							) {
								FeedUpdatedPill(
									workState = workState,
									lastCheckTimeMillis = lastCheckTimeMillis,
								)
							}
						}

						is FeedSection -> item(key = "sec:${model.key}", contentType = "section") {
							FeedDateHeader(
								title = remember(context, model.key, model.date) { model.getTitle(context) },
								modifier = Modifier
									.padding(start = startPad, end = endPad)
									.animateItem(),
							)
						}

						is FeedItem -> {
							val prevItem = items.getOrNull(index - 1) as? FeedItem
							val nextItem = items.getOrNull(index + 1) as? FeedItem
							val isSelected = model.id in selectedIds
							val hasSelectedAbove = isSelected && prevItem != null && prevItem.id in selectedIds
							val hasSelectedBelow = isSelected && nextItem != null && nextItem.id in selectedIds
							item(key = model.id, contentType = "feed_item") {
								val bottomGap = when (model.groupPosition) {
									FeedItem.GroupPosition.FIRST, FeedItem.GroupPosition.MIDDLE -> JoinedGap
									FeedItem.GroupPosition.SINGLE, FeedItem.GroupPosition.LAST -> GroupBottomGap
								}
								FeedEntryRow(
									item = model,
									isSelected = isSelected,
									hasSelectedAbove = hasSelectedAbove,
									hasSelectedBelow = hasSelectedBelow,
									bottomGap = bottomGap,
									isSelecting = isSelecting,
									isSwipeEnabled = isSwipeEnabled && !isSelecting,
									undoEpoch = undoEpoch,
									imageLoader = imageLoader,
									actions = actions,
									modifier = Modifier
										.padding(start = startPad, end = endPad, bottom = bottomGap)
										.animateItem(
											fadeInSpec = spring(stiffness = Spring.StiffnessMedium),
											placementSpec = spring(
												dampingRatio = Spring.DampingRatioNoBouncy,
												stiffness = 950f,
											),
											fadeOutSpec = tween(durationMillis = 80),
										),
								)
							}
						}

						is LoadingState -> item(key = "state_loading", contentType = "state") {
							Box(
								modifier = Modifier
									.fillMaxWidth()
									.defaultMinSize(minHeight = stateMinHeight),
								contentAlignment = Alignment.Center,
							) {
								LoadingIndicator()
							}
						}

						is EmptyState -> item(key = "state_empty", contentType = "state") {
							FeedEmptyState(
								state = model,
								isTrackerRunning = isTrackerRunning,
								onActionClick = {
									if (model.actionStringRes == R.string.show_all) {
										actions.onClearFiltersClick()
									} else if (model.actionStringRes == R.string.update) {
										actions.onRefresh()
									}
								},
								modifier = Modifier
									.fillMaxWidth()
									.defaultMinSize(minHeight = stateMinHeight)
									.padding(start = startPad, end = endPad),
							)
						}

						is ErrorState -> item(key = "state_error", contentType = "state") {
							FeedErrorState(
								state = model,
								modifier = Modifier
									.fillMaxWidth()
									.defaultMinSize(minHeight = stateMinHeight)
									.padding(start = startPad, end = endPad),
							)
						}

						is LoadingFooter -> item(key = "footer_loading", contentType = "footer") {
							Box(
								modifier = Modifier
									.fillMaxWidth()
									.padding(vertical = 16.dp),
								contentAlignment = Alignment.Center,
							) {
								LoadingIndicator()
							}
						}

						is ErrorFooter -> item(key = "footer_error", contentType = "footer") {
							Text(
								text = model.exception.getDisplayMessage(context.resources),
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.error,
								textAlign = TextAlign.Center,
								modifier = Modifier
									.fillMaxWidth()
									.padding(horizontal = 24.dp, vertical = 12.dp),
							)
						}
					}
				}
			}

			ComposeFastScroller(
				listState = listState,
				totalItemsCount = items.size,
				sectionTitleAt = { targetIndex ->
					findSectionTitleAt(items, targetIndex, context)
				},
				topContentPadding = topPad,
				bottomContentPadding = bottomPad,
				endInset = endInset,
				enabled = hasFeedEntries,
				onDragStateChanged = { isFastScrolling = it },
			)
		}
	}
}

private fun findSectionTitleAt(items: List<ListModel>, position: Int, context: Context): String? {
	if (items.isEmpty()) return null
	val start = position.coerceIn(0, items.lastIndex)
	for (i in start downTo 0) {
		val item = items[i]
		if (item is FeedSection) {
			return item.getTitle(context)
		}
	}
	for (i in (start + 1)..items.lastIndex) {
		val item = items[i]
		if (item is FeedSection) {
			return item.getTitle(context)
		}
	}
	return null
}

// region Subtle Non-Interactive "Updated N ago" Pill & Plain Date Header

/**
 * Quiet, non-interactive centered pill below the filter chips showing when the feed was last updated.
 */
@Composable
private fun FeedUpdatedPill(
	workState: TrackerWorkState,
	lastCheckTimeMillis: Long,
	modifier: Modifier = Modifier,
) {
	val colors = MaterialTheme.colorScheme
	val isRunning = workState.isRunning

	var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
	LaunchedEffect(lastCheckTimeMillis, isRunning) {
		nowMillis = System.currentTimeMillis()
		while (!isRunning && lastCheckTimeMillis > 0L) {
			delay(30_000L)
			nowMillis = System.currentTimeMillis()
		}
	}

	val showProgress = isRunning && workState.max > 0
	// null = the live N/M label, which rolls its number in place instead of swapping the whole text
	val labelText = when {
		showProgress -> null
		isRunning -> stringResource(R.string.feed_updating)
		lastCheckTimeMillis > 0L -> {
			val diff = (nowMillis - lastCheckTimeMillis).coerceAtLeast(0L)
			when {
				diff < DateUtils.MINUTE_IN_MILLIS -> stringResource(R.string.feed_updated_just_now)
				diff < DateUtils.HOUR_IN_MILLIS -> stringResource(
					R.string.feed_updated_minutes_ago,
					(diff / DateUtils.MINUTE_IN_MILLIS).toInt(),
				)

				diff < DateUtils.DAY_IN_MILLIS -> stringResource(
					R.string.feed_updated_hours_ago,
					(diff / DateUtils.HOUR_IN_MILLIS).toInt(),
				)

				diff < 2 * DateUtils.DAY_IN_MILLIS -> stringResource(R.string.feed_updated_yesterday)
				else -> {
					val rel = remember(lastCheckTimeMillis, nowMillis) {
						DateUtils.getRelativeTimeSpanString(
							lastCheckTimeMillis,
							nowMillis,
							DateUtils.DAY_IN_MILLIS,
							DateUtils.FORMAT_ABBREV_RELATIVE,
						).toString()
					}
					stringResource(R.string.feed_updated_ago, rel)
				}
			}
		}

		else -> stringResource(R.string.feed_never_updated)
	}

	Box(
		modifier = modifier
			.background(colors.surfaceContainer, CircleShape)
			.animateContentSize(
				animationSpec = spring(
					dampingRatio = Spring.DampingRatioNoBouncy,
					stiffness = Spring.StiffnessMedium,
				),
			)
			.padding(horizontal = 12.dp, vertical = 5.dp),
		contentAlignment = Alignment.Center,
	) {
		AnimatedContent(
			targetState = labelText,
			transitionSpec = {
				fadeIn(tween(150)) togetherWith fadeOut(tween(100)) using SizeTransform(clip = false)
			},
			label = "pillUpdatedText",
		) { text ->
			val style = MaterialTheme.typography.labelMedium
			if (text == null) {
				val template = stringResource(R.string.feed_updating_progress)
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						text = template.substringBefore("%1\$d"),
						style = style,
						fontWeight = FontWeight.Medium,
						color = colors.onSurfaceVariant,
						maxLines = 1,
						softWrap = false,
					)
					RollingNumber(
						value = workState.progress.coerceAtLeast(1),
						style = style,
						fontWeight = FontWeight.Medium,
						color = colors.onSurfaceVariant,
					)
					Text(
						text = template.substringAfter("%1\$d").replace("%2\$d", workState.max.toString()),
						style = style,
						fontWeight = FontWeight.Medium,
						color = colors.onSurfaceVariant,
						maxLines = 1,
						softWrap = false,
					)
				}
			} else {
				Text(
					text = text,
					style = style,
					fontWeight = FontWeight.Medium,
					color = colors.onSurfaceVariant,
					maxLines = 1,
					softWrap = false,
				)
			}
		}
	}
}

/** Simple, plain date section label matching `item_header.xml`. */
@Composable
private fun FeedDateHeader(
	title: String,
	modifier: Modifier = Modifier,
) {
	Text(
		text = title,
		style = MaterialTheme.typography.titleSmall,
		fontWeight = FontWeight.SemiBold,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		maxLines = 1,
		overflow = TextOverflow.Ellipsis,
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 8.dp, vertical = 8.dp),
	)
}

// endregion

// region Feed Entry Row

@Composable
private fun FeedEntryRow(
	item: FeedItem,
	isSelected: Boolean,
	hasSelectedAbove: Boolean,
	hasSelectedBelow: Boolean,
	bottomGap: Dp,
	isSelecting: Boolean,
	isSwipeEnabled: Boolean,
	undoEpoch: Int,
	imageLoader: ImageLoader,
	actions: FeedActions,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val view = LocalView.current
	val density = LocalDensity.current
	val colors = MaterialTheme.colorScheme
	val motion = MaterialTheme.motionScheme
	val scope = rememberCoroutineScope()
	val currentItem by rememberUpdatedState(item)
	val currentActions by rememberUpdatedState(actions)

	val errorDotColor = remember(context, LocalConfiguration.current.uiMode) {
		Color(context.getThemeColor(appcompatR.attr.colorError))
	}

	// Tactile dip when selection toggles
	val selectDip = remember { Animatable(1f) }
	val isFirstSelectPass = remember { booleanArrayOf(true) }
	LaunchedEffect(isSelected) {
		if (isFirstSelectPass[0]) {
			isFirstSelectPass[0] = false
			return@LaunchedEffect
		}
		selectDip.animateTo(0.95f, spring(stiffness = Spring.StiffnessHigh))
		selectDip.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
	}

	// Horizontal swipe state with crisp exit tween (zero spring-tail lag), cancel safety, and Undo slide-back
	var rowWidthPx by remember { mutableIntStateOf(0) }
	val rawDragX = remember { Animatable(0f) }
	var isDraggingSwipe by remember { mutableStateOf(false) }
	var wasUnreadAtDragStart by remember { mutableStateOf(item.isNew) }
	var isDismissed by remember { mutableStateOf(false) }
	val badgePop = remember { Animatable(0f) }
	val rejectShake = remember { Animatable(0f) }

	val swipeSettleSpec = remember {
		spring<Float>(dampingRatio = 0.76f, stiffness = 560f)
	}

	var seenUndoEpoch by remember { mutableIntStateOf(undoEpoch) }
	LaunchedEffect(undoEpoch) {
		if (undoEpoch != seenUndoEpoch) {
			seenUndoEpoch = undoEpoch
			if (isDismissed || rawDragX.value != 0f) {
				isDismissed = false
				rawDragX.animateTo(0f, swipeSettleSpec)
			}
		}
	}

	LaunchedEffect(isSwipeEnabled) {
		if (!isSwipeEnabled && rawDragX.value != 0f && !isDismissed) {
			rawDragX.snapTo(0f)
			badgePop.snapTo(0f)
		}
	}

	val effectiveDx = remember(rawDragX.value, rowWidthPx, wasUnreadAtDragStart, isDismissed, rejectShake.value) {
		val dx = rawDragX.value
		when {
			isDismissed -> rowWidthPx.toFloat()
			dx == 0f || rowWidthPx <= 0 -> 0f
			dx < 0f && !wasUnreadAtDragStart -> {
				val limit = rowWidthPx * SWIPE_REJECT_RUBBER_LIMIT
				-(limit * tanh(abs(dx) / limit)) + rejectShake.value
			}

			dx < 0f -> {
				val thresholdPx = rowWidthPx * SWIPE_COMMIT_THRESHOLD
				val absDx = abs(dx)
				if (absDx <= thresholdPx) {
					dx
				} else {
					val extra = absDx - thresholdPx
					-(thresholdPx + extra * 0.34f)
				}
			}

			else -> dx
		}
	}

	var confirmHapticFired by remember { mutableStateOf(false) }
	var rejectHapticFired by remember { mutableStateOf(false) }

	LaunchedEffect(rawDragX.value, isDraggingSwipe, rowWidthPx) {
		if (!isDraggingSwipe || rowWidthPx <= 0) return@LaunchedEffect
		val dx = rawDragX.value
		val isReadSwipe = dx < 0f
		val isAlreadyRead = isReadSwipe && !wasUnreadAtDragStart
		if (isAlreadyRead) {
			val limit = rowWidthPx * SWIPE_REJECT_RUBBER_LIMIT
			if (abs(dx) > limit * 1.9f && !rejectHapticFired) {
				rejectHapticFired = true
				view.hapticFeedback(HapticEffect.REJECT)
				launch {
					val amp = with(density) { 4.dp.toPx() }
					rejectShake.animateTo(amp, tween(45))
					rejectShake.animateTo(-amp * 0.7f, tween(45))
					rejectShake.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 900f))
				}
			}
		} else if (dx != 0f) {
			val passed = abs(dx) >= SWIPE_COMMIT_THRESHOLD * rowWidthPx
			if (passed && !confirmHapticFired) {
				confirmHapticFired = true
				view.hapticFeedback(HapticEffect.CONFIRM)
				launch {
					badgePop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 900f))
				}
			} else if (!passed && confirmHapticFired) {
				confirmHapticFired = false
				launch {
					badgePop.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 900f))
				}
			}
		}
	}

	val deletePainter = painterResource(R.drawable.ic_delete)
	val readPainter = painterResource(R.drawable.ic_eye_check)

	val rowInteraction = remember { MutableInteractionSource() }
	val isRowPressed by rowInteraction.collectIsPressedAsState()
	val rowPressScale by animateFloatAsState(
		targetValue = if (isRowPressed && effectiveDx == 0f) 0.985f else 1f,
		animationSpec = motion.fastSpatialSpec(),
		label = "rowPressScale",
	)
	val pressedSeamCorner by animateDpAsState(
		targetValue = if (isRowPressed) 14.dp else InnerCorner,
		animationSpec = motion.fastSpatialSpec(),
		label = "rowSeam",
	)
	val detachFraction = if (rowWidthPx > 0) {
		(abs(effectiveDx) / with(density) { 44.dp.toPx() }).coerceIn(0f, 1f)
	} else {
		0f
	}
	val detachedSeam = with(density) {
		lerpFloat(pressedSeamCorner.coerceAtLeast(0.dp).toPx(), OuterCorner.toPx(), detachFraction).toDp()
	}.coerceAtLeast(0.dp)

	val targetTopCorner = when {
		hasSelectedAbove -> 0.dp
		item.groupPosition == FeedItem.GroupPosition.SINGLE || item.groupPosition == FeedItem.GroupPosition.FIRST -> OuterCorner
		else -> detachedSeam
	}
	val targetBottomCorner = when {
		hasSelectedBelow -> 0.dp
		item.groupPosition == FeedItem.GroupPosition.SINGLE || item.groupPosition == FeedItem.GroupPosition.LAST -> OuterCorner
		else -> detachedSeam
	}
	val topCorner by animateDpAsState(targetTopCorner, motion.fastSpatialSpec(), label = "topRowCorner")
	val bottomCorner by animateDpAsState(targetBottomCorner, motion.fastSpatialSpec(), label = "bottomRowCorner")
	val safeTopCorner = topCorner.coerceAtLeast(0.dp)
	val safeBottomCorner = bottomCorner.coerceAtLeast(0.dp)
	val rowShape = RoundedCornerShape(
		topStart = safeTopCorner,
		topEnd = safeTopCorner,
		bottomStart = safeBottomCorner,
		bottomEnd = safeBottomCorner,
	)

	val selectorCorner = dimensionResource(R.dimen.list_selector_corner)
	val selectionStrokeWidth = dimensionResource(R.dimen.selection_stroke_width)
	val selectionFill = lerp(colors.surfaceContainer, colors.primary, 0.20f)
	val rowFill by animateColorAsState(
		targetValue = if (isSelected) selectionFill else colors.surfaceContainer,
		animationSpec = motion.fastEffectsSpec(),
		label = "rowFill",
	)
	val selectionStrokeColor = colors.primary

	Box(
		modifier = modifier
			.fillMaxWidth()
			.onSizeChanged { rowWidthPx = it.width }
			.drawBehind {
				if (effectiveDx == 0f || isDismissed) return@drawBehind
				val isReadSwipe = effectiveDx < 0f
				val isAlreadyRead = isReadSwipe && !wasUnreadAtDragStart
				val bgColor = when {
					isAlreadyRead -> SwipeDisabledBg
					isReadSwipe -> SwipeReadBg
					else -> SwipeDeleteBg
				}
				val gapPx = 4.dp.toPx()
				val left = if (isReadSwipe) {
					(size.width + effectiveDx + gapPx).coerceAtMost(size.width)
				} else {
					0f
				}
				val right = if (isReadSwipe) {
					size.width
				} else {
					(effectiveDx - gapPx).coerceAtLeast(0f).coerceAtMost(size.width)
				}
				val revealedWidth = right - left
				if (revealedWidth <= 2f) return@drawBehind

				val pillRadius = minOf(size.height / 2f, revealedWidth / 2f)
				drawRoundRect(
					color = bgColor,
					topLeft = Offset(left, 0f),
					size = Size(revealedWidth, size.height),
					cornerRadius = CornerRadius(pillRadius, pillRadius),
				)

				val iconBasePx = 22.dp.toPx()
				if (revealedWidth >= iconBasePx * 0.9f) {
					val progress = (abs(effectiveDx) / (size.width * SWIPE_COMMIT_THRESHOLD)).coerceIn(0f, 1f)
					val pop = badgePop.value.coerceAtLeast(0f)
					val cx = (left + right) / 2f
					val cy = size.height / 2f

					if (pop > 0.01f && !isAlreadyRead) {
						val badgeRadius = (20.dp.toPx() * pop).coerceAtMost(minOf(revealedWidth, size.height) * 0.44f)
						drawCircle(
							color = Color.White.copy(alpha = 0.22f * pop.coerceIn(0f, 1f)),
							radius = badgeRadius,
							center = Offset(cx, cy),
						)
					}

					val scale = lerpFloat(0.76f, 1f, progress) + pop * 0.14f
					val drawSize = iconBasePx * scale
					val alpha = (progress * 1.8f).coerceIn(0f, 1f)
					val tint = if (isAlreadyRead) SwipeDisabledIconTint else Color.White
					val painter = if (isReadSwipe) readPainter else deletePainter
					translate(left = cx - drawSize / 2f, top = cy - drawSize / 2f) {
						with(painter) {
							draw(
								size = Size(drawSize, drawSize),
								alpha = alpha,
								colorFilter = ColorFilter.tint(tint),
							)
						}
					}
				}
			}
			.then(
				if (isSwipeEnabled) {
					Modifier.pointerInput(Unit) {
						val velocityTracker = VelocityTracker()
						detectHorizontalDragGestures(
							onDragStart = {
								if (isDismissed) return@detectHorizontalDragGestures
								velocityTracker.resetTracking()
								wasUnreadAtDragStart = currentItem.isNew
								confirmHapticFired = false
								rejectHapticFired = false
								isDraggingSwipe = true
								scope.launch { badgePop.snapTo(0f) }
							},
							onHorizontalDrag = { change, dragAmount ->
								if (isDismissed) return@detectHorizontalDragGestures
								change.consume()
								velocityTracker.addPosition(change.uptimeMillis, change.position)
								scope.launch {
									rawDragX.snapTo(rawDragX.value + dragAmount)
								}
							},
							onDragCancel = {
								isDraggingSwipe = false
								scope.launch {
									launch { badgePop.animateTo(0f, spring(stiffness = 900f)) }
									rawDragX.animateTo(0f, swipeSettleSpec)
								}
							},
							onDragEnd = {
								isDraggingSwipe = false
								val width = rowWidthPx
								val dx = rawDragX.value
								val vx = velocityTracker.calculateVelocity().x.coerceIn(-5000f, 5000f)
								if (width > 0 && dx <= -SWIPE_COMMIT_THRESHOLD * width && wasUnreadAtDragStart) {
									currentActions.onSwipeMarkRead(currentItem)
									scope.launch {
										launch { badgePop.animateTo(0f, spring(stiffness = 700f)) }
										rawDragX.animateTo(0f, swipeSettleSpec, initialVelocity = vx * 0.35f)
									}
								} else if (width > 0 && dx >= SWIPE_COMMIT_THRESHOLD * width) {
									val remaining = (width - dx).coerceAtLeast(0f)
									val speedPxPerSec = maxOf(vx, width * 4.2f)
									val exitDurationMs = ((remaining / speedPxPerSec) * 1000f).roundToInt().coerceIn(70, 145)
									scope.launch {
										if (remaining > 1f) {
											rawDragX.animateTo(
												targetValue = width.toFloat(),
												animationSpec = tween(durationMillis = exitDurationMs, easing = FastOutLinearInEasing),
											)
										}
										isDismissed = true
										currentActions.onSwipeRemove(currentItem)
									}
								} else {
									scope.launch {
										launch { badgePop.animateTo(0f, spring(stiffness = 900f)) }
										rawDragX.animateTo(0f, swipeSettleSpec, initialVelocity = vx * 0.4f)
									}
								}
							},
						)
					}
				} else {
					Modifier
				},
			),
	) {
		val selectionFillPath = remember { Path() }
		val selectionStrokePath = remember { Path() }
		val combinedScale = selectDip.value * rowPressScale

		Row(
			modifier = Modifier
				.fillMaxWidth()
				.defaultMinSize(minHeight = 76.dp)
				.graphicsLayer {
					translationX = effectiveDx
					scaleX = combinedScale
					scaleY = combinedScale
					alpha = if (isDismissed) 0f else 1f
				}
				.drawWithContent {
					drawContent()
					if (isSelected) {
						val bridgePx = if (hasSelectedBelow) bottomGap.toPx() else 0f
						val strokePx = selectionStrokeWidth.toPx()
						val halfStroke = strokePx / 2f
						val topR = when {
							hasSelectedAbove -> 0f
							safeTopCorner >= OuterCorner -> (safeTopCorner.toPx() - halfStroke).coerceAtLeast(0f)
							else -> (selectorCorner.toPx() - halfStroke).coerceAtLeast(0f)
						}
						val bottomR = when {
							hasSelectedBelow -> 0f
							safeBottomCorner >= OuterCorner -> (safeBottomCorner.toPx() - halfStroke).coerceAtLeast(0f)
							else -> (selectorCorner.toPx() - halfStroke).coerceAtLeast(0f)
						}
						val boxTop = if (hasSelectedAbove) 0f else halfStroke
						val boxBottom = if (hasSelectedBelow) size.height + bridgePx else size.height - halfStroke

						if (hasSelectedBelow && bridgePx > 0f) {
							selectionFillPath.rewind()
							selectionFillPath.addRect(Rect(0f, size.height, size.width, size.height + bridgePx))
							drawPath(selectionFillPath, color = selectionFill)
						}

						selectionStrokePath.rewind()
						val bounds = Rect(halfStroke, boxTop, (size.width - halfStroke).coerceAtLeast(halfStroke), boxBottom)
						buildMergedSelectionStroke(selectionStrokePath, bounds, topR, bottomR, hasSelectedAbove, hasSelectedBelow)
						drawPath(
							path = selectionStrokePath,
							color = selectionStrokeColor,
							style = Stroke(width = strokePx),
						)
					}
				}
				.clip(rowShape)
				.background(rowFill)
				.combinedClickable(
					interactionSource = rowInteraction,
					indication = LocalIndication.current,
					hapticFeedbackEnabled = false,
					onClickLabel = stringResource(R.string.read),
					onClick = { if (isSelecting) actions.onItemClick(item) else actions.onReadClick(item) },
					onLongClick = { actions.onItemLongClick(item) },
				)
				.padding(start = 12.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
			verticalAlignment = Alignment.Top,
		) {
			// Cover and title open the details page (or toggle selection); the rest of the row reads.
			val openDetails = Modifier.combinedClickable(
				hapticFeedbackEnabled = false,
				onClick = { actions.onItemClick(item) },
				onLongClick = { actions.onItemLongClick(item) },
			)
			FeedCover(
				item = item,
				imageLoader = imageLoader,
				modifier = Modifier
					.clip(RoundedCornerShape(12.dp))
					.then(openDetails),
			)

			Column(
				modifier = Modifier
					.weight(1f)
					.padding(start = 14.dp, end = 10.dp)
					.align(Alignment.CenterVertically),
			) {
				Text(
					text = item.title,
					style = MaterialTheme.typography.titleSmall,
					color = colors.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier
						.clip(RoundedCornerShape(4.dp))
						.then(openDetails),
				)
				Spacer(Modifier.height(3.dp))
				FeedChaptersBlock(
					item = item,
					isSelecting = isSelecting,
					errorDotColor = errorDotColor,
					onSummaryOrCollapseClick = {
						if (isSelecting) {
							actions.onItemClick(item)
						} else {
							actions.onToggleExpand(item)
						}
					},
					onLongPress = { actions.onItemLongClick(item) },
				)
			}

		}
	}
}

private fun buildMergedSelectionStroke(
	path: Path,
	bounds: Rect,
	topRadius: Float,
	bottomRadius: Float,
	hasSelectedAbove: Boolean,
	hasSelectedBelow: Boolean,
) {
	when {
		!hasSelectedAbove && !hasSelectedBelow -> {
			path.addRoundRect(
				RoundRect(
					rect = bounds,
					topLeft = CornerRadius(topRadius, topRadius),
					topRight = CornerRadius(topRadius, topRadius),
					bottomRight = CornerRadius(bottomRadius, bottomRadius),
					bottomLeft = CornerRadius(bottomRadius, bottomRadius),
				),
			)
		}

		!hasSelectedAbove -> {
			val r = topRadius
			path.moveTo(bounds.left, bounds.bottom)
			path.lineTo(bounds.left, bounds.top + r)
			if (r > 0f) {
				path.arcTo(Rect(bounds.left, bounds.top, bounds.left + 2 * r, bounds.top + 2 * r), 180f, 90f, false)
			}
			path.lineTo(bounds.right - r, bounds.top)
			if (r > 0f) {
				path.arcTo(Rect(bounds.right - 2 * r, bounds.top, bounds.right, bounds.top + 2 * r), 270f, 90f, false)
			}
			path.lineTo(bounds.right, bounds.bottom)
		}

		!hasSelectedBelow -> {
			val r = bottomRadius
			path.moveTo(bounds.left, bounds.top)
			path.lineTo(bounds.left, bounds.bottom - r)
			if (r > 0f) {
				path.arcTo(Rect(bounds.left, bounds.bottom - 2 * r, bounds.left + 2 * r, bounds.bottom), 180f, -90f, false)
			}
			path.lineTo(bounds.right - r, bounds.bottom)
			if (r > 0f) {
				path.arcTo(Rect(bounds.right - 2 * r, bounds.bottom - 2 * r, bounds.right, bounds.bottom), 90f, -90f, false)
			}
			path.lineTo(bounds.right, bounds.top)
		}

		else -> {
			path.moveTo(bounds.left, bounds.top)
			path.lineTo(bounds.left, bounds.bottom)
			path.moveTo(bounds.right, bounds.top)
			path.lineTo(bounds.right, bounds.bottom)
		}
	}
}

@Composable
private fun FeedCover(
	item: FeedItem,
	imageLoader: ImageLoader,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val sizePx = with(LocalDensity.current) { 52.dp.roundToPx() }
	AsyncImage(
		model = remember(item.manga.id, item.imageUrl) {
			ImageRequest.Builder(context)
				.data(item.imageUrl)
				.size(sizePx)
				.mangaSourceExtra(item.manga.source)
				.stableMangaCoverKey(item.manga, item.imageUrl)
				.build()
		},
		imageLoader = imageLoader,
		contentDescription = null,
		contentScale = ContentScale.Crop,
		modifier = modifier
			.size(52.dp)
			.background(MaterialTheme.colorScheme.surfaceContainerHighest),
	)
}

@Composable
private fun FeedChaptersBlock(
	item: FeedItem,
	isSelecting: Boolean,
	errorDotColor: Color,
	onSummaryOrCollapseClick: () -> Unit,
	onLongPress: () -> Unit,
) {
	val context = LocalContext.current
	val colors = MaterialTheme.colorScheme
	val motion = MaterialTheme.motionScheme
	val haptic = rememberHapticEffect()
	val chapters = item.chapters

	when {
		chapters.size == 1 -> {
			val single = chapters[0]
			Row(
				modifier = Modifier.padding(vertical = 2.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				AnimatedUnreadDotSlot(visible = item.isNew && single.isNew, color = errorDotColor)
				Text(
					text = single.name,
					style = MaterialTheme.typography.bodyMedium,
					color = colors.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}

		chapters.isEmpty() -> {
			val summaryText = remember(context, item.count) {
				context.resources.getQuantityStringSafe(R.plurals.new_chapters, item.count, item.count)
			}
			Text(
				text = summaryText,
				style = MaterialTheme.typography.bodyMedium,
				color = colors.primary,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.padding(vertical = 2.dp),
			)
		}

		else -> {
			val summaryText = remember(context, item.count) {
				context.resources.getQuantityStringSafe(R.plurals.new_chapters, item.count, item.count)
			}
			val chevronRotation by animateFloatAsState(
				targetValue = if (item.isExpanded) 180f else 0f,
				animationSpec = spring(dampingRatio = 0.52f, stiffness = 520f),
				label = "expandChevron",
			)
			val pillBg by animateColorAsState(
				targetValue = if (item.isExpanded) colors.surfaceContainerHigh else Color.Transparent,
				animationSpec = motion.fastEffectsSpec(),
				label = "summaryPillBg",
			)
			val pillPadH by animateDpAsState(
				targetValue = if (item.isExpanded) 8.dp else 0.dp,
				animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
				label = "summaryPillPad",
			)

			Column(modifier = Modifier.fillMaxWidth()) {
				Row(
					modifier = Modifier
						.clip(CircleShape)
						.background(pillBg, CircleShape)
						.combinedClickable(
							interactionSource = remember { MutableInteractionSource() },
							indication = ripple(),
							hapticFeedbackEnabled = false,
							onClickLabel = stringResource(if (item.isExpanded) R.string.collapse else R.string.expand),
							onClick = {
								if (!isSelecting) {
									haptic(HapticEffect.LIGHT_CLICK)
								}
								onSummaryOrCollapseClick()
							},
							onLongClick = onLongPress,
						)
						.padding(horizontal = pillPadH.coerceAtLeast(0.dp), vertical = 2.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					AnimatedUnreadDotSlot(visible = item.isNew, color = errorDotColor)
					Text(
						text = summaryText,
						style = MaterialTheme.typography.bodyMedium,
						fontWeight = if (item.isExpanded) FontWeight.Medium else FontWeight.Normal,
						color = colors.primary,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.weight(1f, fill = false),
					)
					Spacer(Modifier.width(2.dp))
					Icon(
						painter = painterResource(R.drawable.ic_expand_more),
						contentDescription = null,
						tint = colors.primary,
						modifier = Modifier
							.size(18.dp)
							.drawWithContent {
								rotate(chevronRotation) { this@drawWithContent.drawContent() }
							},
					)
				}

				AnimatedVisibility(
					visible = item.isExpanded,
					enter = expandVertically(
						animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
						expandFrom = Alignment.Top,
					) + fadeIn(animationSpec = tween(durationMillis = 180)),
					exit = shrinkVertically(
						animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 680f),
						shrinkTowards = Alignment.Top,
					) + fadeOut(animationSpec = tween(durationMillis = 120)),
				) {
					val railColor = colors.outlineVariant
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.padding(top = 4.dp)
							.clip(RoundedCornerShape(10.dp))
							.combinedClickable(
								interactionSource = remember { MutableInteractionSource() },
								indication = ripple(),
								hapticFeedbackEnabled = false,
								onClickLabel = stringResource(R.string.collapse),
								onClick = {
									if (!isSelecting) {
										haptic(HapticEffect.LIGHT_CLICK)
									}
									onSummaryOrCollapseClick()
								},
								onLongClick = onLongPress,
							)
							.drawBehind {
								val x = 4.dp.toPx()
								val strokeW = 2.dp.toPx()
								drawLine(
									color = railColor,
									start = Offset(x, 6.dp.toPx()),
									end = Offset(x, (size.height - 6.dp.toPx()).coerceAtLeast(6.dp.toPx())),
									strokeWidth = strokeW,
									cap = StrokeCap.Round,
								)
							}
							.padding(start = 14.dp, top = 2.dp, bottom = 2.dp),
						verticalArrangement = Arrangement.spacedBy(2.dp),
					) {
						chapters.forEachIndexed { idx, chapter ->
							ChapterCascadeRow(
								name = chapter.name,
								isUnread = item.isNew && chapter.isNew,
								showDotSlot = item.isNew,
								errorDotColor = errorDotColor,
								index = idx,
							)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun ChapterCascadeRow(
	name: String,
	isUnread: Boolean,
	showDotSlot: Boolean,
	errorDotColor: Color,
	index: Int,
) {
	val colors = MaterialTheme.colorScheme
	val reveal = remember { Animatable(0f) }
	LaunchedEffect(Unit) {
		val staggerMs = (index * 16).coerceAtMost(96)
		if (staggerMs > 0) delay(staggerMs.toLong())
		reveal.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 650f))
	}
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.graphicsLayer {
				alpha = reveal.value.coerceIn(0f, 1f)
				translationY = (1f - reveal.value) * -6.dp.toPx()
			}
			.padding(vertical = 2.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (showDotSlot) {
			if (isUnread) {
				Box(
					modifier = Modifier
						.size(7.dp)
						.background(errorDotColor, CircleShape),
				)
			} else {
				Spacer(Modifier.size(7.dp))
			}
			Spacer(Modifier.width(8.dp))
		}
		Text(
			text = name,
			style = MaterialTheme.typography.bodyMedium,
			color = colors.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

@Composable
private fun AnimatedUnreadDotSlot(visible: Boolean, color: Color) {
	val progress by animateFloatAsState(
		targetValue = if (visible) 1f else 0f,
		animationSpec = spring(dampingRatio = 0.6f, stiffness = 750f),
		label = "unreadDotProgress",
	)
	if (progress <= 0.005f) return
	Box(
		modifier = Modifier.layout { measurable, constraints ->
			val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
			val fullW = 15.dp.roundToPx()
			val w = (fullW * progress.coerceIn(0f, 1f)).roundToInt()
			layout(w, placeable.height) {
				placeable.placeRelative(0, 0)
			}
		},
		contentAlignment = Alignment.CenterStart,
	) {
		Box(
			modifier = Modifier
				.size(8.dp)
				.graphicsLayer {
					val s = progress.coerceAtLeast(0f)
					scaleX = s
					scaleY = s
					alpha = progress.coerceIn(0f, 1f)
				}
				.background(color, CircleShape),
		)
	}
}

// endregion

// region Tip, Empty & Error States

@Composable
private fun FeedTipCard(
	tip: TipModel,
	onDismiss: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val colors = MaterialTheme.colorScheme
	Surface(
		shape = MaterialTheme.shapes.extraLarge,
		color = colors.surfaceContainerHigh,
		modifier = modifier.fillMaxWidth(),
	) {
		Column(
			modifier = Modifier.padding(
				start = 16.dp,
				end = if (tip.isClosable) 8.dp else 16.dp,
				top = if (tip.isClosable) 8.dp else 16.dp,
				bottom = 16.dp,
			),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				if (tip.icon != 0) {
					Icon(
						painter = painterResource(tip.icon),
						contentDescription = null,
						tint = colors.onSurface,
						modifier = Modifier.size(24.dp),
					)
					Spacer(Modifier.width(12.dp))
				}
				Text(
					text = stringResource(tip.title),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold,
					color = colors.onSurface,
					modifier = Modifier.weight(1f),
				)
				if (tip.isClosable) {
					IconButton(
						onClick = onDismiss,
						modifier = Modifier.size(40.dp),
					) {
						Icon(
							painter = painterResource(R.drawable.ic_close),
							contentDescription = stringResource(R.string.close),
							tint = colors.onSurfaceVariant,
							modifier = Modifier.size(20.dp),
						)
					}
				}
			}
			Spacer(Modifier.height(if (tip.isClosable) 2.dp else 6.dp))
			Text(
				text = stringResource(tip.text),
				style = MaterialTheme.typography.bodyMedium,
				color = colors.onSurfaceVariant,
				modifier = Modifier.padding(end = if (tip.isClosable) 8.dp else 0.dp),
			)
		}
	}
}

@Composable
private fun FeedEmptyState(
	state: EmptyState,
	isTrackerRunning: Boolean,
	onActionClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val colors = MaterialTheme.colorScheme
	val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
	val showButton = state.actionStringRes != 0 && !(state.actionStringRes == R.string.update && isTrackerRunning)

	if (isLandscape) {
		Row(
			modifier = modifier.padding(horizontal = 24.dp, vertical = 16.dp),
			horizontalArrangement = Arrangement.Center,
			verticalAlignment = Alignment.CenterVertically,
		) {
			if (state.icon != 0) {
				Image(
					painter = painterResource(state.icon),
					contentDescription = null,
					modifier = Modifier.size(168.dp),
				)
				Spacer(Modifier.width(24.dp))
			}
			Column(modifier = Modifier.widthIn(max = 320.dp)) {
				Text(
					text = stringResource(state.textPrimary),
					style = MaterialTheme.typography.titleLarge,
					color = colors.onSurface,
				)
				if (state.textSecondary != 0) {
					Spacer(Modifier.height(12.dp))
					Text(
						text = stringResource(state.textSecondary),
						style = MaterialTheme.typography.bodyMedium,
						color = colors.onSurfaceVariant,
					)
				}
				if (showButton) {
					Spacer(Modifier.height(16.dp))
					FilledTonalButton(onClick = onActionClick) {
						Text(text = stringResource(state.actionStringRes))
					}
				}
			}
		}
	} else {
		Column(
			modifier = modifier.padding(horizontal = 32.dp, vertical = 24.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.Center,
		) {
			if (state.icon != 0) {
				Image(
					painter = painterResource(state.icon),
					contentDescription = null,
					modifier = Modifier.size(192.dp),
				)
				Spacer(Modifier.height(16.dp))
			}
			Text(
				text = stringResource(state.textPrimary),
				style = MaterialTheme.typography.titleLarge,
				color = colors.onSurface,
				textAlign = TextAlign.Center,
			)
			if (state.textSecondary != 0) {
				Spacer(Modifier.height(16.dp))
				Text(
					text = stringResource(state.textSecondary),
					style = MaterialTheme.typography.bodyMedium,
					color = colors.onSurfaceVariant,
					textAlign = TextAlign.Center,
				)
			}
			if (showButton) {
				Spacer(Modifier.height(20.dp))
				FilledTonalButton(onClick = onActionClick) {
					Text(text = stringResource(state.actionStringRes))
				}
			}
		}
	}
}

@Composable
private fun FeedErrorState(
	state: ErrorState,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val colors = MaterialTheme.colorScheme
	Column(
		modifier = modifier.padding(horizontal = 32.dp, vertical = 24.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Center,
	) {
		if (state.icon != 0) {
			Icon(
				painter = painterResource(state.icon),
				contentDescription = null,
				tint = colors.onSurfaceVariant,
				modifier = Modifier.size(64.dp),
			)
			Spacer(Modifier.height(16.dp))
		}
		Text(
			text = state.exception.getDisplayMessage(context.resources),
			style = MaterialTheme.typography.bodyLarge,
			color = colors.onSurface,
			textAlign = TextAlign.Center,
		)
	}
}
