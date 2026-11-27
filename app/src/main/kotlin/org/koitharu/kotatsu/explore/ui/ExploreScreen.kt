package org.koitharu.kotatsu.explore.ui

import android.view.LayoutInflater
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp as lerpValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.material.carousel.CarouselSnapHelper
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.CaptchaHandler.Companion.suppressCaptchaErrors
import org.koitharu.kotatsu.core.model.getSummary
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isExternalSource
import org.koitharu.kotatsu.core.model.isNovelSource
import org.koitharu.kotatsu.core.parser.favicon.faviconUri
import org.koitharu.kotatsu.core.ui.image.FaviconDrawable
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect
import org.koitharu.kotatsu.databinding.ItemRecommendationBinding
import org.koitharu.kotatsu.databinding.ItemTip2Binding
import org.koitharu.kotatsu.explore.data.ExtensionLayout
import org.koitharu.kotatsu.explore.ui.adapter.LoopingCarouselAdapter
import org.koitharu.kotatsu.explore.ui.adapter.setUpLoopingMangaCarousel
import org.koitharu.kotatsu.explore.ui.model.ExploreSources
import org.koitharu.kotatsu.explore.ui.model.MangaSourceItem
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.MangaCompactListModel
import org.koitharu.kotatsu.list.ui.model.TipModel
import org.koitharu.kotatsu.main.ui.nav.DrawablePainter
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.appcompat.R as appcompatR

/** Height of the suggestions carousel, shared with the details page's related titles. */
val SUGGESTIONS_CAROUSEL_HEIGHT = 234.dp

interface ExploreActions {
	fun onLocalClick()
	fun onBookmarksClick()
	fun onDownloadsClick()
	fun onSuggestionsMoreClick()
	fun onMangaClick(manga: Manga)
	fun onMangaLongClick(manga: Manga, view: View)
	fun onManageClick()
	fun onCycleLayoutClick()
	fun onKindLongClick()
	fun onSourceClick(item: MangaSourceItem)
	fun onSourceLongClick(item: MangaSourceItem)
	fun onSourceWebsiteClick(item: MangaSourceItem)
	fun onSourceSettingsClick(item: MangaSourceItem)
	fun onTipClose(tip: TipModel)
}

/**
 * Explore: expressive quick-access cards, the suggestions poster carousel, and installed extensions
 * split into spotlighted pinned sources + full catalog with an inline layout switcher and live
 * pager-synced Manga / Novels pill switcher. Scrolled as one surface by the fragment's NestedScrollView.
 */
@Composable
fun ExploreScreen(
	suggestions: List<MangaCompactListModel>?,
	sources: ExploreSources,
	layout: ExtensionLayout,
	gridColumns: Int,
	isNovelFirst: Boolean,
	isSingleKind: Boolean,
	hasExtensionUpdates: Boolean,
	selectedIds: Set<Long>,
	imageLoader: ImageLoader,
	actions: ExploreActions,
) {
	val config = LocalConfiguration.current
	val density = LocalDensity.current
	var containerWidthPx by remember { mutableIntStateOf(0) }
	val containerWidthDp = if (containerWidthPx > 0) {
		with(density) { containerWidthPx.toDp() }
	} else {
		(config.screenWidthDp.dp - 16.dp).coerceAtLeast(280.dp)
	}
	val isWide = containerWidthDp >= 600.dp

	Column(
		Modifier
			.fillMaxWidth()
			.onSizeChanged { containerWidthPx = it.width },
	) {
		QuickButtons(isWide = isWide, actions = actions)
		if (suggestions != null) {
			Spacer(Modifier.height(14.dp))
			SuggestionsHeader(
				title = stringResource(R.string.suggestions),
				action = stringResource(R.string.more),
				onAction = actions::onSuggestionsMoreClick,
			)
			Spacer(Modifier.height(6.dp))
			SuggestionsCarousel(suggestions, actions)
		}
		Spacer(Modifier.height(16.dp))
		ExtensionsSection(
			sources = sources,
			layout = layout,
			gridColumns = gridColumns,
			containerWidthDp = containerWidthDp,
			isWide = isWide,
			isNovelFirst = isNovelFirst,
			isSingleKind = isSingleKind,
			hasExtensionUpdates = hasExtensionUpdates,
			selectedIds = selectedIds,
			imageLoader = imageLoader,
			actions = actions,
		)
	}
}

@Composable
private fun topBarIconColor(): Color = themeColor(R.attr.colorTopBarIcon)

/** A theme colour the shared Compose colour scheme doesn't carry over from the app theme. */
@Composable
private fun themeColor(@AttrRes attr: Int): Color {
	val context = LocalContext.current
	val uiMode = LocalConfiguration.current.uiMode
	return remember(context, uiMode, attr) { Color(context.getThemeColor(attr)) }
}

/** The app theme's badge colour, matching update dots across the app. */
@Composable
private fun UpdateDot(modifier: Modifier = Modifier) {
	Badge(modifier = modifier, containerColor = themeColor(appcompatR.attr.colorError))
}

// region Quick buttons

private val OuterCorner = 24.dp
private val InnerCorner = 4.dp
private val QuickInnerCorner = 6.dp
private val JoinedGap = 2.dp

/**
 * Local storage, bookmarks and downloads: three joined expressive cards, each with its own
 * theme-harmonized tonal badge that morphs into a circle when pressed. Like an M3 Expressive button
 * group, the pressed card widens and pushes its neighbours aside. Wide screens place the label beside the icon.
 */
