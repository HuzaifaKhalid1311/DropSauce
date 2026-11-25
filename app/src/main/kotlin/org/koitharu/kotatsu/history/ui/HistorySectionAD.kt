package org.koitharu.kotatsu.history.ui

import android.view.ViewGroup
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegate
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.stableMangaCoverKey
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.settings.compose.DropSauceTheme

interface HistorySectionListener {

	fun onSectionClick(section: HistorySection)

	fun onSectionLongClick(section: HistorySection)
}

/** The checked ids while History is in selection mode, read by the section headers. */
@Stable
class HistorySelectionState {
	var ids: Set<Long> by mutableStateOf(emptySet())
}

fun historySectionAD(
	listener: HistorySectionListener,
	selection: HistorySelectionState,
	imageLoader: ImageLoader,
) = adapterDelegate<HistorySection, ListModel>(
	layout = 0,
	layoutInflater = { parent, _ ->
		ComposeView(parent.context).apply {
			layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		}
	},
) {
	// The holder is rebound in place (see HistorySection.getChangePayload), so every change animates.
	var section by mutableStateOf<HistorySection?>(null)
	(itemView as ComposeView).setContent {
		DropSauceTheme {
			// Keyed so a holder reused for another section starts fresh instead of animating the swap.
			section?.let { key(it.key) { SectionHeader(it, selection, imageLoader, listener) } }
		}
	}
	bind { section = item }
}

private val bouncy = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)

@Composable
private fun SectionHeader(
	section: HistorySection,
	selection: HistorySelectionState,
	imageLoader: ImageLoader,
	listener: HistorySectionListener,
) {
	val context = LocalContext.current
	val colors = MaterialTheme.colorScheme
	val motion = MaterialTheme.motionScheme
	val isSelecting = selection.ids.isNotEmpty()
	val selected = if (isSelecting) section.itemIds.count { it in selection.ids } else 0
	val isCollapsed = section.isCollapsed
	val interactionSource = remember { MutableInteractionSource() }
	val isPressed by interactionSource.collectIsPressedAsState()
	val scale by animateFloatAsState(if (isPressed) 0.97f else 1f, bouncy, label = "pressScale")
	// A folded section becomes a tonal pill, so it still reads as "there's more in here".
	val container by animateColorAsState(
		if (isCollapsed) colors.surfaceContainer else Color.Transparent,
		motion.defaultEffectsSpec(),
		label = "container",
	)
	val shape = RoundedCornerShape(24.dp)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 2.dp, vertical = 4.dp)
			.graphicsLayer {
				scaleX = scale
				scaleY = scale
			}
			.clip(shape)
			.background(container)
			.combinedClickable(
				interactionSource = interactionSource,
				indication = ripple(),
				onClickLabel = stringResource(
					when {
						isSelecting && selected >= section.count -> R.string.deselect_all
						isSelecting -> android.R.string.selectAll
						isCollapsed -> R.string.expand
						else -> R.string.collapse
					},
				),
				onClick = { listener.onSectionClick(section) },
				onLongClickLabel = if (isSelecting) null else stringResource(android.R.string.selectAll),
				onLongClick = { listener.onSectionLongClick(section) },
			)
			.heightIn(min = 48.dp)
			.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = remember(section.key) { section.getTitle(context) },
			style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
			fontWeight = FontWeight.SemiBold,
			color = colors.primary,
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
		Spacer(Modifier.width(8.dp))
		CountBadge(section, selected)
		Spacer(Modifier.weight(1f))
		AnimatedVisibility(
			visible = isCollapsed && section.previews.isNotEmpty(),
			enter = fadeIn() + expandHorizontally(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)) +
				scaleIn(bouncy, initialScale = 0.6f),
			exit = fadeOut() + shrinkHorizontally() + scaleOut(targetScale = 0.6f),
		) {
			CoverStack(section.previews, container, imageLoader)
		}
		Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
			AnimatedContent(
				targetState = isSelecting,
				transitionSpec = {
					(scaleIn(bouncy, initialScale = 0.4f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.4f) + fadeOut())
				},
				label = "trailing",
			) { selecting ->
				if (selecting) {
					SelectMark(selected = selected, total = section.count)
				} else {
					Chevron(isCollapsed)
				}
			}
		}
	}
}

@Composable
private fun CountBadge(section: HistorySection, selected: Int) {
	val colors = MaterialTheme.colorScheme
	val isActive = selected > 0
	val container by animateColorAsState(if (isActive) colors.primary else colors.secondaryContainer, label = "badge")
	val content by animateColorAsState(if (isActive) colors.onPrimary else colors.onSecondaryContainer, label = "badgeText")
	// Ordered by what changes most, so the number rolls the right way when it does.
	val value = Triple(selected, section.count, section.isCountExact)
	AnimatedContent(
		targetState = value,
		modifier = Modifier
			.clip(CircleShape)
			.background(container),
		transitionSpec = {
			val up = compareValuesBy(targetState, initialState, { it.first }, { it.second }) > 0
			(slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMedium)) { if (up) it else -it } + fadeIn()) togetherWith
				(slideOutVertically { if (up) -it else it } + fadeOut()) using SizeTransform(clip = true)
		},
		label = "count",
	) { (sel, count, exact) ->
		Text(
			text = when {
				sel > 0 -> "$sel/$count"
				exact -> count.toString()
				else -> "$count+"
			},
			style = MaterialTheme.typography.labelMedium,
			fontWeight = FontWeight.SemiBold,
			color = content,
			maxLines = 1,
			modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
		)
	}
}

