@file:Suppress("DEPRECATION")

package org.koitharu.kotatsu.details.ui

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowOverflow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.titleResId
import org.koitharu.kotatsu.core.prefs.DetailsUiMode
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.parser.favicon.faviconUri
import org.koitharu.kotatsu.core.util.FileSize
import org.koitharu.kotatsu.core.util.ext.isRemoteCoverUrl
import org.koitharu.kotatsu.core.util.ext.mangaCoverDiskCacheKey
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.stableMangaCoverKey
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.ui.model.HistoryInfo
import org.koitharu.kotatsu.details.ui.scrobbling.labelResId
import org.koitharu.kotatsu.list.domain.ReadingProgress
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Navigation/action hooks for the expressive details screen. The activity owns the router
 * and supplies these so the Compose layer stays free of Android navigation plumbing.
 */
class DetailsExpressiveActions(
	val onCoverClick: (Manga) -> Unit,
	val onTitleClick: (String) -> Unit,
	val onSourceClick: (Manga) -> Unit,
	val onLocalClick: (Manga) -> Unit,
	val onFavoriteClick: (Manga) -> Unit,
	val onAuthorClick: (String) -> Unit,
	val onTagClick: (MangaTag) -> Unit,
	val onScrobblingMore: () -> Unit,
	val onScrobblingCardClick: (Int) -> Unit,
	val onRelatedMore: (Manga) -> Unit,
	val onRelatedClick: (MangaListModel) -> Unit,
	val onReadClick: () -> Unit,
	val onIncognitoClick: () -> Unit,
	val onForgetHistoryClick: () -> Unit,
	val onChaptersClick: () -> Unit,
)

private val SCREEN_PADDING = 20.dp
private val CARD_CORNER = 26.dp
private val COVER_WIDTH = 158.dp
private val COVER_HEIGHT = 236.dp
private val COMPACT_COVER_WIDTH = 120.dp
private val COMPACT_COVER_HEIGHT = 178.dp
private const val TAGS_COLLAPSED_ROWS = 3
// Vertical room the floating action dock (chapters pill + read FAB) needs, so the scrolling content
// can always be pushed clear of it and nothing hides behind the FAB at the end of the page.
private val DETAIL_DOCK_RESERVE = 128.dp

@Composable
fun DetailsExpressiveScreen(
	details: MangaDetails?,
	tags: List<ChipsView.ChipModel>,
	historyInfo: HistoryInfo,
	isLoading: Boolean,
	favouriteCount: Int,
	favouriteLabel: String?,
	scrobblings: List<ScrobblingInfo>,
	related: List<MangaListModel>,
	localSize: Long,
	sourceTitle: String?,
	imageLoader: ImageLoader,
	coverUrl: String?,
	backdropUrl: String?,
	isBackdropEnabled: Boolean,
	backdropBlurAmount: Int,
	style: DetailsUiMode,
	topInset: Dp,
	bottomContentPadding: Dp,
	onScroll: (Int) -> Unit,
	actions: DetailsExpressiveActions,
) {
	val manga = details?.toManga()
	val baseScheme = MaterialTheme.colorScheme
	val typography = MaterialTheme.typography

	MaterialTheme(colorScheme = baseScheme, typography = typography) {
		val scheme = MaterialTheme.colorScheme
		val accentColor = scheme.primary
		val scrollState = rememberScrollState()
		val centered = style != DetailsUiMode.COMPACT

		LaunchedEffect(scrollState) {
			snapshotFlow { scrollState.value }.collect(onScroll)
		}

		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(scheme.surface),
		) {
			if (isBackdropEnabled && backdropUrl != null) {
				ExpressiveBackdrop(
					url = backdropUrl,
					manga = manga,
					imageLoader = imageLoader,
					surface = scheme.surface,
					blurAmount = backdropBlurAmount,
				)
			}

			Column(
				modifier = Modifier
					.fillMaxSize()
					.verticalScroll(scrollState)
					.padding(bottom = bottomContentPadding + DETAIL_DOCK_RESERVE),
				horizontalAlignment = Alignment.CenterHorizontally,
			) {
				// Push the hero clear of the translucent top bar / back button.
			Spacer(Modifier.height(topInset + if (centered) 84.dp else 72.dp))

			if (manga == null) {
				LoadingHero()
			} else {
				val favLabel = favouriteLabel ?: stringResource(R.string.add_to_favourites)
				val isFavourite = favouriteCount > 0
				HeroSection(
					centered = centered,
					manga = manga,
					details = details,
					sourceTitle = sourceTitle,
					accent = accentColor,
					imageLoader = imageLoader,
					coverUrl = coverUrl,
					favouriteLabel = favLabel,
					isFavourite = isFavourite,
					onFavouriteClick = { actions.onFavoriteClick(manga) },
					actions = actions,
				)

				// In the centered style the favourite button is a full-width action below the hero;
				// in compact it lives in the info column beside the cover (added inside HeroSection).
				if (centered) {
					Spacer(Modifier.height(20.dp))
					FavouriteButton(
						label = favLabel,
						isFavourite = isFavourite,
						accent = accentColor,
						onClick = { actions.onFavoriteClick(manga) },
					)
				}

				Spacer(Modifier.height(8.dp))
				ProgressCard(historyInfo = historyInfo, isLoading = isLoading, accent = accentColor)

				DescriptionCard(
					description = details.description,
					manga = manga,
					details = details,
					accent = accentColor,
				)

				TagsSection(tags = tags, accent = accentColor, onTagClick = actions.onTagClick)

				if (scrobblings.isNotEmpty()) {
					ScrobblingSection(
						items = scrobblings,
						imageLoader = imageLoader,
						accent = accentColor,
						onMore = actions.onScrobblingMore,
						onCardClick = actions.onScrobblingCardClick,
					)
				}

				if (related.isNotEmpty()) {
					RelatedSection(
						items = related,
						imageLoader = imageLoader,
						accent = accentColor,
						onMore = { actions.onRelatedMore(manga) },
						onItemClick = actions.onRelatedClick,
					)
				}

				if (localSize > 0L) {
					LocalSizeRow(size = localSize, manga = manga, onClick = actions.onLocalClick)
				}

					Spacer(Modifier.height(28.dp))
				}
			}

				// Floating action dock: a "N chapters" pill stacked above the read FAB. Both pin to the
				// bottom-end and stay clear of the navigation bar; the modal chapters sheet draws its own
				// scrim over them, so they read as "behind" the sheet without any extra hide/show logic.
				ActionDock(
					historyInfo = historyInfo,
					isLoading = isLoading,
					accent = accentColor,
					actions = actions,
					modifier = Modifier
						.align(Alignment.BottomEnd)
						.padding(end = SCREEN_PADDING, bottom = bottomContentPadding + 16.dp),
				)
		}
	}
}

