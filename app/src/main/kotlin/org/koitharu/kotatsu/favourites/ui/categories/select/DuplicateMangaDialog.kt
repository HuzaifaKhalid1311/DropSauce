package org.koitharu.kotatsu.favourites.ui.categories.select

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogTextButton
import org.koitharu.kotatsu.core.ui.dialog.ExpressivePillButton
import org.koitharu.kotatsu.core.util.ext.mangaExtra
import org.koitharu.kotatsu.core.util.ext.stableMangaCoverKey
import org.koitharu.kotatsu.favourites.domain.model.DuplicateGroup
import org.koitharu.kotatsu.favourites.domain.model.DuplicateMatch
import org.koitharu.kotatsu.parsers.model.Manga

/** Minimum touch target for the compact in-card actions, per Material 3 guidance. */
private val MinTouchTarget = 48.dp
private val CoverSize = 56.dp to 78.dp

/**
 * Warning shown above the category picker when the manga being added looks like something already
 * in Favourites. Purely advisory — the picker underneath stays usable throughout.
 */
@Composable
fun DuplicateWarningSection(
	duplicates: List<DuplicateGroup>,
	imageLoader: ImageLoader,
	onViewExisting: (Manga) -> Unit,
	onMigrate: (target: Manga, existing: Manga) -> Unit,
	onAddAnyway: (targetId: Long) -> Unit,
	onSkip: (targetId: Long) -> Unit,
	onAddAllAnyway: () -> Unit,
) {
	AnimatedVisibility(
		visible = duplicates.isNotEmpty(),
		enter = fadeIn() + expandVertically(),
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(bottom = 8.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Icon(
					painter = painterResource(R.drawable.ic_info_outline),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(18.dp),
				)
				Spacer(Modifier.width(8.dp))
				Text(
					text = stringResource(R.string.possible_duplicates_title),
					style = MaterialTheme.typography.titleSmall,
					color = MaterialTheme.colorScheme.onSurface,
				)
			}
			Text(
				text = stringResource(R.string.possible_duplicates_summary),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			duplicates.forEach { group ->
				group.matches.forEach { match ->
					DuplicateCard(
						group = group,
						match = match,
						imageLoader = imageLoader,
						onViewExisting = { onViewExisting(match.manga) },
						onMigrate = { onMigrate(group.target, match.manga) },
					)
				}
				DuplicateGroupActions(
					onAddAnyway = { onAddAnyway(group.target.id) },
					onSkip = { onSkip(group.target.id) },
				)
			}
			if (duplicates.size > 1) {
				TextButton(
					onClick = onAddAllAnyway,
					modifier = Modifier
						.fillMaxWidth()
						.heightIn(min = MinTouchTarget),
				) {
					Text(
						text = stringResource(R.string.action_add_all_anyway),
						style = MaterialTheme.typography.labelLarge,
					)
				}
			}
		}
	}
}