@Composable
private fun QuickButtons(isWide: Boolean, actions: ExploreActions) {
	val colors = MaterialTheme.colorScheme
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(JoinedGap),
	) {
		QuickButton(
			title = R.string.local_storage,
			icon = R.drawable.ic_storage,
			shape = MaterialShapes.Cookie9Sided,
			badgeContainer = colors.primaryContainer,
			badgeContent = colors.onPrimaryContainer,
			startCorner = OuterCorner,
			endCorner = QuickInnerCorner,
			onClick = actions::onLocalClick,
			isWide = isWide,
		)
		QuickButton(
			title = R.string.bookmarks,
			icon = R.drawable.ic_bookmark,
			shape = MaterialShapes.Clover4Leaf,
			badgeContainer = colors.secondaryContainer,
			badgeContent = colors.onSecondaryContainer,
			startCorner = QuickInnerCorner,
			endCorner = QuickInnerCorner,
			onClick = actions::onBookmarksClick,
			isWide = isWide,
		)
		QuickButton(
			title = R.string.downloads,
			icon = R.drawable.ic_download,
			shape = MaterialShapes.Pentagon,
			// Tertiary is a deliberately different hue, so blend the other two to stay in the same family.
			badgeContainer = lerp(colors.primaryContainer, colors.secondaryContainer, 0.5f),
			badgeContent = lerp(colors.onPrimaryContainer, colors.onSecondaryContainer, 0.5f),
			startCorner = QuickInnerCorner,
			endCorner = OuterCorner,
			onClick = actions::onDownloadsClick,
			isWide = isWide,
		)
	}
}

