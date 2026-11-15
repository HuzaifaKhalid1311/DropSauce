package org.koitharu.kotatsu.favourites.ui.container

import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import org.koitharu.kotatsu.R

/**
 * Compose port of the `item_empty_state` layout as the Favourites tab used it: mascot, title,
 * explanation and a tonal "Manage" button. Sizes and spacing mirror the XML one to one.
 */
@Composable
fun FavouritesEmptyState(
	topPadding: Dp,
	onManageClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier
			.fillMaxSize()
			.padding(start = 32.dp, end = 32.dp, top = topPadding),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Center,
	) {
		ThemedDrawable(
			res = R.drawable.ic_empty_favourites,
			size = 192.dp,
		)
		Spacer(Modifier.height(16.dp))
		Text(
			text = stringResource(R.string.text_empty_holder_primary),
			style = MaterialTheme.typography.titleLarge,
			color = MaterialTheme.colorScheme.onSurface,
			textAlign = TextAlign.Center,
		)
		Spacer(Modifier.height(16.dp))
		Text(
			text = stringResource(R.string.empty_favourite_categories),
			style = MaterialTheme.typography.bodyMedium,
			// The XML TextView had no explicit colour, i.e. the theme's primary text colour.
			color = MaterialTheme.colorScheme.onSurface,
			textAlign = TextAlign.Center,
		)
		Spacer(Modifier.height(16.dp))
		FilledTonalButton(onClick = onManageClick) {
			Text(text = stringResource(R.string.manage))
		}
	}
}

/**
 * The empty-state art tints itself with `?colorOnSurface`, so it is inflated through the view's
 * themed context (exactly as the old CoilImageView did) and drawn fit-centre in a square box.
 */
@Composable
private fun ThemedDrawable(@DrawableRes res: Int, size: Dp) {
	val context = LocalContext.current
	val sizePx = with(LocalDensity.current) { size.roundToPx() }
	val bitmap = remember(context, res, sizePx) {
		val drawable = AppCompatResources.getDrawable(context, res) ?: return@remember null
		val w = drawable.intrinsicWidth
		val h = drawable.intrinsicHeight
		if (w <= 0 || h <= 0) {
			drawable.toBitmap(sizePx, sizePx)
		} else {
			val scale = sizePx.toFloat() / maxOf(w, h)
			drawable.toBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
		}.asImageBitmap()
	}
	if (bitmap != null) {
		Image(
			bitmap = bitmap,
			contentDescription = null,
			contentScale = ContentScale.Fit,
			modifier = Modifier.size(size),
		)
	} else {
		Spacer(Modifier.size(size))
	}
}
