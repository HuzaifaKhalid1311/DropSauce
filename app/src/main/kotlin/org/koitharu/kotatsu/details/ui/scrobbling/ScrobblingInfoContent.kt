package org.koitharu.kotatsu.details.ui.scrobbling

import android.view.Gravity
import android.view.View
import androidx.appcompat.widget.PopupMenu
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.ImageLoader
import coil3.compose.AsyncImage
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.sheet.SheetContentPadding
import org.koitharu.kotatsu.core.ui.sheet.SheetSection
import org.koitharu.kotatsu.core.util.ext.adjustPopupMenuIcons
import org.koitharu.kotatsu.core.util.ext.setOptionalIconsVisibleCompat
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import kotlin.math.abs
import kotlin.math.ceil

/** Stars the rating row draws; the tracker itself stores a 0f..1f fraction. */
const val MAX_STARS = 5f

private val STAR_SIZE = 32.dp
private const val DESCRIPTION_COLLAPSED_LINES = 6
private const val STATUS_COLUMNS = 3

/**
 * What one linked tracker knows about this title, and the two things worth changing from here:
 * the rating and the reading status. Everything else — chapter progress, the remote description —
 * is the tracker's own word for it and is shown read-only.
 */
@Composable
fun ScrobblingInfoContent(
	info: ScrobblingInfo,
	imageLoader: ImageLoader,
	bottomInset: Dp,
	onRatingChange: (Float) -> Unit,
	onStatusChange: (ScrobblingStatus) -> Unit,
	onCoverClick: () -> Unit,
	onOpenInBrowser: () -> Unit,
	onEdit: () -> Unit,
	onUnregister: () -> Unit,
) {
	var localRating by remember { mutableFloatStateOf(info.rating * MAX_STARS) }
	// The value this sheet last sent. Until the tracker echoes it back, incoming values are either
	// our own round trip or a stale write racing it — adopting those is what made the stars jump
	// back to the old rating mid-drag.
	var sentRating by remember { mutableStateOf<Float?>(null) }
	LaunchedEffect(info.rating) {
		val incoming = info.rating * MAX_STARS
		val pending = sentRating
		if (pending == null) {
			localRating = incoming
		} else if (abs(incoming - pending) < 0.01f) {
			sentRating = null
		}
	}
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.verticalScroll(rememberScrollState())
			.padding(bottom = bottomInset + 20.dp),
	) {
		HeroRow(
			info = info,
			imageLoader = imageLoader,
			rating = localRating,
			onRatingChange = { localRating = it },
			// Only a finished gesture is worth a network round trip; committing every pixel of a
			// drag floods the tracker with writes that then land out of order.
			onRatingCommit = {
				sentRating = localRating
				onRatingChange(localRating / MAX_STARS)
			},
			onCoverClick = onCoverClick,
			onOpenInBrowser = onOpenInBrowser,
			onEdit = onEdit,
			onUnregister = onUnregister,
		)
		SheetSection(title = stringResource(R.string.status)) {
			StatusGrid(
				current = info.status,
				onSelect = onStatusChange,
				modifier = Modifier.padding(horizontal = SheetContentPadding),
			)
		}
		val description = info.description?.toString()?.trim()
		if (!description.isNullOrEmpty()) {
			SheetSection(title = stringResource(R.string.description)) {
				var expanded by remember(info.targetId) { mutableStateOf(false) }
				Text(
					text = description,
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_COLLAPSED_LINES,
					overflow = TextOverflow.Ellipsis,
					modifier = Modifier
						.fillMaxWidth()
						.clickable { expanded = !expanded }
						.padding(horizontal = SheetContentPadding, vertical = 4.dp)
						.animateContentSize(),
				)
			}
		}
	}
}

/**
 * Cover, which tracker this is, the remote title, its progress and the rating — the rating sits
 * here rather than in a titled section of its own so the whole of one tracker's state reads as a
 * single block beside the cover.
 */
