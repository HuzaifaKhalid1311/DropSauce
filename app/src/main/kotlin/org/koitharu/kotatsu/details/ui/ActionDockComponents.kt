package org.koitharu.kotatsu.details.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.rememberHapticEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.details.ui.model.HistoryInfo

@Composable
internal fun ActionDock(
	historyInfo: HistoryInfo,
	isLoading: Boolean,
	accent: Color,
	actions: DetailsExpressiveActions,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier,
		horizontalAlignment = Alignment.End,
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		val chapterCount = historyInfo.totalChapters
		if (chapterCount > 0) {
			ChaptersPill(count = chapterCount, onClick = actions.onChaptersClick)
		}
		ReadFab(historyInfo = historyInfo, isLoading = isLoading, accent = accent, actions = actions)
	}
}

@Composable
internal fun ChaptersPill(count: Int, onClick: () -> Unit) {
	Surface(
		onClick = onClick,
		shape = RoundedCornerShape(50),
		color = MaterialTheme.colorScheme.surfaceContainerHighest,
		tonalElevation = 3.dp,
		shadowElevation = 3.dp,
	) {
		Row(
			modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Icon(
				painter = painterResource(R.drawable.ic_list),
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.size(18.dp),
			)
			Text(
				text = pluralStringResource(R.plurals.chapters, count, count),
				style = MaterialTheme.typography.labelLarge,
				fontWeight = FontWeight.Medium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
			)
		}
	}
}

@Composable
internal fun ReadFab(
	historyInfo: HistoryInfo,
	isLoading: Boolean,
	accent: Color,
	actions: DetailsExpressiveActions,
) {
	val isChaptersLoading = isLoading && (historyInfo.totalChapters <= 0 || historyInfo.isChapterMissing)
	val enabled = !isChaptersLoading && historyInfo.isValid
	val label = when {
		isChaptersLoading -> stringResource(R.string.loading_)
		historyInfo.isIncognitoMode -> stringResource(R.string.incognito)
		historyInfo.canContinue -> stringResource(R.string._continue)
		else -> stringResource(R.string.read)
	}
	val canIncognito = !historyInfo.isIncognitoMode
	val canForget = historyInfo.history != null
	val hasMenu = enabled && (canIncognito || canForget)

	val haptic = rememberHapticEffect()
	var expanded by rememberSaveable { mutableStateOf(false) }
	if (!hasMenu && expanded) {
		expanded = false
	}
	val chevronRotation by animateFloatAsState(
		targetValue = if (expanded) 180f else 0f,
		animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
		label = "fabChevron",
	)

	val container = if (enabled) accent else accent.copy(alpha = 0.4f)
	val baseContent = if (accent.luminanceIsLight()) Color.Black else Color.White
	val onColor = if (enabled) baseContent else baseContent.copy(alpha = 0.7f)

	Surface(
		shape = RoundedCornerShape(24.dp),
		color = container,
		shadowElevation = 6.dp,
	) {
		Box(modifier = Modifier.animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))) {
		Column(
			modifier = Modifier.width(IntrinsicSize.Max),
		) {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.height(56.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Row(
					modifier = Modifier
						.clickable(enabled = enabled) {
							haptic(HapticEffect.CLICK)
							expanded = false
							actions.onReadClick()
						}
						.fillMaxHeight()
						.weight(1f)
						.padding(start = 22.dp, end = if (hasMenu) 14.dp else 24.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(10.dp),
				) {
					Icon(
						painter = painterResource(R.drawable.ic_play),
						contentDescription = null,
						tint = onColor,
						modifier = Modifier.size(28.dp),
					)
					Text(
						text = label,
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.SemiBold,
						color = onColor,
						maxLines = 1,
					)
				}
				if (hasMenu) {
					Row(
						modifier = Modifier
							.clickable { expanded = !expanded }
							.fillMaxHeight()
							.padding(end = 16.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Box(
							modifier = Modifier
								.width(1.dp)
								.height(24.dp)
								.background(onColor.copy(alpha = 0.3f)),
						)
						Spacer(Modifier.width(12.dp))
						Icon(
							painter = painterResource(R.drawable.ic_expand_more),
							contentDescription = stringResource(R.string.show_menu),
							tint = onColor,
							modifier = Modifier
								.size(22.dp)
								.rotate(chevronRotation),
						)
					}
				}
			}
			if (expanded) {
				Box(
					modifier = Modifier
						.padding(horizontal = 16.dp)
						.fillMaxWidth()
						.height(1.dp)
						.background(onColor.copy(alpha = 0.22f)),
				)
				if (canIncognito) {
					FabMenuRow(iconRes = R.drawable.ic_incognito, iconSize = 26.dp, label = stringResource(R.string.incognito_mode), color = onColor) {
						expanded = false
						actions.onIncognitoClick()
					}
				}
				if (canForget) {
					FabMenuRow(iconRes = R.drawable.ic_delete, label = stringResource(R.string.remove_from_history), color = onColor) {
						expanded = false
						actions.onForgetHistoryClick()
					}
				}
			}
		}
		}
	}
}

@Composable
internal fun FabMenuRow(
	@DrawableRes iconRes: Int,
	label: String,
	color: Color,
	iconSize: Dp = 20.dp,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(horizontal = 20.dp, vertical = 14.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(14.dp),
	) {
		Icon(
			painter = painterResource(iconRes),
			contentDescription = null,
			tint = color,
			modifier = Modifier.size(iconSize),
		)
		Text(
			text = label,
			style = MaterialTheme.typography.bodyLarge,
			color = color,
			maxLines = 1,
		)
	}
}