@Composable
private fun RowScope.QuickButton(
	@StringRes title: Int,
	@DrawableRes icon: Int,
	shape: RoundedPolygon,
	badgeContainer: Color,
	badgeContent: Color,
	startCorner: Dp,
	endCorner: Dp,
	onClick: () -> Unit,
	isWide: Boolean,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val isPressed by interactionSource.collectIsPressedAsState()
	val motion = MaterialTheme.motionScheme
	val inner by animateDpAsState(
		targetValue = if (isPressed) 18.dp else QuickInnerCorner,
		animationSpec = motion.fastSpatialSpec(),
		label = "quickCorner",
	)
	// The M3 button group's 15% expansion; the others give up the space.
	val weight by animateFloatAsState(
		targetValue = if (isPressed) 1.15f else 1f,
		animationSpec = motion.fastSpatialSpec(),
		label = "quickWeight",
	)
	val start = if (startCorner == QuickInnerCorner) inner else startCorner
	val end = if (endCorner == QuickInnerCorner) inner else endCorner
	val morph = remember(shape) { Morph(shape, MaterialShapes.Circle) }
	val progress = remember { Animatable(0f) }
	LaunchedEffect(isPressed) {
		progress.animateTo(if (isPressed) 1f else 0f, motion.fastSpatialSpec())
	}
	Surface(
		onClick = onClick,
		modifier = Modifier.weight(weight),
		shape = RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end),
		color = MaterialTheme.colorScheme.surfaceContainer,
		interactionSource = interactionSource,
	) {
		val path = remember { Path() }
		val matrix = remember { Matrix() }
		val iconShape = @Composable {
			Box(
				modifier = Modifier
					.size(if (isWide) 46.dp else 52.dp)
					.drawBehind {
						path.rewind()
						morph.toPath(progress.value, path)
						matrix.reset()
						matrix.scale(size.width, size.height)
						path.transform(matrix)
						rotate(progress.value * 90f) { drawPath(path, badgeContainer) }
					},
				contentAlignment = Alignment.Center,
			) {
				Icon(
					painter = painterResource(icon),
					contentDescription = null,
					tint = badgeContent,
					modifier = Modifier.size(if (isWide) 24.dp else 25.dp),
				)
			}
		}
		if (isWide) {
			Row(
				modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
				horizontalArrangement = Arrangement.Center,
				verticalAlignment = Alignment.CenterVertically,
			) {
				iconShape()
				Spacer(Modifier.width(12.dp))
				Text(
					text = stringResource(title),
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		} else {
			Column(
				modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 11.dp),
				horizontalAlignment = Alignment.CenterHorizontally,
			) {
				iconShape()
				Spacer(Modifier.height(8.dp))
				Text(
					text = stringResource(title),
					style = MaterialTheme.typography.labelMedium,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

// endregion

// region Suggestions

@Composable
private fun SuggestionsHeader(title: String, action: String, onAction: () -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.onSurface,
			modifier = Modifier.weight(1f),
		)
		Surface(
			onClick = onAction,
			shape = CircleShape,
			color = MaterialTheme.colorScheme.surfaceContainerHigh,
			modifier = Modifier.height(36.dp),
		) {
			Row(
				modifier = Modifier.padding(start = 14.dp, end = 8.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(2.dp),
			) {
				Text(
					text = action,
					style = MaterialTheme.typography.labelMedium,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.primary,
				)
				Icon(
					painter = painterResource(R.drawable.ic_chevron_right),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(16.dp),
				)
			}
		}
	}
}

/** The shared View carousel the details page embeds too; here it loops around endlessly. */
@Composable
private fun SuggestionsCarousel(items: List<MangaCompactListModel>, actions: ExploreActions) {
	val currentActions by rememberUpdatedState(actions)
	// The snapped title's place in the cycle, so rotating doesn't send the carousel back to the start.
	// A plain array, not Compose state: it's written on scroll and must not trigger recompositions.
	val savedIndex = rememberSaveable { intArrayOf(-1) }
	AndroidView(
		factory = { context ->
			val binding = ItemRecommendationBinding.inflate(LayoutInflater.from(context))
			binding.recyclerView.addOnScrollListener(
				object : RecyclerView.OnScrollListener() {
					override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
						if (newState == RecyclerView.SCROLL_STATE_IDLE) {
							savedIndex[0] = (recyclerView.adapter as LoopingCarouselAdapter)
								.cycleIndexOf(recyclerView.snappedPosition())
						}
					}
				},
			)
			binding.recyclerView.setUpLoopingMangaCarousel(
				object : OnListItemClickListener<Manga> {
					override fun onItemClick(item: Manga, view: View) = currentActions.onMangaClick(item)

					override fun onItemLongClick(item: Manga, view: View): Boolean {
						currentActions.onMangaLongClick(item, view)
						return true
					}
				},
			)
			binding.root
		},
		update = { root ->
			val recyclerView = root.findViewById<RecyclerView>(R.id.recyclerView)
			// The tile in the first big slot, kept in place when a title is hidden. Tiles scrolled off still
			// have their full unmasked size, so only the carousel itself knows which one is in that slot.
			val anchor = recyclerView.snappedPosition()
			val tileWidth = root.resources.getDimension(R.dimen.carousel_tile_width)
			// Big tiles that fit, plus the narrower peeking ones: fewer titles than that can't loop without repeats
			val maxVisibleTiles = (root.resources.displayMetrics.widthPixels / tileWidth).toInt() + 2
			val adapter = recyclerView.adapter as LoopingCarouselAdapter
			val position = adapter.submit(items, anchor, maxVisibleTiles, savedIndex[0])
			if (position >= 0) {
				recyclerView.scrollToPosition(position)
				savedIndex[0] = adapter.cycleIndexOf(position)
			}
		},
		modifier = Modifier
			.fillMaxWidth()
			.height(SUGGESTIONS_CAROUSEL_HEIGHT),
	)
}

private fun RecyclerView.snappedPosition(): Int = layoutManager
	?.let { CarouselSnapHelper().findSnapView(it) }
	?.let(::getChildAdapterPosition) ?: -1

// endregion

// region Extensions

@Composable
private fun ExtensionsSection(
	sources: ExploreSources,
	layout: ExtensionLayout,
	gridColumns: Int,
	containerWidthDp: Dp,
	isWide: Boolean,
	isNovelFirst: Boolean,
	isSingleKind: Boolean,
	hasExtensionUpdates: Boolean,
	selectedIds: Set<Long>,
	imageLoader: ImageLoader,
	actions: ExploreActions,
) {
	val kinds = remember(isNovelFirst, isSingleKind) {
		listOf(isNovelFirst, !isNovelFirst).take(if (isSingleKind) 1 else 2)
	}
	val counts = remember(sources, kinds) {
		kinds.map { isNovel -> sources[isNovel].count { it is MangaSourceItem } }
	}
	val pagerState = rememberPagerState { kinds.size }
	// Back to the first tab only when the tab setup changes, not when the screen is recreated (rotation)
	val kindsSetup = (if (isNovelFirst) 1 else 0) + (if (isSingleKind) 2 else 0)
	var shownSetup by rememberSaveable { mutableIntStateOf(kindsSetup) }
	LaunchedEffect(kindsSetup) {
		if (kindsSetup != shownSetup) {
			shownSetup = kindsSetup
			pagerState.scrollToPage(0)
		}
	}
	val isSelecting = selectedIds.isNotEmpty()
	ExtensionsToolbar(
		kinds = kinds,
		counts = counts,
		pagerState = pagerState,
		layout = layout,
		isWide = isWide,
		enabled = !isSelecting,
		hasExtensionUpdates = hasExtensionUpdates,
		actions = actions,
	)
	Spacer(Modifier.height(10.dp))
	val view = LocalView.current
	val heights = remember(kinds) { mutableStateMapOf<Int, Int>() }
	val tallestPx = kinds.indices.maxOfOrNull { heights[it] ?: 0 } ?: 0
	val density = LocalDensity.current
	// Both pages share the taller tab's height (at least 260dp): swiping never resizes the scroll container
	// mid-gesture, and the empty area below the shorter tab remains swipable back and forth.
	val minPagerHeightDp = maxOf(with(density) { tallestPx.toDp() }, 260.dp)

	HorizontalPager(
		state = pagerState,
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(min = minPagerHeightDp)
			// Claim horizontal swipes synchronously before NestedScrollView can steal a diagonal drag.
			.pointerInput(kinds.size, isSelecting) {
				if (kinds.size <= 1 || isSelecting) return@pointerInput
				awaitEachGesture {
					val down = awaitFirstDown(requireUnconsumed = false)
					var dx = 0f
					var dy = 0f
					while (true) {
						val event = awaitPointerEvent(PointerEventPass.Initial)
						val change = event.changes.firstOrNull { it.id == down.id } ?: break
						if (!change.pressed) break
						dx += change.position.x - change.previousPosition.x
						dy += change.position.y - change.previousPosition.y
						val absDx = abs(dx)
						val absDy = abs(dy)
						if (absDx > viewConfiguration.touchSlop || absDy > viewConfiguration.touchSlop) {
							if (absDx > absDy) {
								view.parent?.requestDisallowInterceptTouchEvent(true)
							}
							break
						}
					}
				}
			},
		pageSpacing = 16.dp,
		verticalAlignment = Alignment.Top,
		beyondViewportPageCount = 1,
		userScrollEnabled = kinds.size > 1 && !isSelecting,
	) { page ->
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(min = minPagerHeightDp),
			contentAlignment = Alignment.TopCenter,
		) {
			Box(
				Modifier
					.fillMaxWidth()
					.wrapContentHeight(Alignment.Top, unbounded = true),
			) {
				SourcesPage(
					items = sources[kinds[page]],
					layout = layout,
					gridColumns = gridColumns,
					maxWidth = containerWidthDp,
					selectedIds = selectedIds,
					imageLoader = imageLoader,
					actions = actions,
					modifier = Modifier.onSizeChanged { heights[page] = it.height },
				)
			}
		}
	}
}

/**
 * Extensions section header: "Extensions" title, the pager-synced Manga / Novels pill and the connected
 * [Layout | Manage] pair. Phones put the pill and the pair on a second row under the title; wide screens
 * keep everything on one row. In single-kind mode a compact chip next to "Extensions" replaces the pill.
 */
@Composable
private fun ExtensionsToolbar(
	kinds: List<Boolean>,
	counts: List<Int>,
	pagerState: PagerState,
	layout: ExtensionLayout,
	isWide: Boolean,
	enabled: Boolean,
	hasExtensionUpdates: Boolean,
	actions: ExploreActions,
) {
	val isMultiKind = kinds.size > 1
	val switcher = @Composable {
		KindSwitcherPill(
			kinds = kinds,
			counts = counts,
			pagerState = pagerState,
			enabled = enabled,
			onLongClick = actions::onKindLongClick,
		)
	}
	val actionPair = @Composable {
		ExtensionsActionPair(
			layout = layout,
			hasUpdates = hasExtensionUpdates,
			onCycleLayout = actions::onCycleLayoutClick,
			onManage = actions::onManageClick,
		)
	}
	Column(Modifier.fillMaxWidth()) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Row(
				modifier = Modifier
					.weight(1f)
					.padding(start = 8.dp),
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Text(
					text = stringResource(R.string.extensions),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				if (!isMultiKind) {
					SingleKindBadge(
						isNovel = kinds.firstOrNull() == true,
						count = counts.firstOrNull() ?: 0,
						enabled = enabled,
						onClick = actions::onKindLongClick,
					)
				}
			}
			if (isMultiKind && isWide) {
				// Equal-weight sides keep the pill in the true centre of the row.
				switcher()
				Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { actionPair() }
			} else if (!isMultiKind) {
				Spacer(Modifier.width(8.dp))
				actionPair()
			}
		}
		if (isMultiKind && !isWide) {
			Spacer(Modifier.height(10.dp))
			Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
				switcher()
				Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
				actionPair()
			}
		}
	}
}

