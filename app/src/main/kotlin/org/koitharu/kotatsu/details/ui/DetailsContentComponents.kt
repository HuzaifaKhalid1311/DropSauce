package org.koitharu.kotatsu.details.ui

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.core.util.FileSize
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo
import org.koitharu.kotatsu.details.ui.scrobbling.labelResId
import java.util.Locale

@Composable
internal fun DescriptionCard(
	description: CharSequence?,
	manga: Manga,
	details: MangaDetails?,
	accent: Color,
) {
	val text = description?.toString()?.trim().orEmpty()
	// The appearance setting only decides how a long description *starts*; tapping still toggles it
	// either way, so turning collapsing off doesn't cost you the ability to fold a wall of text away.
	val collapseEnabled by rememberBooleanPref(AppSettings.KEY_COLLAPSE_DESCRIPTION, true)
	var expanded by rememberSaveable(collapseEnabled) { mutableStateOf(!collapseEnabled) }
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
@Suppress("DEPRECATION")
internal fun TagsSection(tags: List<ChipsView.ChipModel>, accent: Color, onTagClick: (MangaTag) -> Unit) {
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
		val needsToggle = remember(tags, maxWidth, chipStyle) {
			with(density) {
				val available = maxWidth.toPx()
				val chipHorizontalPadding = 28.dp.toPx()
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
				androidx.compose.foundation.layout.FlowRowOverflow.expandOrCollapseIndicator(
					expandIndicator = {
						TagToggleChip(text = stringResource(R.string.more), accent = accent, expanded = false) { expanded = true }
					},
					collapseIndicator = {
						TagToggleChip(text = stringResource(R.string.collapse), accent = accent, expanded = true) { expanded = false }
					},
				)
			} else {
				androidx.compose.foundation.layout.FlowRowOverflow.Visible
			},
		) {
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
internal fun TagToggleChip(text: String, accent: Color, expanded: Boolean, onClick: () -> Unit) {
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
internal fun ScrobblingSection(
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
internal fun RelatedSection(
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
		flingBehavior = CarouselDefaults.multiBrowseFlingBehavior(state = carouselState),
		contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = SCREEN_PADDING),
		modifier = Modifier
			.fillMaxWidth()
			.height(232.dp),
	) { i ->
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
internal fun LocalSizeRow(size: Long, manga: Manga, onClick: (Manga) -> Unit) {
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
