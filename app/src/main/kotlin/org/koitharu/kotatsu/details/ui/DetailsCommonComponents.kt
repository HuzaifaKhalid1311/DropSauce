package org.koitharu.kotatsu.details.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R

internal val SCREEN_PADDING = 20.dp
internal val CARD_CORNER = 26.dp
internal const val TAGS_COLLAPSED_ROWS = 3
internal val DETAIL_DOCK_RESERVE = 128.dp

internal fun Color.luminanceIsLight(): Boolean =
	(0.299f * red + 0.587f * green + 0.114f * blue) > 0.5f

@Composable
internal fun SectionCard(
	onClick: (() -> Unit)? = null,
	modifier: Modifier = Modifier,
	content: @Composable ColumnScope.() -> Unit,
) {
	val base = Modifier
		.fillMaxWidth()
		.padding(horizontal = SCREEN_PADDING, vertical = 8.dp)
		.then(modifier)
	Surface(
		shape = RoundedCornerShape(CARD_CORNER),
		color = MaterialTheme.colorScheme.surfaceContainerHigh,
		modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
	) {
		Column(modifier = Modifier.padding(20.dp), content = content)
	}
}

@Composable
internal fun SectionHeader(title: String, action: String, accent: Color, onAction: () -> Unit) {
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
internal fun Pill(
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
internal fun LoadingHero() {
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