@Composable
private fun SingleKindBadge(
	isNovel: Boolean,
	count: Int,
	enabled: Boolean,
	onClick: () -> Unit,
) {
	val title = stringResource(if (isNovel) R.string.store_kind_novel else R.string.store_kind_manga)
	val colors = MaterialTheme.colorScheme
	Row(
		modifier = Modifier
			.clip(CircleShape)
			.alpha(if (enabled) 1f else 0.45f)
			.background(colors.surfaceContainerHigh, CircleShape)
			.combinedClickable(
				enabled = enabled,
				onClick = onClick,
				onLongClick = onClick,
			)
			.padding(horizontal = 10.dp, vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(5.dp),
	) {
		Text(
			text = if (count > 0) "$title · $count" else title,
			style = MaterialTheme.typography.labelSmall,
			fontWeight = FontWeight.SemiBold,
			color = colors.onSurfaceVariant,
			maxLines = 1,
			softWrap = false,
		)
		Icon(
			painter = painterResource(R.drawable.ic_swap),
			contentDescription = stringResource(R.string.explore_tabs),
			tint = colors.onSurfaceVariant,
			modifier = Modifier.size(13.dp),
		)
	}
}

@Composable
private fun KindSwitcherPill(
	kinds: List<Boolean>,
	counts: List<Int>,
	pagerState: PagerState,
	enabled: Boolean,
	onLongClick: () -> Unit,
) {
	val scope = rememberCoroutineScope()
	val haptic = rememberHapticEffect()
	val colors = MaterialTheme.colorScheme
	val motion = MaterialTheme.motionScheme
	val titles = kinds.map { stringResource(if (it) R.string.store_kind_novel else R.string.store_kind_manga) }
	// Only flip composition state once at the halfway point; the capsule itself glides in drawBehind.
	val selectedTab by remember(pagerState, kinds.size) {
		derivedStateOf {
			(pagerState.currentPage + pagerState.currentPageOffsetFraction)
				.roundToInt()
				.coerceIn(0, (kinds.size - 1).coerceAtLeast(0))
		}
	}
	val tabWidths = remember { intArrayOf(0, 0) }
	val stretchMaxPx = with(LocalDensity.current) { 6.dp.toPx() }

	Surface(
		shape = CircleShape,
		color = colors.surfaceContainer,
		modifier = Modifier.alpha(if (enabled) 1f else 0.45f),
	) {
		Layout(
			modifier = Modifier
				.padding(3.dp)
				.drawBehind {
					val w0 = tabWidths[0].toFloat()
					val w1 = tabWidths[1].toFloat()
					if (w0 <= 0f || w1 <= 0f) return@drawBehind
					val f = (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, 1f)
					val baseLeft = lerpValue(0f, w0, f)
					val baseWidth = lerpValue(w0, w1, f)
					val stretch = sin(f * PI).toFloat() * stretchMaxPx
					val rawLeft = (baseLeft - stretch * 0.5f).coerceAtLeast(0f)
					val drawWidth = (baseWidth + stretch).coerceAtMost(size.width - rawLeft)
					val drawLeft = if (layoutDirection == LayoutDirection.Rtl) {
						size.width - rawLeft - drawWidth
					} else {
						rawLeft
					}
					val r = size.height * 0.5f
					drawRoundRect(
						color = colors.primary,
						topLeft = Offset(drawLeft, 0f),
						size = Size(drawWidth, size.height),
						cornerRadius = CornerRadius(r, r),
					)
				},
			content = {
				titles.forEachIndexed { index, title ->
					val isSelected = index == selectedTab
					val textColor by animateColorAsState(
						targetValue = if (isSelected) colors.onPrimary else colors.onSurfaceVariant,
						animationSpec = motion.fastEffectsSpec(),
						label = "tabTextColor",
					)
					val countBg by animateColorAsState(
						targetValue = if (isSelected) colors.onPrimary.copy(alpha = 0.20f) else colors.surfaceContainerHighest,
						animationSpec = motion.fastEffectsSpec(),
						label = "tabCountBg",
					)
					val count = counts.getOrElse(index) { 0 }
					Row(
						modifier = Modifier
							.height(40.dp)
							.clip(CircleShape)
							.semantics {
								role = Role.Tab
								selected = isSelected
							}
							.combinedClickable(
								enabled = enabled,
								indication = null,
								interactionSource = remember { MutableInteractionSource() },
								onLongClick = onLongClick,
								onClick = {
									if (pagerState.currentPage != index || pagerState.currentPageOffsetFraction != 0f) {
										haptic(HapticEffect.LIGHT_CLICK)
										scope.launch { pagerState.animateScrollToPage(index) }
									}
								},
							)
							.padding(horizontal = 14.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
					) {
						Text(
							text = title,
							style = MaterialTheme.typography.labelMedium,
							fontWeight = FontWeight.SemiBold,
							color = textColor,
							maxLines = 1,
							softWrap = false,
						)
						if (count > 0) {
							Text(
								text = count.toString(),
								style = MaterialTheme.typography.labelSmall,
								fontWeight = FontWeight.SemiBold,
								color = textColor,
								maxLines = 1,
								softWrap = false,
								modifier = Modifier
									.background(countBg, CircleShape)
									.padding(horizontal = 6.dp, vertical = 1.dp),
							)
						}
					}
				}
			},
		) { measurables, constraints ->
			val p0 = measurables[0].measure(Constraints(minWidth = 0, maxWidth = constraints.maxWidth))
			val rem = (constraints.maxWidth - p0.width).coerceAtLeast(0)
			val p1 = measurables[1].measure(Constraints(minWidth = 0, maxWidth = rem))
			tabWidths[0] = p0.width
			tabWidths[1] = p1.width
			val totalW = p0.width + p1.width
			val totalH = maxOf(p0.height, p1.height)
			layout(totalW, totalH) {
				p0.placeRelative(0, (totalH - p0.height) / 2)
				p1.placeRelative(p0.width, (totalH - p1.height) / 2)
			}
		}
	}
}

@Composable
private fun ExtensionsActionPair(
	layout: ExtensionLayout,
	hasUpdates: Boolean,
	onCycleLayout: () -> Unit,
	onManage: () -> Unit,
) {
	val colors = MaterialTheme.colorScheme
	val layoutLabel = stringResource(
		when (layout) {
			ExtensionLayout.CHIPS -> R.string.chips
			ExtensionLayout.GRID -> R.string.grid
			ExtensionLayout.LIST -> R.string.list
		},
	)
	val layoutDescription = stringResource(R.string.extension_layout) + ": " + layoutLabel

	Row(horizontalArrangement = Arrangement.spacedBy(JoinedGap)) {
		Surface(
			onClick = onCycleLayout,
			shape = RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp, topEnd = 6.dp, bottomEnd = 6.dp),
			color = colors.surfaceContainerHigh,
			modifier = Modifier.size(width = 38.dp, height = 36.dp),
		) {
			Box(contentAlignment = Alignment.Center) {
				AnimatedContent(
					targetState = layout,
					transitionSpec = {
						(scaleIn(initialScale = 0.75f) + fadeIn()) togetherWith
							(scaleOut(targetScale = 0.75f) + fadeOut())
					},
					label = "layoutIcon",
				) { mode ->
					Icon(
						painter = painterResource(
							when (mode) {
								ExtensionLayout.CHIPS -> R.drawable.ic_explore_layout_chips
								ExtensionLayout.GRID -> R.drawable.ic_explore_layout_grid
								ExtensionLayout.LIST -> R.drawable.ic_explore_layout_list
							},
						),
						contentDescription = layoutDescription,
						tint = topBarIconColor(),
						modifier = Modifier.size(19.dp),
					)
				}
			}
		}
		Box {
			Surface(
				onClick = onManage,
				shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 18.dp, bottomEnd = 18.dp),
				color = colors.surfaceContainerHigh,
				modifier = Modifier.height(36.dp),
			) {
				Row(
					modifier = Modifier.padding(start = 12.dp, end = 8.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(2.dp),
				) {
					Text(
						text = stringResource(R.string.manage),
						style = MaterialTheme.typography.labelMedium,
						fontWeight = FontWeight.SemiBold,
						color = colors.primary,
						maxLines = 1,
						softWrap = false,
					)
					Icon(
						painter = painterResource(R.drawable.ic_chevron_right),
						contentDescription = null,
						tint = colors.primary,
						modifier = Modifier.size(16.dp),
					)
				}
			}
			if (hasUpdates) {
				UpdateDot(
					Modifier
						.align(Alignment.TopEnd)
						.padding(top = 2.dp, end = 2.dp),
				)
			}
		}
	}
}

@Composable
private fun SourcesPage(
	items: List<ListModel>,
	layout: ExtensionLayout,
	gridColumns: Int,
	maxWidth: Dp,
	selectedIds: Set<Long>,
	imageLoader: ImageLoader,
	actions: ExploreActions,
	modifier: Modifier = Modifier,
) {
	val sourceItems = remember(items) { items.filterIsInstance<MangaSourceItem>() }
	val (pinned, unpinned) = remember(sourceItems) { sourceItems.partition { it.source.isPinned } }
	val isSelecting = selectedIds.isNotEmpty()

	Column(modifier.fillMaxWidth()) {
		// Pinned extensions are their own section at the top of the tab, under their own heading.
		if (pinned.isNotEmpty()) {
			PinnedGroupLabel()
			SourcesGroupBlock(
				sourceItems = pinned,
				layout = layout,
				gridColumns = gridColumns,
				maxWidth = maxWidth,
				selectedIds = selectedIds,
				isSelecting = isSelecting,
				imageLoader = imageLoader,
				actions = actions,
			)
			if (unpinned.isNotEmpty()) {
				Spacer(Modifier.height(16.dp))
			}
		}
		if (unpinned.isNotEmpty()) {
			SourcesGroupBlock(
				sourceItems = unpinned,
				layout = layout,
				gridColumns = gridColumns,
				maxWidth = maxWidth,
				selectedIds = selectedIds,
				isSelecting = isSelecting,
				imageLoader = imageLoader,
				actions = actions,
			)
		}
		for (item in items) {
			when (item) {
				is LoadingState -> Box(
					modifier = Modifier
						.fillMaxWidth()
						.padding(vertical = 40.dp),
					contentAlignment = Alignment.Center,
				) {
					LoadingIndicator()
				}

				is EmptyState -> EmptyStateBlock(item)
				is TipModel -> TipBlock(item, actions::onTipClose)
				else -> Unit
			}
		}
	}
}

@Composable
private fun PinnedGroupLabel() {
	Row(
		modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Icon(
			painter = painterResource(R.drawable.ic_pin_small),
			contentDescription = null,
			tint = MaterialTheme.colorScheme.primary,
			modifier = Modifier.size(14.dp),
		)
		Text(
			text = stringResource(R.string.pinned),
			style = MaterialTheme.typography.labelMedium,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.primary,
		)
	}
}

@Composable
private fun SourcesGroupBlock(
	sourceItems: List<MangaSourceItem>,
	layout: ExtensionLayout,
	gridColumns: Int,
	maxWidth: Dp,
	selectedIds: Set<Long>,
	isSelecting: Boolean,
	imageLoader: ImageLoader,
	actions: ExploreActions,
) {
	when (layout) {
		ExtensionLayout.CHIPS -> {
			val columns = (maxWidth / 170.dp).toInt().coerceAtLeast(2)
			SimpleGrid(sourceItems, columns, JoinedGap, MangaSourceItem::id) { index, item ->
				SourceChip(
					item = item,
					index = index,
					count = sourceItems.size,
					columns = columns,
					isSelected = item.id in selectedIds,
					imageLoader = imageLoader,
					actions = actions,
				)
			}
		}

		ExtensionLayout.GRID -> {
			val cellWidth = when (gridColumns) {
				3 -> 150.dp
				5 -> 84.dp
				else -> 108.dp
			}
			val columns = ceil(maxWidth / cellWidth).toInt().coerceAtLeast(1)
			SimpleGrid(sourceItems, columns, JoinedGap, MangaSourceItem::id) { index, item ->
				SourceTile(
					item = item,
					index = index,
					count = sourceItems.size,
					columns = columns,
					isSelected = item.id in selectedIds,
					isDense = gridColumns >= 5,
					imageLoader = imageLoader,
					actions = actions,
				)
			}
		}

		ExtensionLayout.LIST -> Column(verticalArrangement = Arrangement.spacedBy(JoinedGap)) {
			sourceItems.forEachIndexed { index, item ->
				key(item.id) {
					SourceRow(
						item = item,
						index = index,
						count = sourceItems.size,
						isSelected = item.id in selectedIds,
						isSelecting = isSelecting,
						imageLoader = imageLoader,
						actions = actions,
					)
				}
			}
		}
	}
}

/** A non-lazy grid: the whole Explore page is one scroll, so every extension is laid out anyway. */
@Composable
private fun <T> SimpleGrid(
	items: List<T>,
	columns: Int,
	spacing: Dp,
	key: (T) -> Any,
	content: @Composable (index: Int, item: T) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
		for ((rowIndex, row) in items.chunked(columns).withIndex()) {
			Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
				for ((column, item) in row.withIndex()) {
					key(key(item)) {
						Box(Modifier.weight(1f)) { content(rowIndex * columns + column, item) }
					}
				}
				repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
			}
		}
	}
}