/**
 * The bottom-end action dock that replaces the old pull-up peek bar: a compact "N chapters" pill
 * resting above a tactile Read/Continue FAB. The pill opens the chapters sheet; the FAB starts
 * reading, with a small overflow for incognito / forget-history. Both sit in the page's [Box] (not
 * the scrolling column) so they stay pinned while the content scrolls underneath.
 */
@Composable
private fun ActionDock(
	historyInfo: HistoryInfo,
	isLoading: Boolean,
	accent: Color,
	actions: DetailsExpressiveActions,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier,
		horizontalAlignment = Alignment.End,
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		// Show the chapters pill whenever a count is known — including chapters served from cache while
		// a fresh copy is still loading from the source — so it doesn't vanish during a refresh.
		val chapterCount = historyInfo.totalChapters
		if (chapterCount > 0) {
			ChaptersPill(count = chapterCount, onClick = actions.onChaptersClick)
		}
		ReadFab(historyInfo = historyInfo, isLoading = isLoading, accent = accent, actions = actions)
	}
}

@Composable
private fun ChaptersPill(count: Int, onClick: () -> Unit) {
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(50),
		color = MaterialTheme.colorScheme.surfaceContainerHighest,
		tonalElevation = 3.dp,
		shadowElevation = 3.dp,
	) {
		Row(
			modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Icon(
				painter = painterResource(R.drawable.ic_list),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(18.dp),
			)
			Text(
				text = pluralStringResource(R.plurals.chapters, count, count),
				style = MaterialTheme.typography.labelLarge,
				fontWeight = FontWeight.Medium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
			)
		}
	}
}

@Composable
private fun ReadFab(
	historyInfo: HistoryInfo,
	isLoading: Boolean,
	accent: Color,
	actions: DetailsExpressiveActions,
) {
	// Label / enabled state mirror the old read split-button exactly.
	val isChaptersLoading = isLoading && (historyInfo.totalChapters <= 0 || historyInfo.isChapterMissing)
	val enabled = !isChaptersLoading && historyInfo.isValid
	val label = when {
		isChaptersLoading -> stringResource(R.string.loading_)
		historyInfo.isIncognitoMode -> stringResource(R.string.incognito)
		historyInfo.canContinue -> stringResource(R.string._continue)
		else -> stringResource(R.string.read)
	}
	// The overflow keeps only the reading-related quick actions; chapter-list display options
	// (reverse, grid, on-device) live in the chapters sheet's own toolbar menu where they act on
	// the list.
	val canIncognito = !historyInfo.isIncognitoMode
	val canForget = historyInfo.history != null
	val hasMenu = enabled && (canIncognito || canForget)

	var expanded by rememberSaveable { mutableStateOf(false) }
	// Fold the menu away if it is no longer available (e.g. state changed while it was open).
	if (!hasMenu && expanded) {
		expanded = false
	}
	val chevronRotation by animateFloatAsState(
		targetValue = if (expanded) 180f else 0f,
		animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
		label = "fabChevron",
	)

	val container = if (enabled) accent else accent.copy(alpha = 0.4f)
	val baseContent = if (accent.luminanceIsLight()) Color.Black else Color.White
	val onColor = if (enabled) baseContent else baseContent.copy(alpha = 0.7f)

	Surface(
		shape = RoundedCornerShape(24.dp),
		color = container,
		shadowElevation = 6.dp,
	) {
		// The FAB is one continuous surface: tapping the chevron grows it downward to reveal the quick
		// actions below the read button, animated by animateContentSize, and the chevron flips over.
		// animateContentSize lives on the wrapper so the IntrinsicSize width resolves cleanly inside.
		Box(modifier = Modifier.animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))) {
		Column(
			modifier = Modifier.width(IntrinsicSize.Max),
		) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.height(56.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Row(
					modifier = Modifier
						.clickable(enabled = enabled) {
							expanded = false
							actions.onReadClick()
						}
						.fillMaxHeight()
						.weight(1f)
						.padding(start = 22.dp, end = if (hasMenu) 14.dp else 24.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(10.dp),
				) {
					Icon(
						painter = painterResource(R.drawable.ic_play),
						contentDescription = null,
						tint = onColor,
						modifier = Modifier.size(22.dp),
					)
					Text(
						text = label,
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.SemiBold,
						color = onColor,
						maxLines = 1,
					)
				}
				if (hasMenu) {
					Row(
						modifier = Modifier
							.clickable { expanded = !expanded }
							.fillMaxHeight()
							.padding(end = 16.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Box(
							modifier = Modifier
								.width(1.dp)
								.height(24.dp)
								.background(onColor.copy(alpha = 0.3f)),
						)
						Spacer(Modifier.width(12.dp))
						Icon(
							painter = painterResource(R.drawable.ic_expand_more),
							contentDescription = stringResource(R.string.show_menu),
							tint = onColor,
							modifier = Modifier
								.size(22.dp)
								.rotate(chevronRotation),
						)
					}
				}
			}
			if (expanded) {
				Box(
					modifier = Modifier
						.padding(horizontal = 16.dp)
						.fillMaxWidth()
						.height(1.dp)
						.background(onColor.copy(alpha = 0.22f)),
				)
				if (canIncognito) {
					FabMenuRow(iconRes = R.drawable.ic_incognito, label = stringResource(R.string.incognito_mode), color = onColor) {
						expanded = false
						actions.onIncognitoClick()
					}
				}
				if (canForget) {
					FabMenuRow(iconRes = R.drawable.ic_delete, label = stringResource(R.string.remove_from_history), color = onColor) {
						expanded = false
						actions.onForgetHistoryClick()
					}
				}
			}
		}
		}
	}
}

