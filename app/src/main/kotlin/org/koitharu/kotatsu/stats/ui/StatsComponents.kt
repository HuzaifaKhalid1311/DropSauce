package org.koitharu.kotatsu.stats.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.stats.domain.StatsBucket
import kotlin.math.max

internal val STATS_PADDING = 20.dp
internal val STATS_CARD_CORNER = 28.dp

/** The rounded tonal surface every section of the statistics screen sits on. */
@Composable
internal fun StatsCard(
	modifier: Modifier = Modifier,
	color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
	content: @Composable ColumnScope.() -> Unit,
) {
	Surface(
		shape = RoundedCornerShape(STATS_CARD_CORNER),
		color = color,
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING),
	) {
		Column(modifier = Modifier.padding(20.dp), content = content)
	}
}

@Composable
internal fun StatsSectionHeader(title: String, trailing: String? = null) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = STATS_PADDING + 4.dp, vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = title,
			style = MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.SemiBold,
			color = MaterialTheme.colorScheme.onSurface,
			modifier = Modifier.weight(1f),
		)
		if (trailing != null) {
			Text(
				text = trailing,
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

/**
 * Small square metric card: icon puck, big value and a quiet label. An optional badge sits opposite
 * the puck — it rides on the icon row rather than under the label so every tile in a grid keeps
 * exactly the same height whether or not it has one.
 */
@Composable
internal fun StatTile(
	value: String,
	label: String,
	icon: Painter,
	accent: Color,
	modifier: Modifier = Modifier,
	badgeIcon: Painter? = null,
	badgeText: String? = null,
) {
	Surface(
		shape = RoundedCornerShape(24.dp),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		modifier = modifier,
	) {
		Column(modifier = Modifier.padding(16.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Box(
					modifier = Modifier
						.size(34.dp)
						.clip(CircleShape)
						.background(accent.copy(alpha = 0.18f)),
					contentAlignment = Alignment.Center,
				) {
					Icon(
						painter = icon,
						contentDescription = null,
						tint = accent,
						modifier = Modifier.size(19.dp),
					)
				}
				if (badgeIcon != null && badgeText != null) {
					Spacer(Modifier.weight(1f))
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(3.dp),
					) {
						Icon(
							painter = badgeIcon,
							contentDescription = null,
							tint = accent,
							modifier = Modifier.size(15.dp),
						)
						Text(
							text = badgeText,
							style = MaterialTheme.typography.labelMedium,
							fontWeight = FontWeight.SemiBold,
							color = accent,
							maxLines = 1,
						)
					}
				}
			}
			Spacer(Modifier.height(12.dp))
			Text(
				text = value,
				style = MaterialTheme.typography.headlineSmall,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
			Text(
				text = label,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

/**
 * Activity chart. Every bucket keeps a full-height track behind it so empty slots still read as
 * "a slot with nothing in it", and any non-zero reading always gets at least a visible stub —
 * tapping a bar selects it and the caller swaps the headline above for that bar's value.
 */
@Composable
internal fun ActivityBarChart(
	buckets: List<StatsBucket>,
	labels: List<String?>,
	selectedIndex: Int,
	onSelect: (Int) -> Unit,
	modifier: Modifier = Modifier,
) {
	val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
	val barColor = MaterialTheme.colorScheme.primary
	val selectedColor = MaterialTheme.colorScheme.tertiary
	val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
	val maxValue = remember(buckets) { buckets.maxOfOrNull { it.duration } ?: 0L }
	val progress by animateFloatAsState(
		targetValue = if (buckets.isEmpty()) 0f else 1f,
		animationSpec = tween(durationMillis = 520),
		label = "barChart",
	)
	val density = LocalDensity.current
	Column(modifier = modifier) {
		Canvas(
			modifier = Modifier
				.fillMaxWidth()
				.height(132.dp)
				.pointerInput(buckets.size) {
					detectTapGestures { offset ->
						if (buckets.isEmpty()) return@detectTapGestures
						val slot = size.width.toFloat() / buckets.size
						val index = (offset.x / slot).toInt().coerceIn(0, buckets.lastIndex)
						onSelect(if (index == selectedIndex) -1 else index)
					}
				},
		) {
			if (buckets.isEmpty()) return@Canvas
			val slot = size.width / buckets.size
			val gap = (slot * 0.28f).coerceAtMost(with(density) { 8.dp.toPx() })
			val barWidth = (slot - gap).coerceAtLeast(2f)
			val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
			val minBar = barWidth.coerceAtMost(size.height)
			buckets.forEachIndexed { index, bucket ->
				val left = index * slot + gap / 2f
				drawRoundRect(
					color = trackColor,
					topLeft = Offset(left, 0f),
					size = Size(barWidth, size.height),
					cornerRadius = radius,
				)
				if (bucket.duration <= 0L || maxValue <= 0L) return@forEachIndexed
				val ratio = (bucket.duration.toFloat() / maxValue).coerceIn(0f, 1f) * progress
				val barHeight = max(minBar, size.height * ratio)
				drawRoundRect(
					color = if (index == selectedIndex) selectedColor else barColor,
					topLeft = Offset(left, size.height - barHeight),
					size = Size(barWidth, barHeight),
					cornerRadius = radius,
				)
			}
		}
		Spacer(Modifier.height(8.dp))
		Row(modifier = Modifier.fillMaxWidth()) {
			labels.forEach { label ->
				Text(
					text = label.orEmpty(),
					style = MaterialTheme.typography.labelSmall,
					color = labelColor,
					textAlign = TextAlign.Center,
					maxLines = 1,
					modifier = Modifier.weight(1f),
				)
			}
		}
	}
}

/**
 * A coherent colour ramp for a ranked list: the theme's primary fading into its tertiary, so the
 * palette always belongs to the user's scheme instead of being a bag of random hues.
 */
@Composable
internal fun rememberRankColors(count: Int): List<Color> {
	val start = MaterialTheme.colorScheme.primary
	val end = MaterialTheme.colorScheme.tertiary
	return remember(count, start, end) {
		List(count) { index ->
			lerp(start, end, if (count <= 1) 0f else index / (count - 1f))
		}
	}
}

/**
 * One title in the top-manga list. The title owns the full width of the row and its numbers sit
 * underneath in two lines — headline metrics first, the quieter detail below — rather than being
 * squeezed into a right-hand column next to a two-line title.
 */
@Composable
internal fun TopMangaRow(
	rank: Int?,
	title: String,
	duration: String,
	percent: String,
	detail: String?,
	accent: Color,
	onClick: (() -> Unit)?,
	cover: (@Composable () -> Unit)?,
) {
	val base = Modifier
		.fillMaxWidth()
		.clip(RoundedCornerShape(20.dp))
	Row(
		modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
			.padding(horizontal = 8.dp, vertical = 12.dp),
		verticalAlignment = Alignment.Top,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Box(
			modifier = Modifier
				.width(22.dp)
				.height(24.dp),
			contentAlignment = Alignment.Center,
		) {
			if (rank != null) {
				Text(
					text = rank.toString(),
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.Bold,
					color = accent,
					textAlign = TextAlign.Center,
				)
			}
		}
		Box(
			modifier = Modifier
				.size(width = 48.dp, height = 66.dp)
				.clip(RoundedCornerShape(14.dp))
				.background(accent.copy(alpha = 0.22f)),
			contentAlignment = Alignment.Center,
		) {
			cover?.invoke()
		}
		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = title,
				style = MaterialTheme.typography.bodyLarge,
				fontWeight = FontWeight.Medium,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
			Spacer(Modifier.height(7.dp))
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Text(
					text = duration,
					style = MaterialTheme.typography.titleSmall,
					fontWeight = FontWeight.Bold,
					color = MaterialTheme.colorScheme.onSurface,
					maxLines = 1,
				)
				Box(
					modifier = Modifier
						.size(4.dp)
						.clip(CircleShape)
						.background(accent),
				)
				Text(
					text = percent,
					style = MaterialTheme.typography.labelLarge,
					fontWeight = FontWeight.SemiBold,
					color = accent,
					maxLines = 1,
				)
			}
			if (detail != null) {
				Spacer(Modifier.height(4.dp))
				Text(
					text = detail,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}