/** Clickable + long-click (selection) + the selected outline and the little dip when it toggles. */
@Composable
private fun Modifier.sourceItem(
	item: MangaSourceItem,
	isSelected: Boolean,
	shape: Shape,
	actions: ExploreActions,
	interactionSource: MutableInteractionSource? = null,
): Modifier {
	val scale = remember { Animatable(1f) }
	val isFirst = remember { booleanArrayOf(true) }
	LaunchedEffect(isSelected) {
		if (isFirst[0]) {
			isFirst[0] = false
			return@LaunchedEffect
		}
		scale.animateTo(0.94f, spring(stiffness = Spring.StiffnessHigh))
		scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
	}
	val colors = MaterialTheme.colorScheme
	val baseContainer = colors.surfaceContainer
	return this
		.graphicsLayer {
			scaleX = scale.value
			scaleY = scale.value
		}
		.clip(shape)
		.background(if (isSelected) lerp(baseContainer, colors.primary, 0.20f) else baseContainer)
		.then(if (isSelected) Modifier.border(2.dp, colors.primary, shape) else Modifier)
		.combinedClickable(
			interactionSource = interactionSource,
			indication = LocalIndication.current,
			hapticFeedbackEnabled = false,
			onLongClick = { actions.onSourceLongClick(item) },
			onClick = { actions.onSourceClick(item) },
		)
}