@Composable
private fun FabMenuRow(
	@DrawableRes iconRes: Int,
	label: String,
	color: Color,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(horizontal = 20.dp, vertical = 14.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(14.dp),
	) {
		Icon(
			painter = painterResource(iconRes),
			contentDescription = null,
			tint = color,
			modifier = Modifier.size(20.dp),
		)
		Text(
			text = label,
			style = MaterialTheme.typography.bodyLarge,
			color = color,
			maxLines = 1,
		)
	}
}

@Composable
private fun ExpressiveBackdrop(
	url: String,
	manga: Manga?,
	imageLoader: ImageLoader,
	surface: Color,
	blurAmount: Int,
) {
	val ctx = LocalContext.current
	val request = remember(url, manga?.source) {
		ImageRequest.Builder(ctx)
			.data(url)
			.crossfade(true)
			.mangaSourceExtra(manga?.source)
			.build()
	}
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(480.dp),
	) {
		val blurDp = when (blurAmount) {
			0 -> 0.dp
			1 -> 20.dp
			else -> 40.dp
		}
		val blurMod = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurDp > 0.dp) {
			Modifier.blur(blurDp)
		} else {
			Modifier
		}
		AsyncImage(
			model = request,
			imageLoader = imageLoader,
			contentDescription = null,
			contentScale = ContentScale.Crop,
			modifier = Modifier
				.fillMaxSize()
				.then(blurMod),
		)
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(
					Brush.verticalGradient(
						0f to surface.copy(alpha = 0.30f),
						0.4f to surface.copy(alpha = 0.55f),
						0.78f to surface.copy(alpha = 0.94f),
						1f to surface,
					),
				),
		)
	}
}

@Composable
private fun HeroSection(
	centered: Boolean,
	manga: Manga,
	details: MangaDetails?,
	sourceTitle: String?,
	accent: Color,
	imageLoader: ImageLoader,
	coverUrl: String?,
	favouriteLabel: String,
	isFavourite: Boolean,
	onFavouriteClick: () -> Unit,
	actions: DetailsExpressiveActions,
) {
	val nsfwLabel = when (manga.contentRating) {
		ContentRating.SUGGESTIVE -> "16+"
		ContentRating.ADULT -> "18+"
		else -> null
	}
	if (centered) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = SCREEN_PADDING),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			CoverCard(
				manga = manga,
				coverUrl = coverUrl,
				imageLoader = imageLoader,
				modifier = Modifier
					.width(COVER_WIDTH)
					.height(COVER_HEIGHT),
				corner = 24.dp,
				nsfwLabel = null,
				forceRefresh = details?.isLoaded == true,
				actions = actions,
			)
			Spacer(Modifier.height(20.dp))
			HeroTexts(centered = true, manga = manga, accent = accent, actions = actions)
			Spacer(Modifier.height(16.dp))
			StatPills(
				centered = true,
				showContentRating = true,
				manga = manga,
				sourceTitle = sourceTitle,
				accent = accent,
				imageLoader = imageLoader,
				onSourceClick = { actions.onSourceClick(manga) },
			)
		}
	} else {
		BoxWithConstraints(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = SCREEN_PADDING)
		) {
			val density = LocalDensity.current
			val compactCoverWidthPx = with(density) { COMPACT_COVER_WIDTH.roundToPx() }
			val compactCoverHeightPx = with(density) { COMPACT_COVER_HEIGHT.roundToPx() }
			val spacingPx = with(density) { 16.dp.roundToPx() }
			val spacerHeightPx = with(density) { 14.dp.roundToPx() }

			val measurer = rememberTextMeasurer()
			val authors = manga.authors.filter { it.isNotBlank() }
			val authorText = authors.joinToString(", ")

			val titleStyle = MaterialTheme.typography.headlineSmall
			val authorStyle = MaterialTheme.typography.labelLarge

			val infoWidth = maxWidth - COMPACT_COVER_WIDTH - 16.dp
			val infoWidthPx = with(density) { infoWidth.roundToPx() }

			val titleLayoutResult = measurer.measure(
				text = manga.title,
				style = titleStyle,
				constraints = Constraints(maxWidth = infoWidthPx),
				maxLines = 4,
			)
			val authorLayoutResult = if (authorText.isNotEmpty()) {
				measurer.measure(
					text = authorText,
					style = authorStyle,
					constraints = Constraints(maxWidth = infoWidthPx),
					maxLines = 2,
				)
			} else {
				null
			}

			val titleFitsInOneLine = titleLayoutResult.lineCount <= 1
			val authorFitsInOneLine = authorLayoutResult == null || authorLayoutResult.lineCount <= 1
			val bothFitInOneLine = titleFitsInOneLine && authorFitsInOneLine

			Layout(
				content = {
					CoverCard(
						manga = manga,
						coverUrl = coverUrl,
						imageLoader = imageLoader,
						modifier = Modifier.width(COMPACT_COVER_WIDTH),
						corner = 20.dp,
						nsfwLabel = nsfwLabel,
						forceRefresh = details?.isLoaded == true,
						actions = actions,
					)
					Column {
						HeroTexts(centered = false, manga = manga, accent = accent, actions = actions)
						Spacer(Modifier.height(12.dp))
						StatPills(
							centered = false,
							showContentRating = false,
							manga = manga,
							sourceTitle = sourceTitle,
							accent = accent,
							imageLoader = imageLoader,
							onSourceClick = { actions.onSourceClick(manga) },
						)
					}
					FavouriteButton(
						label = favouriteLabel,
						isFavourite = isFavourite,
						accent = accent,
						onClick = onFavouriteClick,
						horizontalPadding = 0.dp,
					)
				},
			) { measurables, constraints ->
				val remainingWidth = constraints.maxWidth - compactCoverWidthPx - spacingPx

				val upperPlaceable = measurables[1].measure(
					Constraints.fixedWidth(remainingWidth)
				)

				val buttonPlaceable = measurables[2].measure(
					Constraints.fixedWidth(remainingWidth)
				)

				val naturalInfoHeight = upperPlaceable.height + spacerHeightPx + buttonPlaceable.height
				val coverHeight = if (bothFitInOneLine) {
					compactCoverHeightPx
				} else {
					maxOf(compactCoverHeightPx, naturalInfoHeight)
				}

				val coverPlaceable = measurables[0].measure(
					Constraints.fixed(compactCoverWidthPx, coverHeight)
				)

				layout(constraints.maxWidth, coverHeight) {
					coverPlaceable.placeRelative(0, 0)
					upperPlaceable.placeRelative(compactCoverWidthPx + spacingPx, 0)
					buttonPlaceable.placeRelative(
						compactCoverWidthPx + spacingPx,
						coverHeight - buttonPlaceable.height
					)
				}
			}
		}
	}
}

