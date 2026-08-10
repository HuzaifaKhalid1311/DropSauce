package org.koitharu.kotatsu.favourites.ui.categories.select

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import org.koitharu.kotatsu.core.model.chaptersCount
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogTextButton
import org.koitharu.kotatsu.core.ui.dialog.ExpressivePillButton
import org.koitharu.kotatsu.core.util.ext.mangaExtra
import org.koitharu.kotatsu.core.util.ext.stableMangaCoverKey
import org.koitharu.kotatsu.parsers.model.Manga
import kotlin.math.sign

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed

@Composable
fun DuplicateMangaDialog(
	duplicatesList: List<Pair<Manga, List<Manga>>>,
	imageLoader: ImageLoader,
	onAddAnyway: () -> Unit,
	onAddIndividualAnyway: (Manga) -> Unit,
	onSkipIndividual: (Manga) -> Unit,
	onOpenManga: (Manga) -> Unit,
	onMigrateManga: (targetManga: Manga, existingManga: Manga) -> Unit,
	onDismissRequest: () -> Unit,
) {
	var selectedForMigration by remember { mutableStateOf<Pair<Manga, Manga>?>(null) }
	val flatDuplicates = remember(duplicatesList) {
		duplicatesList.flatMap { (target, matches) ->
			matches.map { duplicate -> target to duplicate }
		}
	}

	val selected = selectedForMigration
	if (selected != null) {
		val (targetManga, existingManga) = selected
		MigrationConfirmationView(
			targetManga = targetManga,
			existingManga = existingManga,
			imageLoader = imageLoader,
			onConfirm = {
				onMigrateManga(targetManga, existingManga)
				selectedForMigration = null
			},
			onBack = { selectedForMigration = null },
		)
		return
	}

	ExpressiveDialogCard(
		icon = painterResource(R.drawable.ic_info_outline),
		title = stringResource(R.string.possible_duplicates_title),
	) {
		LazyColumn(
			modifier = Modifier
				.fillMaxWidth()
				.heightIn(max = 420.dp),
			verticalArrangement = Arrangement.spacedBy(16.dp),
		) {
			item(key = "summary") {
				Text(
					text = stringResource(R.string.possible_duplicates_summary),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					textAlign = TextAlign.Center,
					modifier = Modifier.fillMaxWidth(),
				)
			}

			itemsIndexed(
				items = flatDuplicates,
				key = { index, (target, duplicate) -> "${target.id}_${duplicate.id}_$index" },
			) { _, (targetManga, duplicate) ->
				Box(modifier = Modifier.animateItem()) {
					DuplicateCardItem(
						targetManga = targetManga,
						duplicate = duplicate,
						imageLoader = imageLoader,
						onOpenManga = { onOpenManga(duplicate) },
						onMigrateManga = { selectedForMigration = targetManga to duplicate },
						onAddIndividualAnyway = { onAddIndividualAnyway(targetManga) },
						onSkipIndividual = { onSkipIndividual(targetManga) },
					)
				}
			}
		}

		Spacer(Modifier.size(16.dp))
		ExpressivePillButton(
			text = stringResource(R.string.action_add_anyway),
			primary = true,
			onClick = onAddAnyway,
		)
		Spacer(Modifier.size(8.dp))
		ExpressiveDialogTextButton(
			text = stringResource(android.R.string.cancel),
			onClick = onDismissRequest,
		)
	}
}

