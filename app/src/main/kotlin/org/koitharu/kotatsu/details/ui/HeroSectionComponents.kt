package org.koitharu.kotatsu.details.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
import org.koitharu.kotatsu.core.parser.favicon.faviconUri
import org.koitharu.kotatsu.core.util.ext.isRemoteCoverUrl
import org.koitharu.kotatsu.core.util.ext.mangaCoverDiskCacheKey
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.stableMangaCoverKey
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga

private val COVER_WIDTH = 158.dp
private val COVER_HEIGHT = 236.dp
private val COMPACT_COVER_WIDTH = 120.dp
private val COMPACT_COVER_HEIGHT = 178.dp

@Composable
internal fun HeroSection(
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
				sourceManga = details?.sourceManga,
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
						sourceManga = details?.sourceManga,
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
internal fun CoverCard(
	manga: Manga,
	/** The pristine manga, used to tell a source cover apart from a user override. */
	sourceManga: Manga?,
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
		// `manga` already has the user's override applied, so the shared `cover:$id` disk slot must be
		// decided against the pristine manga - otherwise a custom cover is stored under the source
		// cover's key and keeps showing in lists after the override is reverted.
		val keyManga = sourceManga ?: manga
		val isSourceCover = coverUrl == keyManga.coverUrl || coverUrl == keyManga.largeCoverUrl
		val coverRequest = remember(coverUrl, manga.id, manga.source, keyManga) {
			ImageRequest.Builder(ctx)
				.data(coverUrl)
				.crossfade(true)
				.mangaSourceExtra(manga.source)
				.stableMangaCoverKey(keyManga, coverUrl)
				.build()
		}
		// Refresh the source cover even while an override hides it, so reverting shows the current
		// one straight away instead of a stale copy.
		val refreshUrl = if (isSourceCover) coverUrl else keyManga.coverUrl
		if (forceRefresh && isRemoteCoverUrl(refreshUrl)) {
			LaunchedEffect(manga.id, refreshUrl) {
				imageLoader.enqueue(
					ImageRequest.Builder(ctx)
						.data(refreshUrl)
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
internal fun HeroTexts(
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
internal fun StatPills(
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
internal fun SourcePill(
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

@Composable
internal fun AutoResizeText(
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
internal fun FavouriteButton(
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
			modifier = Modifier
				.fillMaxSize()
				.padding(horizontal = 16.dp),
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
