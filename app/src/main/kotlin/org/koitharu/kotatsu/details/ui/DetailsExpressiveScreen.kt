@file:Suppress("DEPRECATION")

package org.koitharu.kotatsu.details.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.DetailsUiMode
import org.koitharu.kotatsu.core.ui.util.StatusBarScrim
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.ui.model.HistoryInfo
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo

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
						.padding(end = SCREEN_PADDING, bottom = bottomContentPadding + 16.dp)
						.dockGlow(scheme.surface),
				)

				// Status bar protection, mirroring the View-side scrim in MainActivity. Sits under the
				// activity's toolbar (that lives in the XML AppBarLayout above this ComposeView).
				if (topInset > 0.dp) {
					val stops = StatusBarScrim.alphas
					Box(
						modifier = Modifier
							.align(Alignment.TopCenter)
							.fillMaxWidth()
							.height(topInset * StatusBarScrim.HEIGHT_FACTOR)
							.background(
								Brush.verticalGradient(
									*stops.mapIndexed { i, a ->
										i / (stops.lastIndex.toFloat()) to scheme.surface.copy(alpha = a / 255f)
									}.toTypedArray(),
								),
							),
					)
				}
		}
	}
}

/**
 * Soft radial halo behind the action dock so the pill and FAB keep contrast over scrolling content.
 * Drawn from the dock's own bounds and deliberately spills past them — a round falloff has no
 * visible edge, unlike a rectangular scrim.
 */
private fun Modifier.dockGlow(surface: Color) = drawBehind {
	val rx = size.width * 0.62f
	val ry = size.height * 0.60f
	val brush = Brush.radialGradient(
		0f to surface.copy(alpha = 0.92f),
		0.40f to surface.copy(alpha = 0.74f),
		0.72f to surface.copy(alpha = 0.32f),
		1f to Color.Transparent,
		center = center,
		radius = rx,
	)
	// Squash the circle into an ellipse so the halo hugs the (wide, short) dock instead of ballooning.
	scale(scaleX = 1f, scaleY = ry / rx, pivot = center) {
		drawCircle(brush = brush, radius = rx, center = center)
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