@Composable
private fun DuplicateCardItem(
	targetManga: Manga,
	duplicate: Manga,
	imageLoader: ImageLoader,
	onOpenManga: () -> Unit,
	onMigrateManga: () -> Unit,
	onAddIndividualAnyway: () -> Unit,
	onSkipIndividual: () -> Unit,
) {
	val context = LocalContext.current
	val sourceTitle = remember(duplicate.source) { duplicate.source.getTitle(context) }
	val isTargetLoading = targetManga.chapters.isNullOrEmpty()
	val isDuplicateLoading = duplicate.chapters.isNullOrEmpty()
	val existingCount = duplicate.chaptersCount()
	val targetCount = targetManga.chaptersCount()
	val diff = targetCount - existingCount

	val coverRequest = remember(duplicate.id, duplicate.coverUrl, duplicate.largeCoverUrl, duplicate.source) {
		val url = duplicate.coverUrl?.takeIf { it.isNotBlank() } ?: duplicate.largeCoverUrl?.takeIf { it.isNotBlank() }
		if (url.isNullOrBlank()) {
			ImageRequest.Builder(context)
				.data(R.drawable.ic_placeholder)
				.build()
		} else {
			ImageRequest.Builder(context)
				.data(url)
				.mangaExtra(duplicate)
				.stableMangaCoverKey(duplicate, url)
				.crossfade(true)
				.build()
		}
	}

	Card(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(16.dp))
			.clickable { onOpenManga() },
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
		),
		shape = RoundedCornerShape(16.dp),
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(12.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				AsyncImage(
					model = coverRequest,
					imageLoader = imageLoader,
					contentDescription = duplicate.title,
					contentScale = ContentScale.Crop,
					modifier = Modifier
						.size(64.dp, 90.dp)
						.clip(RoundedCornerShape(12.dp)),
				)
				Spacer(Modifier.width(12.dp))
				Column(
					modifier = Modifier.weight(1f),
					verticalArrangement = Arrangement.spacedBy(4.dp),
				) {
					Text(
						text = duplicate.title,
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.Bold,
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
					)
					Text(
						text = sourceTitle,
						style = MaterialTheme.typography.labelMedium,
						color = MaterialTheme.colorScheme.onSecondaryContainer,
						modifier = Modifier
							.clip(RoundedCornerShape(6.dp))
							.background(MaterialTheme.colorScheme.secondaryContainer)
							.padding(horizontal = 8.dp, vertical = 3.dp),
					)

					Row(verticalAlignment = Alignment.CenterVertically) {
						Text(
							text = if (isDuplicateLoading) {
								stringResource(R.string.loading_)
							} else if (existingCount > 0) {
								pluralStringResource(R.plurals.chapters, existingCount, existingCount)
							} else {
								stringResource(R.string.no_chapters)
							},
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
						if (!isTargetLoading && !isDuplicateLoading) {
							when (diff.sign) {
								1 -> Text(
									text = "  ▲ +$diff",
									style = MaterialTheme.typography.bodySmall,
									fontWeight = FontWeight.Bold,
									color = MaterialTheme.colorScheme.primary,
								)
								-1 -> Text(
									text = "  ▼ $diff",
									style = MaterialTheme.typography.bodySmall,
									fontWeight = FontWeight.Bold,
									color = MaterialTheme.colorScheme.error,
								)
							}
						} else if (isTargetLoading) {
							Text(
								text = "  (" + stringResource(R.string.loading_) + "…)",
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
				}
			}

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				OutlinedButton(
					onClick = onOpenManga,
					modifier = Modifier
						.weight(1f)
						.height(36.dp),
					shape = RoundedCornerShape(20.dp),
					contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
				) {
					Text(
						text = stringResource(R.string.action_view_existing),
						style = MaterialTheme.typography.labelMedium,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}

				FilledTonalButton(
					onClick = onMigrateManga,
					modifier = Modifier
						.weight(1f)
						.height(36.dp),
					shape = RoundedCornerShape(20.dp),
					contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
				) {
					Text(
						text = stringResource(R.string.migrate),
						style = MaterialTheme.typography.labelMedium,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Button(
					onClick = onAddIndividualAnyway,
					modifier = Modifier
						.weight(1f)
						.height(36.dp),
					shape = RoundedCornerShape(20.dp),
					contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
				) {
					Text(
						text = stringResource(R.string.action_add_anyway),
						style = MaterialTheme.typography.labelMedium,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}

				OutlinedButton(
					onClick = onSkipIndividual,
					modifier = Modifier
						.weight(1f)
						.height(36.dp),
					shape = RoundedCornerShape(20.dp),
					contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
					colors = ButtonDefaults.outlinedButtonColors(
						contentColor = MaterialTheme.colorScheme.error,
					),
				) {
					Text(
						text = stringResource(R.string.skip),
						style = MaterialTheme.typography.labelMedium,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
		}
	}
}

@Composable
private fun MigrationConfirmationView(
	targetManga: Manga,
	existingManga: Manga,
	imageLoader: ImageLoader,
	onConfirm: () -> Unit,
	onBack: () -> Unit,
) {
	val context = LocalContext.current
	val existingSourceTitle = remember(existingManga.source) { existingManga.source.getTitle(context) }
	val targetSourceTitle = remember(targetManga.source) { targetManga.source.getTitle(context) }
	val isExistingLoading = existingManga.chapters.isNullOrEmpty()
	val isTargetLoading = targetManga.chapters.isNullOrEmpty()
	val existingCount = existingManga.chaptersCount()
	val targetCount = targetManga.chaptersCount()

	val existingCoverRequest = remember(existingManga.id, existingManga.coverUrl, existingManga.largeCoverUrl, existingManga.source) {
		val url = existingManga.coverUrl?.takeIf { it.isNotBlank() } ?: existingManga.largeCoverUrl?.takeIf { it.isNotBlank() }
		if (url.isNullOrBlank()) {
			ImageRequest.Builder(context)
				.data(R.drawable.ic_placeholder)
				.build()
		} else {
			ImageRequest.Builder(context)
				.data(url)
				.mangaExtra(existingManga)
				.stableMangaCoverKey(existingManga, url)
				.crossfade(true)
				.build()
		}
	}

	val targetCoverRequest = remember(targetManga.id, targetManga.coverUrl, targetManga.largeCoverUrl, targetManga.source) {
		val url = targetManga.coverUrl?.takeIf { it.isNotBlank() } ?: targetManga.largeCoverUrl?.takeIf { it.isNotBlank() }
		if (url.isNullOrBlank()) {
			ImageRequest.Builder(context)
				.data(R.drawable.ic_placeholder)
				.build()
		} else {
			ImageRequest.Builder(context)
				.data(url)
				.mangaExtra(targetManga)
				.stableMangaCoverKey(targetManga, url)
				.crossfade(true)
				.build()
		}
	}

	ExpressiveDialogCard(
		icon = painterResource(R.drawable.ic_info_outline),
		title = stringResource(R.string.migrate),
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.verticalScroll(rememberScrollState()),
			verticalArrangement = Arrangement.spacedBy(16.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Text(
				text = stringResource(
					R.string.migrate_confirmation,
					existingManga.title,
					existingSourceTitle,
					targetManga.title,
					targetSourceTitle,
				),
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				textAlign = TextAlign.Center,
			)

			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceEvenly,
				verticalAlignment = Alignment.CenterVertically,
			) {
				// Existing Manga Column
				Column(
					horizontalAlignment = Alignment.CenterHorizontally,
					verticalArrangement = Arrangement.spacedBy(4.dp),
					modifier = Modifier.weight(1f),
				) {
					AsyncImage(
						model = existingCoverRequest,
						imageLoader = imageLoader,
						contentDescription = existingManga.title,
						contentScale = ContentScale.Crop,
						modifier = Modifier
							.size(60.dp, 84.dp)
							.clip(RoundedCornerShape(8.dp)),
					)
					Text(
						text = existingSourceTitle,
						style = MaterialTheme.typography.labelSmall,
						fontWeight = FontWeight.SemiBold,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					Text(
						text = if (isExistingLoading) {
							stringResource(R.string.loading_)
						} else if (existingCount > 0) {
							pluralStringResource(R.plurals.chapters, existingCount, existingCount)
						} else {
							stringResource(R.string.no_chapters)
						},
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}

				Icon(
					painter = painterResource(R.drawable.ic_arrow_forward),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.padding(horizontal = 8.dp),
				)

				// Target Manga Column
				Column(
					horizontalAlignment = Alignment.CenterHorizontally,
					verticalArrangement = Arrangement.spacedBy(4.dp),
					modifier = Modifier.weight(1f),
				) {
					AsyncImage(
						model = targetCoverRequest,
						imageLoader = imageLoader,
						contentDescription = targetManga.title,
						contentScale = ContentScale.Crop,
						modifier = Modifier
							.size(60.dp, 84.dp)
							.clip(RoundedCornerShape(8.dp)),
					)
					Text(
						text = targetSourceTitle,
						style = MaterialTheme.typography.labelSmall,
						fontWeight = FontWeight.SemiBold,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
					Text(
						text = if (isTargetLoading) {
							stringResource(R.string.loading_)
						} else if (targetCount > 0) {
							pluralStringResource(R.plurals.chapters, targetCount, targetCount)
						} else {
							stringResource(R.string.no_chapters)
						},
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
		}

		Spacer(Modifier.size(16.dp))
		ExpressivePillButton(
			text = stringResource(R.string.migrate),
			primary = true,
			onClick = onConfirm,
		)
		Spacer(Modifier.size(8.dp))
		ExpressiveDialogTextButton(
			text = stringResource(R.string.back),
			onClick = onBack,
		)
	}
}