/** A few covers from the folded section, overlapping like a small stack. */
@Composable
private fun CoverStack(previews: List<Manga>, ring: Color, imageLoader: ImageLoader) {
	val context = LocalContext.current
	val sizePx = with(LocalDensity.current) { 28.dp.roundToPx() }
	Row(
		horizontalArrangement = Arrangement.spacedBy((-10).dp),
		modifier = Modifier.padding(end = 4.dp),
	) {
		previews.forEachIndexed { index, manga ->
			AsyncImage(
				model = remember(manga.id, manga.coverUrl) {
					ImageRequest.Builder(context)
						.data(manga.coverUrl)
						.size(sizePx)
						.mangaSourceExtra(manga.source)
						.stableMangaCoverKey(manga, manga.coverUrl)
						.build()
				},
				imageLoader = imageLoader,
				contentDescription = null,
				contentScale = ContentScale.Crop,
				modifier = Modifier
					.graphicsLayer { translationY = if (index % 2 == 0) 0f else 2.dp.toPx() } // a loose, hand-stacked look
					.size(28.dp)
					.clip(CircleShape)
					.border(2.dp, ring, CircleShape)
					.background(MaterialTheme.colorScheme.surfaceContainerHighest),
			)
		}
	}
}

@Composable
private fun Chevron(isCollapsed: Boolean) {
	val rotation by animateFloatAsState(
		targetValue = if (isCollapsed) 0f else 180f,
		animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow),
		label = "chevron",
	)
	Box(
		modifier = Modifier
			.size(32.dp)
			.clip(CircleShape)
			.background(MaterialTheme.colorScheme.surfaceContainerHigh),
		contentAlignment = Alignment.Center,
	) {
		Icon(
			painter = painterResource(R.drawable.ic_expand_more),
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier
				.size(22.dp)
				// Rotated while drawing rather than as a layer property: the logs showed the spring running
				// at full frame rate on lower sections while the arrow on screen stayed put.
				.drawWithContent { rotate(rotation) { this@drawWithContent.drawContent() } },
		)
	}
}

/**
 * A ring that fills up as entries of the section get picked (tapping picks the rest); once the
 * whole section is in, it blooms into a filled cookie with a check, echoing Explore's shape morph.
 */
@Composable
private fun SelectMark(selected: Int, total: Int) {
	val colors = MaterialTheme.colorScheme
	val isAll = total > 0 && selected >= total
	val sweep by animateFloatAsState(
		targetValue = if (total > 0) selected.coerceAtMost(total) / total.toFloat() else 0f,
		animationSpec = spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow),
		label = "sweep",
	)
	val bloom by animateFloatAsState(if (isAll) 1f else 0f, spring(0.4f, Spring.StiffnessMediumLow), label = "bloom")
	val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
	val path = remember { Path() }
	val matrix = remember { Matrix() }
	Box(
		modifier = Modifier
			.size(28.dp)
			.drawBehind {
				val ring = 2.5.dp.toPx()
				val inset = ring / 2f + 2.dp.toPx()
				val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
				val fade = 1f - bloom.coerceIn(0f, 1f)
				if (fade > 0f) {
					drawCircle(colors.outlineVariant.copy(alpha = fade), radius = arcSize.minDimension / 2f, style = Stroke(ring))
					drawArc(
						color = colors.primary.copy(alpha = fade),
						startAngle = -90f,
						sweepAngle = 360f * sweep.coerceIn(0f, 1f),
						useCenter = false,
						topLeft = Offset(inset, inset),
						size = arcSize,
						style = Stroke(ring, cap = StrokeCap.Round),
					)
				}
				if (bloom > 0f) {
					path.rewind()
					morph.toPath(bloom.coerceIn(0f, 1f), path)
					matrix.reset()
					val s = size.minDimension * bloom.coerceAtLeast(0f)
					matrix.scale(s, s)
					path.transform(matrix)
					val offset = (size.minDimension - s) / 2f
					path.translate(Offset(offset, offset))
					rotate(bloom * 40f) { drawPath(path, colors.primary) }
				}
			},
		contentAlignment = Alignment.Center,
	) {
		Icon(
			painter = painterResource(R.drawable.ic_check),
			contentDescription = null,
			tint = colors.onPrimary,
			modifier = Modifier
				.size(16.dp)
				.graphicsLayer {
					scaleX = bloom.coerceAtLeast(0f)
					scaleY = bloom.coerceAtLeast(0f)
					alpha = bloom.coerceIn(0f, 1f)
				},
		)
	}
}
