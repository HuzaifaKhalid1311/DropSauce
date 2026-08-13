package org.koitharu.kotatsu.favourites.ui.duplicates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.parser.favicon.faviconUri
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.list.domain.ReadingProgress
import kotlin.math.roundToInt

private val ContentPadding = 16.dp
private val CardGap = 12.dp
private val CoverRatio = 13f / 18f

@Composable
fun DuplicatesContent(
	state: DuplicatesState.Ask,
	imageLoader: ImageLoader,
	onSkip: () -> Unit,
	onAddAnyway: () -> Unit,
	onPreview: (DuplicateCardModel) -> Unit,
	onReplace: (DuplicateCardModel) -> Unit,
	onDisableCheck: () -> Unit,
) {
	Column(modifier = Modifier.padding(top = 12.dp, bottom = 24.dp)) {
		Header(
			subtitle = stringResource(R.string.duplicates_summary, state.incoming.title),
			remaining = state.remaining,
			enabled = !state.isMigrating,
			onSkip = onSkip,
			onDisableCheck = onDisableCheck,
		)
		Spacer(Modifier.height(16.dp))
		// No height cap: the sheet grows with the card count and, once it runs out of screen, this is
		// the part that scrolls while the header and the action button stay put. `fill = false` lets a
		// short list keep the sheet short.
		LazyColumn(
			contentPadding = PaddingValues(horizontal = ContentPadding),
			verticalArrangement = Arrangement.spacedBy(CardGap),
			modifier = Modifier.weight(1f, fill = false),
		) {
			items(items = state.cards, key = { it.manga.id }) { card ->
				DuplicateCard(
					card = card,
					imageLoader = imageLoader,
					onPreview = onPreview,
					onReplace = onReplace,
				)
			}
		}
		Spacer(Modifier.height(16.dp))
		Button(
			onClick = onAddAnyway,
			enabled = !state.isMigrating,
			shape = RoundedCornerShape(28.dp),
			modifier = Modifier
				.padding(horizontal = ContentPadding)
				.fillMaxWidth()
				.height(56.dp),
		) {
			Icon(
				painter = painterResource(R.drawable.ic_heart),
				contentDescription = null,
				modifier = Modifier.size(20.dp),
			)
			Spacer(Modifier.width(10.dp))
			Text(
				text = stringResource(R.string.duplicates_add_anyway),
				style = MaterialTheme.typography.titleMedium,
			)
		}
	}
}

@Composable
private fun Header(
	subtitle: String,
	remaining: Int,
	enabled: Boolean,
	onSkip: () -> Unit,
	onDisableCheck: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 12.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		FilledTonalIconButton(
			onClick = onSkip,
			enabled = enabled,
			modifier = Modifier.size(40.dp),
		) {
			Icon(
				painter = painterResource(R.drawable.ic_close),
				contentDescription = stringResource(R.string.duplicates_skip),
				modifier = Modifier.size(20.dp),
			)
		}
		Column(
			modifier = Modifier
				.weight(1f)
				.padding(horizontal = 12.dp),
		) {
			Text(
				text = stringResource(R.string.duplicates_title),
				style = MaterialTheme.typography.titleLarge,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = subtitle,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
			if (remaining > 0) {
				Text(
					text = pluralStringResource(R.plurals.duplicates_remaining, remaining, remaining),
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.primary,
					modifier = Modifier.padding(top = 2.dp),
				)
			}
		}
		Box {
			var isMenuOpen by remember { mutableStateOf(false) }
			IconButton(
				onClick = { isMenuOpen = true },
				enabled = enabled,
				modifier = Modifier.size(44.dp),
			) {
				Icon(
					painter = painterResource(R.drawable.ic_more_vert),
					contentDescription = stringResource(R.string.more),
					// Explicit: this sits on the bare sheet, where LocalContentColor is not theme-aware
					// and the icon would come out near-invisible in one of the two themes.
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					// 24dp is the app-wide toolbar/overflow icon size; anything smaller reads as a
					// different, undersized control next to every other menu in the app.
					modifier = Modifier.size(24.dp),
				)
			}
			DropdownMenu(
				expanded = isMenuOpen,
				onDismissRequest = { isMenuOpen = false },
				shape = RoundedCornerShape(20.dp),
			) {
				DropdownMenuItem(
					text = { Text(stringResource(R.string.duplicates_stop_checking)) },
					leadingIcon = {
						Icon(
							painter = painterResource(R.drawable.ic_eye_off),
							contentDescription = null,
							modifier = Modifier.size(20.dp),
						)
					},
					onClick = {
						isMenuOpen = false
						onDisableCheck()
					},
				)
			}
		}
	}
}

@Composable
private fun DuplicateCard(
	card: DuplicateCardModel,
	imageLoader: ImageLoader,
	onPreview: (DuplicateCardModel) -> Unit,
	onReplace: (DuplicateCardModel) -> Unit,
) {
	Surface(
		onClick = { onPreview(card) },
		enabled = !card.isBlocked,
		shape = RoundedCornerShape(26.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		modifier = Modifier.fillMaxWidth(),
	) {
		Box {
			Row(modifier = Modifier.padding(14.dp)) {
				Cover(card, imageLoader)
				Spacer(Modifier.width(14.dp))
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = card.manga.title,
						style = MaterialTheme.typography.titleMedium,
						color = MaterialTheme.colorScheme.onSurface,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
						// Keep clear of the preview badge parked in the card's corner.
						modifier = Modifier.padding(end = 28.dp),
					)
					val author = card.manga.authors.firstOrNull()
					if (!author.isNullOrBlank()) {
						Text(
							text = author,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.padding(top = 2.dp),
						)
					}
					Spacer(Modifier.height(10.dp))
					ChaptersLine(card)
					SourceLine(card, imageLoader)
					CategoryPill(card)
					Spacer(Modifier.height(12.dp))
					Row(
						horizontalArrangement = Arrangement.End,
						modifier = Modifier.fillMaxWidth(),
					) {
						ReplaceButton(card, onReplace)
					}
				}
			}
			// Preview affordance, parked in the card's own corner rather than over the artwork.
			Icon(
				painter = painterResource(R.drawable.ic_open_in_new),
				contentDescription = stringResource(R.string.preview),
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier
					.align(Alignment.TopEnd)
					.padding(14.dp)
					.size(18.dp),
			)
		}
	}
}