@Composable
private fun SourceChip(
	item: MangaSourceItem,
	index: Int,
	count: Int,
	columns: Int,
	isSelected: Boolean,
	imageLoader: ImageLoader,
	actions: ExploreActions,
) {
	val context = LocalContext.current
	val interactionSource = remember { MutableInteractionSource() }
	val shape = joinedGridShape(index, count, columns, pressedSeam(interactionSource))
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(56.dp)
			.sourceItem(item, isSelected, shape, actions, interactionSource)
			.padding(start = 8.dp, end = 12.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		SourceIcon(item, imageLoader, RoundedCornerShape(12.dp), Modifier.size(40.dp))
		Spacer(Modifier.width(10.dp))
		SourceTitle(
			title = item.source.getTitle(context),
			style = MaterialTheme.typography.titleSmall,
			modifier = Modifier.weight(1f),
		)
	}
}

/** A joined item's inner (seam) corner: it rounds off a little while pressed. */
@Composable
private fun pressedSeam(interactionSource: MutableInteractionSource): Dp {
	val isPressed by interactionSource.collectIsPressedAsState()
	return animateDpAsState(
		if (isPressed) 14.dp else InnerCorner,
		MaterialTheme.motionScheme.fastSpatialSpec(),
		label = "corner",
	).value
}

