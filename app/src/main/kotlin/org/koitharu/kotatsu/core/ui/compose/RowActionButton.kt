package org.koitharu.kotatsu.core.ui.compose

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getThemeColor

@Composable
fun topBarIconColor(): Color {
	val context = LocalContext.current
	val uiMode = LocalConfiguration.current.uiMode
	return remember(context, uiMode) { Color(context.getThemeColor(R.attr.colorTopBarIcon)) }
}

/**
 * Shared `40dp` neutral tonal icon button used at the trailing end of list rows on Explore and Feed.
 */
@Composable
fun RowActionButton(
	@DrawableRes icon: Int,
	@StringRes description: Int,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	shape: Shape = CircleShape,
) {
	Surface(
		onClick = onClick,
		shape = shape,
		color = MaterialTheme.colorScheme.surfaceContainerHighest,
		modifier = modifier.size(40.dp),
	) {
		Box(contentAlignment = Alignment.Center) {
			Icon(
				painter = painterResource(icon),
				contentDescription = stringResource(description),
				tint = topBarIconColor(),
				modifier = Modifier.size(20.dp),
			)
		}
	}
}