@Composable
private fun Cover(card: DuplicateCardModel, imageLoader: ImageLoader) {
	Box {
		AsyncImage(
			model = card.manga.coverUrl,
			imageLoader = imageLoader,
			contentDescription = null,
			contentScale = ContentScale.Crop,
			modifier = Modifier
				.width(92.dp)
				.aspectRatio(CoverRatio)
				.clip(RoundedCornerShape(18.dp)),
		)
		val progress = card.duplicate.progress
		if (ReadingProgress.isValid(progress) && progress > 0f) {
			ProgressBadge(
				progress = progress,
				modifier = Modifier
					.align(Alignment.BottomEnd)
					.padding(6.dp),
			)
		}
	}
}

@Composable
private fun ProgressBadge(progress: Float, modifier: Modifier = Modifier) {
	Surface(
		color = Color.Black.copy(alpha = 0.55f),
		shape = RoundedCornerShape(percent = 50),
		modifier = modifier.size(30.dp),
	) {
		Box(contentAlignment = Alignment.Center) {
			CircularProgressIndicator(
				progress = { progress },
				color = MaterialTheme.colorScheme.primary,
				trackColor = Color.White.copy(alpha = 0.25f),
				strokeWidth = 2.5.dp,
				strokeCap = StrokeCap.Round,
				modifier = Modifier.size(26.dp),
			)
			Text(
				text = "${(progress * 100f).roundToInt()}",
				style = MaterialTheme.typography.labelSmall,
				color = Color.White,
				maxLines = 1,
			)
		}
	}
}

@Composable
private fun ChaptersLine(card: DuplicateCardModel) {
	val count = card.duplicate.chaptersCount
	Row(verticalAlignment = Alignment.CenterVertically) {
		Text(
			text = if (count > 0) {
				pluralStringResource(R.plurals.chapters, count, count)
			} else {
				stringResource(R.string.no_chapters)
			},
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		val diff = card.chaptersDiff
		if (diff != 0) {
			Spacer(Modifier.width(6.dp))
			Text(
				text = if (diff > 0) "▲ +$diff" else "▼ $diff",
				style = MaterialTheme.typography.labelMedium,
				color = colorResource(if (diff > 0) R.color.common_green else R.color.common_red),
				maxLines = 1,
			)
		}
	}
}

/** The extension, sitting inline with the other metadata: favicon plus plain secondary text. */
@Composable
private fun SourceLine(card: DuplicateCardModel, imageLoader: ImageLoader) {
	val context = LocalContext.current
	val source = card.manga.source
	val request = remember(context, source) {
		ImageRequest.Builder(context)
			.data(source.faviconUri())
			.mangaSourceExtra(source)
			.build()
	}
	val fallback = painterResource(R.drawable.ic_web)
	Row(
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier.padding(top = 4.dp),
	) {
		AsyncImage(
			model = request,
			imageLoader = imageLoader,
			contentDescription = null,
			placeholder = fallback,
			error = fallback,
			fallback = fallback,
			contentScale = ContentScale.Fit,
			modifier = Modifier
				.size(14.dp)
				.clip(RoundedCornerShape(4.dp)),
		)
		Spacer(Modifier.width(6.dp))
		Text(
			text = source.getTitle(context),
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

/** The favourite category, given the tonal-pill treatment so it reads as the entry's home. */
@Composable
private fun CategoryPill(card: DuplicateCardModel) {
	val categories = card.duplicate.categories
	if (categories.isEmpty()) {
		return
	}
	Surface(
		color = MaterialTheme.colorScheme.secondaryContainer,
		shape = RoundedCornerShape(10.dp),
		modifier = Modifier.padding(top = 8.dp),
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
		) {
			Icon(
				painter = painterResource(R.drawable.ic_heart),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSecondaryContainer,
				modifier = Modifier.size(13.dp),
			)
			Spacer(Modifier.width(6.dp))
			Text(
				text = categories.joinToString(", "),
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSecondaryContainer,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

@Composable
private fun ReplaceButton(
	card: DuplicateCardModel,
	onReplace: (DuplicateCardModel) -> Unit,
) {
	FilledTonalButton(
		onClick = { onReplace(card) },
		enabled = card.duplicate.canReplace && !card.isBlocked,
		shape = RoundedCornerShape(18.dp),
		contentPadding = PaddingValues(horizontal = 14.dp),
		modifier = Modifier.height(38.dp),
	) {
		when {
			card.isMigrating -> CircularProgressIndicator(
				strokeWidth = 2.dp,
				modifier = Modifier.size(18.dp),
			)

			!card.duplicate.canReplace -> Text(
				text = stringResource(R.string.replace_unavailable),
				style = MaterialTheme.typography.labelLarge,
				maxLines = 1,
			)

			else -> {
				Icon(
					painter = painterResource(R.drawable.ic_swap),
					contentDescription = null,
					modifier = Modifier.size(18.dp),
				)
				Spacer(Modifier.width(6.dp))
				Text(
					text = stringResource(R.string.replace),
					style = MaterialTheme.typography.labelLarge,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}