@Composable
private fun CoverCard(
	manga: Manga,
	coverUrl: String?,
	imageLoader: ImageLoader,
	modifier: Modifier,
	corner: Dp,
	nsfwLabel: String?,
	forceRefresh: Boolean,
	actions: DetailsExpressiveActions,
) {
	val ctx = LocalContext.current
	Surface(
		shape = RoundedCornerShape(corner),
		color = MaterialTheme.colorScheme.surfaceVariant,
		tonalElevation = 4.dp,
		shadowElevation = 16.dp,
		modifier = modifier,
	) {
		// The visible cover always loads from cache via the stable per-manga key, so it appears
		// instantly with no "greyed out" placeholder — exactly like a local cover.
		val coverRequest = remember(coverUrl, manga.id, manga.source) {
			ImageRequest.Builder(ctx)
				.data(coverUrl)
				.crossfade(true)
				.mangaSourceExtra(manga.source)
				.stableMangaCoverKey(manga, coverUrl)
				.build()
		}
		// Once the entry has been opened and its details loaded, silently re-check the source for a
		// new cover in the background and overwrite the cached copy. The visible cover is untouched
		// (no flicker); a genuinely changed cover then shows up on the lists and on the next open.
		if (forceRefresh && isRemoteCoverUrl(coverUrl)) {
			LaunchedEffect(manga.id, coverUrl) {
				imageLoader.enqueue(
					ImageRequest.Builder(ctx)
						.data(coverUrl)
						.mangaSourceExtra(manga.source)
						.diskCacheKey(mangaCoverDiskCacheKey(manga.id))
						.diskCachePolicy(CachePolicy.WRITE_ONLY)
						.memoryCachePolicy(CachePolicy.DISABLED)
						.networkCachePolicy(CachePolicy.ENABLED)
						.build(),
				)
			}
		}
		Box(modifier = Modifier.fillMaxSize()) {
			AsyncImage(
				model = coverRequest,
				imageLoader = imageLoader,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier
					.fillMaxSize()
					.clickable { actions.onCoverClick(manga) },
			)
			if (nsfwLabel != null) {
				Surface(
					shape = RoundedCornerShape(8.dp),
					color = Color.Black.copy(alpha = 0.6f),
					modifier = Modifier
						.align(Alignment.BottomEnd)
						.padding(6.dp),
				) {
					Text(
						text = nsfwLabel,
						style = MaterialTheme.typography.labelSmall,
						fontWeight = FontWeight.Bold,
						color = Color.White,
						modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
					)
				}
			}
		}
	}
}

