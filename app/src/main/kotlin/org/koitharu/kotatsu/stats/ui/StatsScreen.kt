package org.koitharu.kotatsu.stats.ui

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.stableMangaCoverKey
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.format
import org.koitharu.kotatsu.stats.domain.ReadingStats
import org.koitharu.kotatsu.stats.domain.StatsPeriod
import org.koitharu.kotatsu.stats.domain.StatsRecord
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Material 3 Expressive reading statistics: a headline activity card with a tappable chart, four
 * metric tiles and a ranked list of the titles behind the time.
 *
 * Everything below the filter row is driven by a single [ReadingStats] snapshot, so the chart, the
 * tiles and the list can never describe different periods.
 */
@Composable
fun StatsScreen(
	stats: ReadingStats,
	isLoading: Boolean,
	period: StatsPeriod,
	categories: List<FavouriteCategory>,
	selectedCategories: Set<Long>,
	imageLoader: ImageLoader,
	bottomInset: Dp,
	onPeriodChange: (StatsPeriod) -> Unit,
	onCategoryToggle: (FavouriteCategory) -> Unit,
	onCategoriesClear: () -> Unit,
	onMangaClick: (Manga) -> Unit,
) {
	val context = LocalContext.current
	val resources = context.resources
	val zone = remember { ZoneId.systemDefault() }
	// Selections are per-snapshot: an index means nothing once the period or filter changes.
	var selectedBucket by remember(stats) { mutableIntStateOf(-1) }

	Box(
		modifier = Modifier
			.fillMaxSize()
			.nestedScroll(rememberNestedScrollInteropConnection()),
	) {
		// Only the very first load gets a progress bar, and it floats over the content rather than
		// pushing it down — re-filtering keeps the previous numbers on screen until the new ones
		// land, so changing a chip no longer makes the whole page jump.
		if (isLoading && stats.isEmpty) {
			LinearProgressIndicator(
				modifier = Modifier
					.fillMaxWidth()
					.align(Alignment.TopCenter),
			)
		}
		LazyColumn(
			modifier = Modifier.fillMaxSize(),
			contentPadding = PaddingValues(top = 8.dp, bottom = bottomInset + 32.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			item("filters") {
				LazyRow(
					modifier = Modifier.fillMaxWidth(),
					contentPadding = PaddingValues(horizontal = STATS_PADDING),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					if (categories.isNotEmpty()) {
						item("category-filter") {
							CategoryDropdownChip(
								categories = categories,
								selected = selectedCategories,
								onToggle = onCategoryToggle,
								onClear = onCategoriesClear,
							)
						}
						item("divider") {
							Box(
								modifier = Modifier
									.padding(horizontal = 2.dp)
									.size(width = 1.dp, height = 22.dp)
									.background(MaterialTheme.colorScheme.outlineVariant),
							)
						}
					}
					items(StatsPeriod.entries, key = { it.name }) { entry ->
						FilterChip(
							selected = entry == period,
							onClick = { onPeriodChange(entry) },
							label = { Text(stringResource(entry.titleResId)) },
						)
					}
				}
			}

			if (stats.isEmpty) {
				item("empty") { StatsEmptyState() }
				return@LazyColumn
			}

			item("activity") {
				val bucket = stats.buckets.getOrNull(selectedBucket)
				val labels = remember(stats.buckets, stats.bucketUnit) {
					val stride = labelStride(stats.buckets.size)
					stats.buckets.mapIndexed { index, item ->
						if (index % stride == 0) formatBucketTick(item.startAt, stats.bucketUnit, zone) else null
					}
				}
				StatsCard {
					Text(
						text = stringResource(R.string.total_read_time),
						style = MaterialTheme.typography.labelLarge,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Spacer(Modifier.height(4.dp))
					Text(
						text = formatDurationShort(resources, bucket?.duration ?: stats.totalDuration),
						style = MaterialTheme.typography.displaySmall,
						fontWeight = FontWeight.Bold,
						color = MaterialTheme.colorScheme.onSurface,
					)
					Text(
						text = if (bucket != null) {
							formatBucketTitle(bucket.startAt, stats.bucketUnit, zone)
						} else {
							stringResource(period.rangeLabelResId())
						},
						style = MaterialTheme.typography.bodyMedium,
						color = if (bucket != null) {
							MaterialTheme.colorScheme.tertiary
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
					)
					Spacer(Modifier.height(18.dp))
					ActivityBarChart(
						buckets = stats.buckets,
						labels = labels,
						selectedIndex = selectedBucket,
						onSelect = { selectedBucket = it },
					)
					if (stats.activeDays > 0) {
						Spacer(Modifier.height(12.dp))
						Text(
							text = stringResource(R.string.stats_active_days, stats.activeDays) +
								"  ·  " + stringResource(R.string.stats_daily_average) +
								" " + formatDurationShort(resources, stats.averagePerActiveDay),
							style = MaterialTheme.typography.labelMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
			}

			item("tiles") {
				Column(
					modifier = Modifier.padding(horizontal = STATS_PADDING),
					verticalArrangement = Arrangement.spacedBy(10.dp),
				) {
					Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
						StatTile(
							value = stats.chapters.toString(),
							label = stringResource(R.string.stats_chapters_read),
							icon = painterResource(R.drawable.ic_auto_stories),
							accent = MaterialTheme.colorScheme.primary,
							modifier = Modifier.weight(1f),
						)
						StatTile(
							value = stats.pages.toString(),
							label = stringResource(R.string.stats_pages_read),
							icon = painterResource(R.drawable.ic_book_page),
							accent = MaterialTheme.colorScheme.secondary,
							modifier = Modifier.weight(1f),
						)
					}
					Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
						StatTile(
							value = stringResource(
								R.string.minutes_per_chapter_short,
								stats.minutesPerChapter.format(if (stats.minutesPerChapter < 10) 1 else 0),
							),
							label = stringResource(R.string.reading_rate),
							icon = painterResource(R.drawable.ic_timer),
							accent = MaterialTheme.colorScheme.tertiary,
							modifier = Modifier.weight(1f),
						)
						StatTile(
							value = stats.currentStreak.toString(),
							label = stringResource(R.string.stats_day_streak),
							icon = painterResource(R.drawable.ic_local_fire),
							accent = MaterialTheme.colorScheme.error,
							// The trophy reads as "your record" without spending a whole extra line on it,
							// which is what keeps this tile the same height as its neighbours.
							badgeIcon = painterResource(R.drawable.ic_trophy)
								.takeIf { stats.longestStreak > 0 },
							badgeText = stats.longestStreak.toString().takeIf { stats.longestStreak > 0 },
							modifier = Modifier.weight(1f),
						)
					}
				}
			}

			if (stats.records.isNotEmpty()) {
				item("top-manga") {
					TopMangaSection(
						stats = stats,
						imageLoader = imageLoader,
						onMangaClick = onMangaClick,
					)
				}
			}
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopMangaSection(
	stats: ReadingStats,
	imageLoader: ImageLoader,
	onMangaClick: (Manga) -> Unit,
) {
	val context = LocalContext.current
	val resources = context.resources
	val zone = remember { ZoneId.systemDefault() }
	val colors = rememberRankColors(stats.records.size)
	val total = remember(stats.records) {
		stats.records.sumOf { it.duration }.coerceAtLeast(1L)
	}
	var isOtherShown by remember(stats) { mutableStateOf(false) }
	Column {
		StatsSectionHeader(title = stringResource(R.string.stats_top_manga))
		StatsCard {
			stats.records.forEachIndexed { index, record ->
				if (index > 0) {
					HorizontalDivider(
						modifier = Modifier.padding(horizontal = 8.dp),
						color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
					)
				}
				val accent = colors.getOrElse(index) { MaterialTheme.colorScheme.primary }
				val manga = record.manga
				TopMangaRow(
					// The trailing "other" bucket is not a ranked title, so it gets no position.
					rank = if (manga != null) index + 1 else null,
					title = record.displayTitle(context),
					duration = formatDurationShort(resources, record.duration),
					percent = record.percentOf(total),
					detail = record.detailLine(resources, zone),
					accent = accent,
					onClick = when {
						manga != null -> ({ onMangaClick(manga) })
						stats.otherRecords.isNotEmpty() -> ({ isOtherShown = true })
						else -> null
					},
					cover = if (manga != null) {
						{ MangaCover(manga, imageLoader) }
					} else {
						{
							Icon(
								painter = painterResource(R.drawable.ic_stacks),
								contentDescription = null,
								tint = accent,
								modifier = Modifier.size(22.dp),
							)
						}
					},
				)
			}
		}
	}
	if (isOtherShown) {
		OtherMangaSheet(
			records = stats.otherRecords,
			total = total,
			imageLoader = imageLoader,
			zone = zone,
			onDismiss = { isOtherShown = false },
			onMangaClick = onMangaClick,
		)
	}
}

/** The long tail behind the "other manga" row, on its own sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OtherMangaSheet(
	records: List<StatsRecord>,
	total: Long,
	imageLoader: ImageLoader,
	zone: ZoneId,
	onDismiss: () -> Unit,
	onMangaClick: (Manga) -> Unit,
) {
	val context = LocalContext.current
	val resources = context.resources
	val accent = MaterialTheme.colorScheme.secondary
	ModalBottomSheet(
		onDismissRequest = onDismiss,
		sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
		shape = RoundedCornerShape(topStart = STATS_CARD_CORNER, topEnd = STATS_CARD_CORNER),
		containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
	) {
		LazyColumn(
			contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
		) {
			item("header") {
				Column {
					Text(
						text = stringResource(R.string.other_manga),
						style = MaterialTheme.typography.headlineSmall,
						fontWeight = FontWeight.SemiBold,
						color = MaterialTheme.colorScheme.onSurface,
						modifier = Modifier.padding(horizontal = 8.dp),
					)
					Text(
						text = pluralStringResource(R.plurals.stats_titles, records.size, records.size),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						modifier = Modifier.padding(horizontal = 8.dp),
					)
					Spacer(Modifier.height(12.dp))
				}
			}
			items(records, key = { it.manga?.id ?: 0L }) { record ->
				val manga = record.manga
				TopMangaRow(
					rank = null,
					title = record.displayTitle(context),
					duration = formatDurationShort(resources, record.duration),
					percent = record.percentOf(total),
					detail = record.detailLine(resources, zone),
					accent = accent,
					onClick = if (manga != null) {
						{
							onDismiss()
							onMangaClick(manga)
						}
					} else {
						null
					},
					cover = if (manga != null) {
						{ MangaCover(manga, imageLoader) }
					} else {
						null
					},
				)
			}
		}
	}
}

@Composable
private fun MangaCover(manga: Manga, imageLoader: ImageLoader) {
	val context = LocalContext.current
	AsyncImage(
		model = remember(manga) {
			ImageRequest.Builder(context)
				.data(manga.coverUrl)
				.crossfade(true)
				.mangaSourceExtra(manga.source)
				.stableMangaCoverKey(manga, manga.coverUrl)
				.build()
		},
		imageLoader = imageLoader,
		contentDescription = null,
		contentScale = ContentScale.Crop,
		modifier = Modifier
			.fillMaxSize()
			.clip(RoundedCornerShape(14.dp)),
	)
}

private fun StatsRecord.percentOf(total: Long): String =
	"${(duration.toFloat() / total * 100f).roundToInt()}%"

/** "340 pages · 6 days · since 12 Aug 2026", skipping whatever this record doesn't have. */
private fun StatsRecord.detailLine(resources: Resources, zone: ZoneId): String? = buildList {
	if (pages > 0) add(resources.getQuantityString(R.plurals.stats_pages, pages, pages))
	if (daysRead > 0) add(resources.getQuantityString(R.plurals.stats_days, daysRead, daysRead))
	if (firstReadAt > 0L) add(resources.getString(R.string.stats_since, formatDate(firstReadAt, zone)))
}.joinToString(" · ").ifEmpty { null }

/**
 * Single chip standing in for the whole favourite-category filter, so the period chips stay the
 * only thing competing for the filter row.
 */
@Composable
private fun CategoryDropdownChip(
	categories: List<FavouriteCategory>,
	selected: Set<Long>,
	onToggle: (FavouriteCategory) -> Unit,
	onClear: () -> Unit,
) {
	var expanded by remember { mutableStateOf(false) }
	// No selection means no filtering at all, so the chip says so plainly and stays unhighlighted.
	val label = when (selected.size) {
		0 -> stringResource(R.string.stats_categories_all)
		1 -> categories.firstOrNull { it.id in selected }?.title
			?: stringResource(R.string.stats_categories_all)

		else -> pluralStringResource(R.plurals.stats_categories_selected, selected.size, selected.size)
	}
	Box {
		FilterChip(
			selected = selected.isNotEmpty(),
			onClick = { expanded = true },
			label = { Text(label) },
			trailingIcon = {
				Icon(
					painter = painterResource(R.drawable.ic_expand_more),
					contentDescription = null,
					modifier = Modifier.size(FilterChipDefaults.IconSize),
				)
			},
		)
		DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			DropdownMenuItem(
				text = { Text(stringResource(R.string.stats_categories_all)) },
				trailingIcon = {
					if (selected.isEmpty()) {
						Icon(painterResource(R.drawable.ic_check), contentDescription = null)
					}
				},
				onClick = {
					onClear()
					expanded = false
				},
			)
			categories.forEach { category ->
				DropdownMenuItem(
					text = { Text(category.title) },
					trailingIcon = {
						if (category.id in selected) {
							Icon(painterResource(R.drawable.ic_check), contentDescription = null)
						}
					},
					onClick = { onToggle(category) },
				)
			}
		}
	}
}

private fun StatsRecord.displayTitle(context: android.content.Context): String =
	manga?.title ?: context.getString(R.string.other_manga)

private fun StatsPeriod.rangeLabelResId(): Int = when (this) {
	StatsPeriod.DAY -> R.string.stats_range_day
	StatsPeriod.WEEK -> R.string.stats_range_week
	StatsPeriod.MONTH -> R.string.stats_range_month
	StatsPeriod.MONTHS_3 -> R.string.stats_range_3months
	StatsPeriod.ALL -> R.string.stats_range_all
}

@Composable
private fun StatsEmptyState() {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING, vertical = 40.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Icon(
			painter = painterResource(R.drawable.ic_empty_history),
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
			modifier = Modifier.size(72.dp),
		)
		Spacer(Modifier.height(16.dp))
		Text(
			text = stringResource(R.string.text_empty_holder_primary),
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.onSurface,
			textAlign = TextAlign.Center,
		)
		Spacer(Modifier.height(4.dp))
		Text(
			text = stringResource(R.string.empty_stats_text),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
		)
	}
}