@Composable
private fun HeroRow(
	info: ScrobblingInfo,
	imageLoader: ImageLoader,
	rating: Float,
	onRatingChange: (Float) -> Unit,
	onRatingCommit: () -> Unit,
	onCoverClick: () -> Unit,
	onOpenInBrowser: () -> Unit,
	onEdit: () -> Unit,
	onUnregister: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = SheetContentPadding, vertical = 4.dp),
		verticalAlignment = Alignment.Top,
	) {
		AsyncImage(
			model = info.coverUrl,
			imageLoader = imageLoader,
			contentDescription = null,
			contentScale = ContentScale.Crop,
			modifier = Modifier
				.size(92.dp, 127.dp)
				.clip(RoundedCornerShape(16.dp))
				.clickable(onClick = onCoverClick),
		)
		Spacer(Modifier.width(16.dp))
		Column(modifier = Modifier.weight(1f)) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Row(
					modifier = Modifier.weight(1f),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.spacedBy(6.dp),
				) {
					Icon(
						painter = painterResource(
							// The raster Shikimori logo has its own colours; tinting it flattens them.
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
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
					)
				}
				OverflowMenu(
					onOpenInBrowser = onOpenInBrowser,
					onEdit = onEdit,
					onUnregister = onUnregister,
				)
			}
			Text(
				text = info.title,
				style = MaterialTheme.typography.titleLarge,
				fontWeight = FontWeight.SemiBold,
				color = MaterialTheme.colorScheme.onSurface,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
			if (info.chapter > 0) {
				Spacer(Modifier.height(6.dp))
				Text(
					text = if (info.totalChapters > 0) {
						stringResource(R.string.chapters_read_d_of_d, info.chapter, info.totalChapters)
					} else {
						stringResource(R.string.chapters_read_d, info.chapter)
					},
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			Spacer(Modifier.height(6.dp))
			Row(verticalAlignment = Alignment.CenterVertically) {
				RatingStars(
					rating = rating,
					onRatingChange = onRatingChange,
					onRatingCommit = onRatingCommit,
				)
				Spacer(Modifier.width(10.dp))
				Text(
					text = formatRating(rating),
					style = MaterialTheme.typography.titleMedium,
					color = MaterialTheme.colorScheme.primary,
				)
			}
		}
	}
}

/**
 * The six reading states as a grid of tiles rather than a chip row: at this count chips wrap into
 * a ragged block that hides which states even exist, while an even grid shows all six at once and
 * gives each a target big enough to hit without looking.
 */
@Composable
private fun StatusGrid(
	current: ScrobblingStatus?,
	onSelect: (ScrobblingStatus) -> Unit,
	modifier: Modifier = Modifier,
) {
	FlowRow(
		modifier = modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
		maxItemsInEachRow = STATUS_COLUMNS,
	) {
		ScrobblingStatus.entries.forEach { status ->
			val isSelected = status == current
			val background by animateColorAsState(
				targetValue = if (isSelected) {
					MaterialTheme.colorScheme.primary
				} else {
					MaterialTheme.colorScheme.surfaceContainerHigh
				},
				label = "status_bg_${status.name}",
			)
			val contentColor by animateColorAsState(
				targetValue = if (isSelected) {
					MaterialTheme.colorScheme.onPrimary
				} else {
					MaterialTheme.colorScheme.onSurfaceVariant
				},
				label = "status_fg_${status.name}",
			)
			Column(
				modifier = Modifier
					.weight(1f)
					.clip(RoundedCornerShape(20.dp))
					.background(background)
					.clickable { onSelect(status) }
					.padding(vertical = 12.dp, horizontal = 6.dp),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.Center,
			) {
				Icon(
					painter = painterResource(status.iconResId),
					contentDescription = null,
					tint = contentColor,
					modifier = Modifier.size(24.dp),
				)
				Spacer(Modifier.height(6.dp))
				Text(
					text = stringResource(status.labelResId),
					style = MaterialTheme.typography.labelMedium,
					fontWeight = FontWeight.Medium,
					color = contentColor,
					textAlign = TextAlign.Center,
					maxLines = 2,
					overflow = TextOverflow.Ellipsis,
				)
			}
		}
	}
}

@Composable
private fun OverflowMenu(
	onOpenInBrowser: () -> Unit,
	onEdit: () -> Unit,
	onUnregister: () -> Unit,
) {
	// A real View to hang the menu off: Compose has no window token of its own to anchor to.
	val anchor = remember { mutableStateOf<View?>(null) }
	Box(contentAlignment = Alignment.Center) {
		AndroidView(
			factory = { View(it) },
			update = { anchor.value = it },
			modifier = Modifier.size(40.dp),
		)
		IconButton(
			onClick = {
				anchor.value?.let { showOverflowMenu(it, onOpenInBrowser, onEdit, onUnregister) }
			},
			colors = IconButtonDefaults.filledTonalIconButtonColors(),
			modifier = Modifier.size(40.dp),
		) {
			Icon(
				painter = painterResource(R.drawable.ic_more_vert),
				contentDescription = stringResource(R.string.more),
				modifier = Modifier.size(dimensionResource(R.dimen.top_bar_action_icon_size)),
			)
		}
	}
}

/**
 * The platform [PopupMenu] rather than Compose's `DropdownMenu`, so this overflow looks and behaves
 * exactly like every other three-dot menu in the app — the theme styles it, and
 * [adjustPopupMenuIcons] gives it the same icon metrics.
 */
private fun showOverflowMenu(
	anchor: View,
	onOpenInBrowser: () -> Unit,
	onEdit: () -> Unit,
	onUnregister: () -> Unit,
) {
	PopupMenu(anchor.context, anchor, Gravity.END).apply {
		inflate(R.menu.opt_scrobbling)
		setForceShowIcon(true)
		menu.setOptionalIconsVisibleCompat(true)
		menu.adjustPopupMenuIcons(anchor.resources)
		setOnMenuItemClickListener { item ->
			when (item.itemId) {
				R.id.action_browser -> onOpenInBrowser()
				R.id.action_edit -> onEdit()
				R.id.action_unregister -> onUnregister()
				else -> return@setOnMenuItemClickListener false
			}
			true
		}
		show()
	}
}

/**
 * Five-star row editable by tap or drag in half-star steps. [onRatingChange] fires throughout the
 * gesture for the drawing, [onRatingCommit] once it ends, for the write.
 */
@Composable
private fun RatingStars(
	rating: Float,
	onRatingChange: (Float) -> Unit,
	onRatingCommit: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val currentOnChange by rememberUpdatedState(onRatingChange)
	val currentOnCommit by rememberUpdatedState(onRatingCommit)
	var rowWidth by remember { mutableIntStateOf(0) }
	val star = painterResource(R.drawable.ic_star_rate)
	val filledColor = colorResource(R.color.common_yellow)
	val emptyColor = MaterialTheme.colorScheme.outlineVariant
	val pick: (Float) -> Unit = { x ->
		if (rowWidth > 0) {
			// Round up to the next half star so the star under the finger reads as filled.
			val stars = x / rowWidth * MAX_STARS
			currentOnChange((ceil(stars * 2f) / 2f).coerceIn(0.5f, MAX_STARS))
		}
	}
	Row(
		modifier = modifier
			.onSizeChanged { rowWidth = it.width }
			.pointerInput(Unit) {
				detectTapGestures {
					pick(it.x)
					currentOnCommit()
				}
			}
			.pointerInput(Unit) {
				detectHorizontalDragGestures(
					onDragEnd = { currentOnCommit() },
					onDragCancel = { currentOnCommit() },
				) { change, _ -> pick(change.position.x) }
			},
	) {
		repeat(MAX_STARS.toInt()) { index ->
			val fraction = (rating - index).coerceIn(0f, 1f)
			Box(modifier = Modifier.size(STAR_SIZE)) {
				Icon(
					painter = star,
					contentDescription = null,
					tint = emptyColor,
					modifier = Modifier.fillMaxSize(),
				)
				if (fraction > 0f) {
					// A half star is the filled star clipped to the covered fraction.
					Icon(
						painter = star,
						contentDescription = null,
						tint = filledColor,
						modifier = Modifier
							.fillMaxSize()
							.drawWithContent {
								clipRect(right = size.width * fraction) {
									this@drawWithContent.drawContent()
								}
							},
					)
				}
			}
		}
	}
}

private fun formatRating(stars: Float): String = if (stars <= 0f) {
	"–"
} else {
	"%.1f".format(stars)
}
