package org.koitharu.kotatsu.stats.ui.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.stats.domain.StatsBucket
import org.koitharu.kotatsu.stats.domain.StatsBucketUnit
import org.koitharu.kotatsu.stats.ui.StatTile
import org.koitharu.kotatsu.stats.ui.ActivityBarChart
import org.koitharu.kotatsu.stats.ui.formatBucketTitle
import org.koitharu.kotatsu.stats.ui.formatBucketTick
import org.koitharu.kotatsu.stats.ui.labelStride
import java.time.ZoneId

/**
 * Per-title statistics shown when a row of the main screen is tapped: a daily pages-read chart
 * plus the two totals that actually mean something for a single title.
 */
@Composable
fun MangaStatsContent(
	title: String,
	subtitle: String?,
	buckets: List<StatsBucket>,
	pagesRead: Int,
	daysRead: Int,
	bottomInset: Dp,
	onOpenClick: () -> Unit,
) {
	val zone = remember { ZoneId.systemDefault() }
	var selected by remember(buckets) { mutableIntStateOf(-1) }
	val labels = remember(buckets) {
		val stride = labelStride(buckets.size)
		buckets.mapIndexed { index, bucket ->
			if (index % stride == 0) formatBucketTick(bucket.startAt, StatsBucketUnit.DAY, zone) else null
		}
	}
	val bucket = buckets.getOrNull(selected)
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(start = 20.dp, end = 20.dp, top = 20.dp)
			.padding(bottom = bottomInset + 20.dp),
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = title,
					style = MaterialTheme.typography.titleLarge,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
				if (subtitle != null) {
					Text(
						text = subtitle,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
			IconButton(onClick = onOpenClick) {
				Icon(
					painter = painterResource(R.drawable.ic_open_external),
					contentDescription = stringResource(R.string.details),
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		if (buckets.isNotEmpty()) {
			Spacer(Modifier.height(20.dp))
			Text(
				text = if (bucket != null) {
					formatBucketTitle(bucket.startAt, StatsBucketUnit.DAY, zone)
				} else {
					stringResource(R.string.stats_pages_read)
				},
				style = MaterialTheme.typography.labelLarge,
				color = if (bucket != null) {
					MaterialTheme.colorScheme.tertiary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
			)
			Text(
				text = (bucket?.duration ?: buckets.sumOf { it.duration }).toString(),
				style = MaterialTheme.typography.headlineMedium,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onSurface,
			)
			Spacer(Modifier.height(14.dp))
			ActivityBarChart(
				buckets = buckets,
				labels = labels,
				selectedIndex = selected,
				onSelect = { selected = it },
			)
		}
		Spacer(Modifier.height(20.dp))
		Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
			StatTile(
				value = pagesRead.toString(),
				label = stringResource(R.string.stats_pages_read),
				icon = painterResource(R.drawable.ic_book_page),
				accent = MaterialTheme.colorScheme.primary,
				modifier = Modifier.weight(1f),
			)
			StatTile(
				value = daysRead.toString(),
				label = stringResource(R.string.stats_active_days_short),
				icon = painterResource(R.drawable.ic_local_fire),
				accent = MaterialTheme.colorScheme.tertiary,
				modifier = Modifier.weight(1f),
			)
		}
	}
}