@Composable
private fun DuplicateCard(
	group: DuplicateGroup,
	match: DuplicateMatch,
	imageLoader: ImageLoader,
	onViewExisting: () -> Unit,
	onMigrate: () -> Unit,
) {
	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = RoundedCornerShape(16.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
	) {
		Column(
			modifier = Modifier.padding(12.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				MangaColumn(
					manga = group.target,
					chaptersCount = group.targetChaptersCount,
					label = stringResource(R.string.duplicate_adding),
					chaptersDiff = group.targetChaptersCount - match.chaptersCount,
					showDiff = group.targetChaptersCount > 0 && match.chaptersCount > 0,
					imageLoader = imageLoader,
					modifier = Modifier.weight(1f),
				)
				Icon(
					painter = painterResource(R.drawable.ic_arrow_forward),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier
						.padding(horizontal = 4.dp)
						.size(18.dp),
				)
				MangaColumn(
					manga = match.manga,
					chaptersCount = match.chaptersCount,
					label = stringResource(R.string.duplicate_in_library),
					chaptersDiff = 0,
					showDiff = false,
					imageLoader = imageLoader,
					modifier = Modifier.weight(1f),
				)
			}
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				TextButton(
					onClick = onViewExisting,
					modifier = Modifier
						.weight(1f)
						.heightIn(min = MinTouchTarget),
				) {
					Text(
						text = stringResource(R.string.action_view_existing),
						style = MaterialTheme.typography.labelLarge,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
				// Migrating away from an imported copy would orphan the local files.
				if (!match.manga.isLocal) {
					TextButton(
						onClick = onMigrate,
						modifier = Modifier
							.weight(1f)
							.heightIn(min = MinTouchTarget),
					) {
						Text(
							text = stringResource(R.string.migrate),
							style = MaterialTheme.typography.labelLarge,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					}
				}
			}
		}
	}
}

@Composable
private fun DuplicateGroupActions(
	onAddAnyway: () -> Unit,
	onSkip: () -> Unit,
) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		TextButton(
			onClick = onAddAnyway,
			modifier = Modifier
				.weight(1f)
				.heightIn(min = MinTouchTarget),
		) {
			Text(
				text = stringResource(R.string.action_add_anyway),
				style = MaterialTheme.typography.labelLarge,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
		TextButton(
			onClick = onSkip,
			modifier = Modifier
				.weight(1f)
				.heightIn(min = MinTouchTarget),
		) {
			Text(
				text = stringResource(R.string.skip),
				style = MaterialTheme.typography.labelLarge,
				color = MaterialTheme.colorScheme.error,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

@Composable
private fun MangaColumn(
	manga: Manga,
	chaptersCount: Int,
	label: String,
	chaptersDiff: Int,
	showDiff: Boolean,
	imageLoader: ImageLoader,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	Column(
		modifier = modifier,
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		AsyncImage(
			model = mangaCoverRequest(manga),
			imageLoader = imageLoader,
			contentDescription = manga.title,
			contentScale = ContentScale.Crop,
			modifier = Modifier
				.size(CoverSize.first, CoverSize.second)
				.clip(RoundedCornerShape(10.dp)),
		)
		Text(
			text = manga.title,
			style = MaterialTheme.typography.bodySmall,
			fontWeight = FontWeight.SemiBold,
			textAlign = TextAlign.Center,
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
		)
		Text(
			text = manga.source.getTitle(context),
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSecondaryContainer,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
			modifier = Modifier
				.clip(RoundedCornerShape(6.dp))
				.background(MaterialTheme.colorScheme.secondaryContainer)
				.padding(horizontal = 6.dp, vertical = 2.dp),
		)
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				text = if (chaptersCount > 0) {
					pluralStringResource(R.plurals.chapters, chaptersCount, chaptersCount)
				} else {
					stringResource(R.string.no_chapters)
				},
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			if (showDiff && chaptersDiff != 0) {
				Spacer(Modifier.width(4.dp))
				ChapterDiffBadge(chaptersDiff)
			}
		}
	}
}

@Composable
private fun ChapterDiffBadge(diff: Int) {
	val isMore = diff > 0
	val tint = if (isMore) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
	Row(verticalAlignment = Alignment.CenterVertically) {
		Icon(
			painter = painterResource(
				if (isMore) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down,
			),
			contentDescription = null,
			tint = tint,
			modifier = Modifier.size(12.dp),
		)
		Text(
			text = if (isMore) "+$diff" else diff.toString(),
			style = MaterialTheme.typography.labelSmall,
			fontWeight = FontWeight.Bold,
			color = tint,
		)
	}
}

/**
 * Confirmation shown after tapping Migrate. Replaces the whole dialog body so the destructive
 * nature of the action is unambiguous.
 */
@Composable
fun MigrationConfirmation(
	request: FavoriteDialogViewModel.MigrationRequest,
	imageLoader: ImageLoader,
	onConfirm: () -> Unit,
	onBack: () -> Unit,
) {
	val context = LocalContext.current
	ExpressiveDialogCard(
		icon = painterResource(R.drawable.ic_info_outline),
		title = stringResource(R.string.migrate),
	) {
		Text(
			text = stringResource(
				R.string.migrate_confirmation,
				request.existing.title,
				request.existing.source.getTitle(context),
				request.target.title,
				request.target.source.getTitle(context),
			),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
			modifier = Modifier.fillMaxWidth(),
		)
		Spacer(Modifier.size(16.dp))
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceEvenly,
			verticalAlignment = Alignment.CenterVertically,
		) {
			MigrationSide(request.existing, imageLoader, Modifier.weight(1f))
			Icon(
				painter = painterResource(R.drawable.ic_arrow_forward),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.primary,
				modifier = Modifier.padding(horizontal = 8.dp),
			)
			MigrationSide(request.target, imageLoader, Modifier.weight(1f))
		}
		Spacer(Modifier.size(16.dp))
		ExpressivePillButton(
			text = stringResource(R.string.migrate),
			primary = true,
			enabled = !request.isRunning,
			onClick = onConfirm,
		)
		Spacer(Modifier.size(8.dp))
		ExpressiveDialogTextButton(
			text = stringResource(R.string.back),
			onClick = { if (!request.isRunning) onBack() },
		)
	}
}

@Composable
private fun MigrationSide(
	manga: Manga,
	imageLoader: ImageLoader,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	Column(
		modifier = modifier,
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		AsyncImage(
			model = mangaCoverRequest(manga),
			imageLoader = imageLoader,
			contentDescription = manga.title,
			contentScale = ContentScale.Crop,
			modifier = Modifier
				.size(60.dp, 84.dp)
				.clip(RoundedCornerShape(8.dp)),
		)
		Text(
			text = manga.source.getTitle(context),
			style = MaterialTheme.typography.labelSmall,
			fontWeight = FontWeight.SemiBold,
			textAlign = TextAlign.Center,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
	}
}

@Composable
private fun mangaCoverRequest(manga: Manga): ImageRequest {
	val context = LocalContext.current
	val url = manga.coverUrl?.takeIf { it.isNotBlank() } ?: manga.largeCoverUrl?.takeIf { it.isNotBlank() }
	return if (url.isNullOrBlank()) {
		ImageRequest.Builder(context)
			.data(R.drawable.ic_placeholder)
			.build()
	} else {
		ImageRequest.Builder(context)
			.data(url)
			.mangaExtra(manga)
			.stableMangaCoverKey(manga, url)
			.crossfade(true)
			.build()
	}
}