/** One joined group across the grid: a corner is round only where it is on the group's outer edge. */
private fun joinedGridShape(index: Int, count: Int, columns: Int, inner: Dp): Shape {
	val column = index % columns
	val hasAbove = index >= columns
	val hasBelow = index + columns < count
	val hasLeft = column > 0
	val hasRight = column < columns - 1 && index + 1 < count
	fun corner(isOuter: Boolean) = if (isOuter) OuterCorner else inner
	return RoundedCornerShape(
		topStart = corner(!hasAbove && !hasLeft),
		topEnd = corner(!hasAbove && !hasRight),
		bottomEnd = corner(!hasBelow && !hasRight),
		bottomStart = corner(!hasBelow && !hasLeft),
	)
}

/** Joined vertical stack shape with animated seam rounding on press. */
private fun joinedStackShape(index: Int, count: Int, inner: Dp): Shape = when {
	count <= 1 -> RoundedCornerShape(OuterCorner)
	index == 0 -> RoundedCornerShape(topStart = OuterCorner, topEnd = OuterCorner, bottomStart = inner, bottomEnd = inner)
	index == count - 1 -> RoundedCornerShape(topStart = inner, topEnd = inner, bottomStart = OuterCorner, bottomEnd = OuterCorner)
	else -> RoundedCornerShape(inner)
}

@Composable
private fun SourceTile(
	item: MangaSourceItem,
	index: Int,
	count: Int,
	columns: Int,
	isSelected: Boolean,
	isDense: Boolean,
	imageLoader: ImageLoader,
	actions: ExploreActions,
) {
	val context = LocalContext.current
	val title = item.source.getTitle(context)
	val interactionSource = remember { MutableInteractionSource() }
	val shape = joinedGridShape(index, count, columns, pressedSeam(interactionSource))
	val iconSize = if (isDense) 44.dp else 56.dp
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.sourceItem(item, isSelected, shape, actions, interactionSource)
			.padding(horizontal = if (isDense) 4.dp else 8.dp, vertical = if (isDense) 8.dp else 12.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		SourceIcon(
			item = item,
			imageLoader = imageLoader,
			shape = RoundedCornerShape(if (isDense) 12.dp else 14.dp),
			modifier = Modifier.size(iconSize),
		)
		Spacer(Modifier.height(6.dp))
		Text(
			text = title,
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurface,
			textAlign = TextAlign.Center,
			minLines = 2,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.padding(horizontal = 2.dp),
		)
	}
}