@Composable
private fun HeroTexts(
	centered: Boolean,
	manga: Manga,
	accent: Color,
	actions: DetailsExpressiveActions,
) {
	val align = if (centered) TextAlign.Center else TextAlign.Start
	Text(
		text = manga.title,
		style = if (centered) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
		fontWeight = FontWeight.Bold,
		color = MaterialTheme.colorScheme.onSurface,
		textAlign = align,
		maxLines = 4,
		overflow = TextOverflow.Ellipsis,
		modifier = Modifier.clickable { actions.onTitleClick(manga.title) },
	)
	val altTitle = manga.altTitles.firstOrNull()?.takeIf { it.isNotBlank() }
	if (altTitle != null) {
		Spacer(Modifier.height(6.dp))
		Text(
			text = altTitle,
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = align,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
	}
	val authors = manga.authors.filter { it.isNotBlank() }
	if (authors.isNotEmpty()) {
		Spacer(Modifier.height(8.dp))
		Text(
			text = authors.joinToString(", "),
			style = MaterialTheme.typography.labelLarge,
			color = accent,
			fontWeight = FontWeight.Medium,
			textAlign = align,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier.clickable { actions.onAuthorClick(authors.first()) },
		)
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatPills(
	centered: Boolean,
	showContentRating: Boolean,
	manga: Manga,
	sourceTitle: String?,
	accent: Color,
	imageLoader: ImageLoader,
	onSourceClick: () -> Unit,
) {
	val ctx = LocalContext.current
	FlowRow(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = if (centered) {
			Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
		} else {
			Arrangement.spacedBy(8.dp)
		},
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		// Status and the source/extension are always kept together on a single horizontal line; the
		// source name auto-shrinks to fit rather than wrapping to a second line.
		val hasContentRating = showContentRating && (manga.contentRating == ContentRating.SUGGESTIVE || manga.contentRating == ContentRating.ADULT)
		if (hasContentRating || manga.state != null || !manga.isLocal) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = if (centered) {
					Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
				} else {
					Arrangement.spacedBy(8.dp)
				},
				verticalAlignment = Alignment.CenterVertically,
			) {
				if (hasContentRating) {
					when (manga.contentRating) {
						ContentRating.SUGGESTIVE -> Pill(text = "16+", accent = accent)
						ContentRating.ADULT -> Pill(text = "18+", accent = accent)
						else -> Unit
					}
				}
				manga.state?.let { state ->
					Pill(text = stringResource(state.titleResId), accent = accent)
				}
				if (!manga.isLocal) {
					val srcText = sourceTitle?.takeUnless { it.isBlank() } ?: manga.source.getTitle(ctx)
					val faviconRequest = remember(manga.source) {
						ImageRequest.Builder(ctx)
							.data(manga.source.faviconUri())
							.mangaSourceExtra(manga.source)
							.crossfade(true)
							.build()
					}
					SourcePill(
						text = srcText,
						faviconRequest = faviconRequest,
						imageLoader = imageLoader,
						// In the centered hero the row spans the full width, so a fixed-size centered pill
						// looks right; in the compact layout the row is narrow, so let the name scale down.
						autoResize = !centered,
						onClick = onSourceClick,
						modifier = if (centered) Modifier else Modifier.weight(1f, fill = false),
					)
				}
			}
		}
	}
}

@Composable
private fun SourcePill(
	text: String,
	faviconRequest: ImageRequest,
	imageLoader: ImageLoader,
	autoResize: Boolean,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Surface(
		shape = RoundedCornerShape(50),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		modifier = modifier.clickable(onClick = onClick),
	) {
		Row(
			modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			AsyncImage(
				model = faviconRequest,
				imageLoader = imageLoader,
				contentDescription = null,
				error = painterResource(R.drawable.ic_manga_source),
				fallback = painterResource(R.drawable.ic_manga_source),
				modifier = Modifier
					.size(16.dp)
					.clip(RoundedCornerShape(4.dp)),
			)
			val labelStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
			val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
			if (autoResize) {
				AutoResizeText(
					text = text,
					color = contentColor,
					baseStyle = labelStyle,
					minTextSize = 9.sp,
					modifier = Modifier.weight(1f, fill = false),
				)
			} else {
				Text(
					text = text,
					style = labelStyle,
					color = contentColor,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

/**
 * Single-line text that shrinks its font size (down to [minTextSize]) until it fits the available
 * width, instead of wrapping or being clipped. Used so the source/extension name always fits on the
 * status row.
 */
@Composable
private fun AutoResizeText(
	text: String,
	color: Color,
	baseStyle: TextStyle,
	minTextSize: TextUnit,
	modifier: Modifier = Modifier,
) {
	val measurer = rememberTextMeasurer()
	val density = LocalDensity.current
	BoxWithConstraints(modifier) {
		val maxWidthPx = with(density) { maxWidth.toPx() }
		val fontSize = remember(text, maxWidthPx, baseStyle) {
			var size = baseStyle.fontSize
			if (maxWidthPx > 0f && size.isSp) {
				while (size.value > minTextSize.value) {
					val width = measurer.measure(
						text = text,
						style = baseStyle.copy(fontSize = size),
						maxLines = 1,
						softWrap = false,
					).size.width
					if (width <= maxWidthPx) break
					size = (size.value - 1f).sp
				}
			}
			size
		}
		Text(
			text = text,
			color = color,
			style = baseStyle.copy(fontSize = fontSize),
			maxLines = 1,
			softWrap = false,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

@Composable
private fun Pill(
	text: String,
	accent: Color,
	highlighted: Boolean = false,
	onClick: (() -> Unit)? = null,
	leading: (@Composable () -> Unit)? = null,
) {
	val container = if (highlighted) accent.copy(alpha = 0.20f) else MaterialTheme.colorScheme.surfaceContainerHigh
	val content = if (highlighted) accent else MaterialTheme.colorScheme.onSurfaceVariant
	Surface(
		shape = RoundedCornerShape(50),
		color = container,
		modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
	) {
		Row(
			modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			leading?.invoke()
			Text(
				text = text,
				style = MaterialTheme.typography.labelMedium,
				fontWeight = FontWeight.Medium,
				color = content,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

@Composable
private fun FavouriteButton(
	label: String,
	isFavourite: Boolean,
	accent: Color,
	onClick: () -> Unit,
	horizontalPadding: Dp = SCREEN_PADDING,
) {
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(20.dp),
		color = if (isFavourite) accent else accent.copy(alpha = 0.16f),
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = horizontalPadding)
			.height(52.dp),
	) {
		val contentColor = if (isFavourite) {
			if (accent.luminanceIsLight()) Color.Black else Color.White
		} else {
			accent
		}
		Row(
			modifier = Modifier.fillMaxSize(),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.Center,
		) {
			Icon(
				painter = painterResource(if (isFavourite) R.drawable.ic_heart else R.drawable.ic_heart_outline),
				contentDescription = null,
				tint = contentColor,
				modifier = Modifier.size(20.dp),
			)
			Spacer(Modifier.width(8.dp))
			Text(
				text = label,
				style = MaterialTheme.typography.titleSmall,
				fontWeight = FontWeight.SemiBold,
				color = contentColor,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

@Composable
private fun ProgressCard(historyInfo: HistoryInfo, isLoading: Boolean, accent: Color) {
	val ctx = LocalContext.current
	val res = ctx.resources
	val chaptersText = when {
		isLoading -> stringResource(R.string.loading_)
		historyInfo.currentChapter >= 0 -> withTime(
			stringResource(R.string.chapter_d_of_d, historyInfo.currentChapter + 1, historyInfo.totalChapters),
			historyInfo, res,
		)
		historyInfo.totalChapters == 0 -> stringResource(R.string.no_chapters)
		historyInfo.totalChapters == -1 -> stringResource(R.string.error_occurred)
		else -> withTime(
			pluralStringResource(R.plurals.chapters, historyInfo.totalChapters, historyInfo.totalChapters),
			historyInfo, res,
		)
	}
	val hasHistory = historyInfo.history != null
	val percent = historyInfo.percent.coerceIn(0f, 1f)
	val showProgress = hasHistory && percent > 0f
	val displayPercent = if (ReadingProgress.isCompleted(historyInfo.percent)) 100 else (percent * 100f).toInt()

	SectionCard {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Icon(
				painter = painterResource(R.drawable.ic_read),
				contentDescription = null,
				tint = accent,
				modifier = Modifier.size(22.dp),
			)
			Spacer(Modifier.width(12.dp))
			Text(
				text = chaptersText,
				style = MaterialTheme.typography.titleSmall,
				color = MaterialTheme.colorScheme.onSurface,
				modifier = Modifier.weight(1f),
			)
			if (showProgress) {
				Text(
					text = stringResource(R.string.percent_string_pattern, displayPercent.toString()),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.Bold,
					color = accent,
				)
			}
		}
		if (showProgress) {
			Spacer(Modifier.height(14.dp))
			WavyProgressBar(
				progress = percent,
				color = accent,
				trackColor = accent.copy(alpha = 0.22f),
				modifier = Modifier
					.fillMaxWidth()
					.height(14.dp),
			)
		}
	}
}

/**
 * A Material 3 Expressive style "squiggly" progress indicator: the played portion is an animated
 * sine wave, the remaining portion a rounded straight track. (The stock wavy indicator only ships
 * in material3 1.4+, so this is drawn by hand to get the same look on the current version.)
 */
@Composable
private fun WavyProgressBar(progress: Float, color: Color, trackColor: Color, modifier: Modifier) {
	val transition = rememberInfiniteTransition(label = "wave")
	val phase by transition.animateFloat(
		initialValue = 0f,
		targetValue = (2f * PI).toFloat(),
		animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
		label = "phase",
	)
	val animatedProgress by animateFloatAsState(
		targetValue = progress.coerceIn(0f, 1f),
		animationSpec = tween(600),
		label = "progress",
	)
	Canvas(modifier = modifier) {
		val midY = size.height / 2f
		val stroke = 4.5.dp.toPx()
		val activeW = size.width * animatedProgress
		val amplitude = (size.height / 2f - stroke / 2f) * 0.9f
		// Match the Material wavy LinearProgressIndicator used by the download list (its default
		// active-wave wavelength, m3_comp_progress_indicator_linear_active_indicator_wave_wavelength).
		val waveLength = 40.dp.toPx()
		if (animatedProgress < 1f) {
			drawLine(
				color = trackColor,
				start = Offset(activeW, midY),
				end = Offset(size.width, midY),
				strokeWidth = stroke,
				cap = StrokeCap.Round,
			)
		}
		if (activeW > 0f) {
			val path = Path().apply {
				moveTo(0f, midY + amplitude * sin(phase))
				var x = 0f
				while (x <= activeW) {
					lineTo(x, midY + amplitude * sin((x / waveLength) * 2f * PI.toFloat() + phase))
					x += 3f
				}
			}
			drawPath(
				path = path,
				color = color,
				style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
			)
		}
	}
}

@Composable
private fun DescriptionCard(
	description: CharSequence?,
	manga: Manga,
	details: MangaDetails?,
	accent: Color,
) {
	val text = description?.toString()?.trim().orEmpty()
	var expanded by rememberSaveable { mutableStateOf(false) }
	var hasOverflow by remember { mutableStateOf(false) }
	val cardColor = MaterialTheme.colorScheme.surfaceContainerHigh
	SectionCard {
		val locale = details?.getLocale()
		Row(
			modifier = Modifier.fillMaxWidth(),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = stringResource(R.string.description),
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.SemiBold,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Spacer(modifier = Modifier.weight(1f))
			locale?.let {
				Pill(
					text = it.getDisplayLanguage(it).replaceFirstChar { ch -> ch.titlecase(it) },
					accent = accent,
					highlighted = true,
				) {
					Icon(
						painter = painterResource(R.drawable.ic_language),
						contentDescription = null,
						tint = accent,
						modifier = Modifier.size(15.dp),
					)
				}
				Spacer(modifier = Modifier.width(8.dp))
			}
			if (manga.hasRating) {
				Pill(text = String.format(Locale.ROOT, "%.1f", manga.rating * 5f), accent = accent, highlighted = true) {
					Icon(
						painter = painterResource(R.drawable.ic_star_small),
						contentDescription = null,
						tint = accent,
						modifier = Modifier.size(15.dp),
					)
				}
			}
		}
		Spacer(Modifier.height(10.dp))
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.animateContentSize()
				.clickable(
					enabled = text.isNotEmpty(),
					indication = null,
					interactionSource = remember { MutableInteractionSource() },
				) { expanded = !expanded },
		) {
			Text(
				text = text.ifEmpty { stringResource(R.string.no_description) },
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = if (expanded) Int.MAX_VALUE else 5,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.fillMaxWidth(),
				onTextLayout = { hasOverflow = it.hasVisualOverflow },
			)
			if (!expanded && hasOverflow) {
				Box(
					modifier = Modifier
						.matchParentSize()
						.background(
							Brush.verticalGradient(
								0.5f to Color.Transparent,
								1.0f to cardColor.copy(alpha = 0.82f),
							)
						),
				)
			}
		}
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsSection(tags: List<ChipsView.ChipModel>, accent: Color, onTagClick: (MangaTag) -> Unit) {
	if (tags.isEmpty()) return
	var expanded by rememberSaveable { mutableStateOf(false) }
	val measurer = rememberTextMeasurer()
	val density = LocalDensity.current
	val chipStyle = MaterialTheme.typography.labelLarge

	BoxWithConstraints(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = SCREEN_PADDING, vertical = 7.dp),
	) {
		// Work out up-front whether the genres actually wrap past the collapsed row budget — only then
		// is an expand/collapse toggle meaningful. Relying on FlowRow's own indicator alone reserves a
		// slot on the last row and pops in a dead toggle for tag sets that already fit in <= 3 rows.
		val needsToggle = remember(tags, maxWidth, chipStyle) {
			with(density) {
				val available = maxWidth.toPx()
				val chipHorizontalPadding = 28.dp.toPx() // 14.dp on each side, mirrors the chip below
				val gap = 8.dp.toPx()
				var rows = 1
				var rowWidth = 0f
				for (tag in tags) {
					val chipWidth = measurer.measure(tag.title?.toString().orEmpty(), chipStyle).size.width + chipHorizontalPadding
					rowWidth = when {
						rowWidth == 0f -> chipWidth
						rowWidth + gap + chipWidth <= available -> rowWidth + gap + chipWidth
						else -> {
							rows++
							chipWidth
						}
					}
				}
				rows > TAGS_COLLAPSED_ROWS
			}
		}

		FlowRow(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
			maxLines = if (needsToggle && !expanded) TAGS_COLLAPSED_ROWS else Int.MAX_VALUE,
			overflow = if (needsToggle) {
				FlowRowOverflow.expandOrCollapseIndicator(
					expandIndicator = {
						TagToggleChip(text = stringResource(R.string.more), accent = accent, expanded = false) { expanded = true }
					},
					collapseIndicator = {
						TagToggleChip(text = stringResource(R.string.collapse), accent = accent, expanded = true) { expanded = false }
					},
				)
			} else {
				FlowRowOverflow.Visible
			},
		) {
			// Tint the genre chips from the page accent (themed primary, or the cover colour when
			// "colors from cover" is on) so they follow the active theme instead of the baseline
			// secondary container, which renders purple under the default colour scheme.
			tags.forEach { tag ->
				val mangaTag = tag.data as? MangaTag
				val warningColor = if (tag.tint != 0) colorResource(tag.tint) else null
				Surface(
					shape = RoundedCornerShape(15.dp),
					color = (warningColor ?: accent).copy(alpha = 0.16f),
					onClick = { if (mangaTag != null) onTagClick(mangaTag) },
				) {
					Text(
						text = tag.title?.toString().orEmpty(),
						style = MaterialTheme.typography.labelLarge,
						color = warningColor ?: accent,
						modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
					)
				}
			}
		}
	}
}

@Composable
private fun TagToggleChip(text: String, accent: Color, expanded: Boolean, onClick: () -> Unit) {
	// Deliberately distinct from the filled tonal genre chips: an outlined chip with a chevron, so it
	// reads as an action (expand / collapse) rather than just another genre tag.
	Surface(
		shape = RoundedCornerShape(15.dp),
		color = Color.Transparent,
		border = BorderStroke(1.dp, accent.copy(alpha = 0.6f)),
		onClick = onClick,
	) {
		Row(
			modifier = Modifier.padding(start = 14.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Text(
				text = text,
				style = MaterialTheme.typography.labelLarge,
				fontWeight = FontWeight.SemiBold,
				color = accent,
			)
			Icon(
				painter = painterResource(R.drawable.ic_expand_more),
				contentDescription = null,
				tint = accent,
				modifier = Modifier
					.size(18.dp)
					.rotate(if (expanded) 180f else 0f),
			)
		}
	}
}

@Composable
private fun ScrobblingSection(
	items: List<ScrobblingInfo>,
	imageLoader: ImageLoader,
	accent: Color,
	onMore: () -> Unit,
	onCardClick: (Int) -> Unit,
) {
	SectionHeader(title = stringResource(R.string.tracking), action = stringResource(R.string.manage), accent = accent, onAction = onMore)
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = SCREEN_PADDING),
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		items.forEachIndexed { index, info ->
			Surface(
				shape = RoundedCornerShape(20.dp),
				color = MaterialTheme.colorScheme.surfaceContainerHigh,
				onClick = { onCardClick(index) },
				modifier = Modifier.fillMaxWidth(),
			) {
				Row(
					modifier = Modifier
						.padding(16.dp)
						.height(IntrinsicSize.Min),
					verticalAlignment = Alignment.Top,
				) {
					AsyncImage(
						model = info.coverUrl,
						imageLoader = imageLoader,
						contentDescription = null,
						contentScale = ContentScale.Crop,
						modifier = Modifier
							.size(80.dp, 116.dp)
							.clip(RoundedCornerShape(14.dp)),
					)
					Spacer(Modifier.width(16.dp))
					Column(
						modifier = Modifier
							.weight(1f)
							.fillMaxHeight(),
						verticalArrangement = Arrangement.SpaceBetween,
					) {
						Column {
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.SpaceBetween,
								verticalAlignment = Alignment.CenterVertically,
							) {
								Row(
									verticalAlignment = Alignment.CenterVertically,
									horizontalArrangement = Arrangement.spacedBy(6.dp),
								) {
									Icon(
										// SHIKIMORI's iconResId is a <bitmap> drawable, which Compose's
										// painterResource cannot load (it accepts only vectors and rasters);
										// fall back to the raw raster to avoid an IllegalArgumentException.
										painter = painterResource(
											if (info.scrobbler == ScrobblerService.SHIKIMORI) {
												R.drawable.ic_shikimori_raw
											} else {
												info.scrobbler.iconResId
											},
										),
										contentDescription = null,
										tint = Color.Unspecified,
										modifier = Modifier.size(16.dp),
									)
									Text(
										text = stringResource(info.scrobbler.titleResId),
										style = MaterialTheme.typography.labelMedium,
										color = MaterialTheme.colorScheme.onSurfaceVariant,
									)
								}
								info.status?.let { status ->
									Text(
										text = stringResource(status.labelResId),
										style = MaterialTheme.typography.labelMedium,
										color = accent,
									)
								}
							}
							Spacer(Modifier.height(10.dp))
							Text(
								text = info.title,
								style = MaterialTheme.typography.bodyLarge,
								color = MaterialTheme.colorScheme.onSurface,
								maxLines = 2,
								overflow = TextOverflow.Ellipsis,
							)
						}
						if (info.rating > 0f) {
							Row(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.End,
								verticalAlignment = Alignment.CenterVertically,
							) {
								Icon(
									painter = painterResource(R.drawable.ic_star_small),
									contentDescription = null,
									tint = accent,
									modifier = Modifier.size(20.dp),
								)
								Spacer(Modifier.width(4.dp))
								Text(
									text = "${"%.1f".format(info.rating * 5)} / 5",
									style = MaterialTheme.typography.titleSmall,
									color = MaterialTheme.colorScheme.onSurface,
								)
							}
						}
					}
				}
			}
		}
	}
	Spacer(Modifier.height(8.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelatedSection(
	items: List<MangaListModel>,
	imageLoader: ImageLoader,
	accent: Color,
	onMore: () -> Unit,
	onItemClick: (MangaListModel) -> Unit,
) {
	SectionHeader(title = stringResource(R.string.related_manga), action = stringResource(R.string.show_all), accent = accent, onAction = onMore)
	val carouselState = rememberCarouselState { items.size }
	HorizontalMultiBrowseCarousel(
		state = carouselState,
		preferredItemWidth = 150.dp,
		itemSpacing = 10.dp,
		// The carousel's default single-advance fling only moves one item per swipe — that's what felt
		// sticky and stopped abruptly. The multi-browse fling lets a flick coast across several covers
		// with momentum and settle on a clean item edge, while keeping the M3 multi-browse mask/peek look.
		flingBehavior = CarouselDefaults.multiBrowseFlingBehavior(state = carouselState),
		contentPadding = PaddingValues(horizontal = SCREEN_PADDING),
		modifier = Modifier
			.fillMaxWidth()
			.height(232.dp),
	) { i ->
		// The M3 carousel can momentarily request an index from a stale (larger) item count while
		// the related list is still settling after an async load — guard against that to avoid an
		// IndexOutOfBounds crash; the slot fills in on the next recomposition.
		val item = items.getOrNull(i) ?: return@HorizontalMultiBrowseCarousel
		val context = LocalContext.current
		Column(
			modifier = Modifier.clickable { onItemClick(item) },
		) {
			AsyncImage(
				model = remember(item.coverUrl, item.source) {
					ImageRequest.Builder(context)
						.data(item.coverUrl)
						.crossfade(true)
						.mangaSourceExtra(item.source)
						.build()
				},
				imageLoader = imageLoader,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier
					.height(200.dp)
					.fillMaxWidth()
					.maskClip(RoundedCornerShape(20.dp)),
			)
			Spacer(Modifier.height(8.dp))
			Text(
				text = item.title,
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.padding(start = 8.dp, end = 4.dp),
			)
		}
	}
}

@Composable
private fun LocalSizeRow(size: Long, manga: Manga, onClick: (Manga) -> Unit) {
	val ctx = LocalContext.current
	SectionCard(onClick = { onClick(manga) }) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Icon(
				painter = painterResource(R.drawable.ic_storage_checked),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(20.dp),
			)
			Spacer(Modifier.width(12.dp))
			Text(
				text = FileSize.BYTES.format(ctx, size),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurface,
			)
		}
	}
}

@Composable
private fun SectionHeader(title: String, action: String, accent: Color, onAction: () -> Unit) {
	Spacer(Modifier.height(8.dp))
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = SCREEN_PADDING),
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
			shape = RoundedCornerShape(50),
			color = accent.copy(alpha = 0.14f),
			onClick = onAction,
		) {
			Text(
				text = action,
				style = MaterialTheme.typography.labelMedium,
				fontWeight = FontWeight.Medium,
				color = accent,
				modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
			)
		}
	}
	Spacer(Modifier.height(12.dp))
}

@Composable
private fun SectionCard(
	onClick: (() -> Unit)? = null,
	content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
	val base = Modifier
		.fillMaxWidth()
		.padding(horizontal = SCREEN_PADDING, vertical = 8.dp)
	Surface(
		shape = RoundedCornerShape(CARD_CORNER),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
	) {
		Column(modifier = Modifier.padding(20.dp), content = content)
	}
}

@Composable
private fun LoadingHero() {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(240.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = stringResource(R.string.loading_),
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

private fun Color.luminanceIsLight(): Boolean =
	(0.299f * red + 0.587f * green + 0.114f * blue) > 0.5f

private fun withTime(base: String, info: HistoryInfo, res: android.content.res.Resources): String {
	val time = info.estimatedTime?.formatShort(res) ?: return base
	return res.getString(R.string.chapters_time_pattern, base, time)
}