@Composable
private fun SourceRow(
	item: MangaSourceItem,
	index: Int,
	count: Int,
	isSelected: Boolean,
	isSelecting: Boolean,
	imageLoader: ImageLoader,
	actions: ExploreActions,
) {
	val context = LocalContext.current
	val interactionSource = remember { MutableInteractionSource() }
	val shape = joinedStackShape(index, count, pressedSeam(interactionSource))
	val subtitle = remember(context, item.source, item.hasUpdate) {
		val summary = item.source.getSummary(context).orEmpty()
		val warning = (item.source.mangaSource as? MihonMangaSource)?.contentWarning
		val tagRes = warning?.tagResId
		val colorRes = warning?.tagColorRes
		buildAnnotatedString {
			append(summary)
			if (tagRes != null && colorRes != null) {
				if (summary.isNotEmpty()) append(" • ")
				withStyle(SpanStyle(color = Color(ContextCompat.getColor(context, colorRes)))) {
					append(context.getString(tagRes))
				}
			}
		}
	}
	val actionsAlpha by animateFloatAsState(
		targetValue = if (isSelecting) 0.38f else 1f,
		animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
		label = "rowActionsAlpha",
	)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.sourceItem(item, isSelected, shape, actions, interactionSource)
			.padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		SourceIcon(item, imageLoader, RoundedCornerShape(12.dp), Modifier.size(42.dp))
		Column(
			modifier = Modifier
				.weight(1f)
				.padding(horizontal = 14.dp),
		) {
			SourceTitle(item.source.getTitle(context), MaterialTheme.typography.titleSmall)
			if (subtitle.isNotEmpty()) {
				Spacer(Modifier.height(1.dp))
				Text(
					text = subtitle,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
		Row(
			modifier = Modifier.alpha(actionsAlpha),
			horizontalArrangement = Arrangement.spacedBy(JoinedGap),
		) {
			ActionButton(
				icon = R.drawable.ic_website,
				description = R.string.open_website,
				shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 6.dp, bottomEnd = 6.dp),
				onClick = { actions.onSourceWebsiteClick(item) },
			)
			ActionButton(
				icon = R.drawable.ic_extension_settings,
				description = R.string.extension_settings,
				shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = 20.dp, bottomEnd = 20.dp),
				onClick = { actions.onSourceSettingsClick(item) },
			)
		}
	}
}

@Composable
private fun ActionButton(
	@DrawableRes icon: Int,
	@StringRes description: Int,
	shape: Shape,
	onClick: () -> Unit,
) {
	Surface(
		onClick = onClick,
		shape = shape,
		color = MaterialTheme.colorScheme.surfaceContainerHighest,
		modifier = Modifier.size(40.dp),
	) {
		Box(contentAlignment = Alignment.Center) {
			Icon(
				painter = painterResource(icon),
				contentDescription = stringResource(description),
				tint = topBarIconColor(),
				modifier = Modifier.size(20.dp),
			)
		}
	}
}

@Composable
private fun SourceTitle(
	title: String,
	style: TextStyle,
	modifier: Modifier = Modifier,
) {
	Text(
		text = title,
		style = style,
		color = MaterialTheme.colorScheme.onSurface,
		maxLines = 1,
		overflow = TextOverflow.Ellipsis,
		modifier = modifier,
	)
}

/** The extension's icon, with the update dot when its store has a newer build. */
@Composable
private fun SourceIcon(
	item: MangaSourceItem,
	imageLoader: ImageLoader,
	shape: Shape,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val uiMode = LocalConfiguration.current.uiMode
	val source = item.source
	val title = source.getTitle(context)
	val fallback = remember(context, uiMode, title) {
		DrawablePainter(FaviconDrawable(context, R.style.FaviconDrawable_Small, title))
	}
	val request = remember(context, source) {
		ImageRequest.Builder(context)
			.data(source.faviconUri())
			.mangaSourceExtra(source)
			.suppressCaptchaErrors()
			.build()
	}
	val isExternal = source.mangaSource.isExternalSource()
	Box(modifier) {
		AsyncImage(
			model = request,
			imageLoader = imageLoader,
			contentDescription = null,
			error = fallback,
			fallback = fallback,
			contentScale = if (isExternal) ContentScale.Crop else ContentScale.Fit,
			modifier = Modifier
				.fillMaxSize()
				// Novel plugin icons are drawn edge to edge, unlike extension icons with their own border
				.graphicsLayer {
					if (source.mangaSource.isNovelSource) {
						scaleX = 0.8f
						scaleY = 0.8f
					}
				}
				.clip(shape)
				.then(
					if (isExternal) {
						Modifier
					} else {
						Modifier
							.background(MaterialTheme.colorScheme.surfaceContainerHighest)
							.border(1.dp, MaterialTheme.colorScheme.outline, shape)
					},
				),
		)
		if (item.hasUpdate) {
			UpdateDot(Modifier.align(Alignment.TopEnd))
		}
	}
}

@Composable
private fun EmptyStateBlock(state: EmptyState) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 32.dp, vertical = 24.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
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
			color = MaterialTheme.colorScheme.onSurface,
			textAlign = TextAlign.Center,
		)
		if (state.textSecondary != 0) {
			Spacer(Modifier.height(16.dp))
			Text(
				text = stringResource(state.textSecondary),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				textAlign = TextAlign.Center,
			)
		}
	}
}

/** The app's tip card View, so it looks like every other tip. */
@Composable
private fun TipBlock(tip: TipModel, onClose: (TipModel) -> Unit) {
	val currentOnClose by rememberUpdatedState(onClose)
	AndroidView(
		factory = { context ->
			ItemTip2Binding.inflate(LayoutInflater.from(context)).root.apply {
				onCloseListener = android.view.View.OnClickListener { (tag as? TipModel)?.let(currentOnClose) }
			}
		},
		update = { view ->
			view.tag = tip
			view.setTitle(tip.title)
			view.setText(tip.text)
			view.setIcon(tip.icon)
			view.setPrimaryButtonText(tip.primaryButtonText)
			view.setSecondaryButtonText(tip.secondaryButtonText)
			view.setClosable(tip.isClosable)
		},
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = 12.dp),
	)
}

// endregion
