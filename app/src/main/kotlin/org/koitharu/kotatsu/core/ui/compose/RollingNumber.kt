package org.koitharu.kotatsu.core.ui.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/** A number that rolls in from below when it grows and from above when it shrinks. */
@Composable
fun RollingNumber(
	value: Int,
	style: TextStyle,
	color: Color,
	modifier: Modifier = Modifier,
	fontWeight: FontWeight? = null,
	text: String = value.toString(),
) {
	AnimatedContent(
		targetState = value to text,
		modifier = modifier,
		transitionSpec = {
			val up = targetState.first >= initialState.first
			(slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMedium)) { if (up) it else -it } + fadeIn()) togetherWith
				(slideOutVertically { if (up) -it else it } + fadeOut()) using SizeTransform(clip = true)
		},
		label = "rollingNumber",
	) { (_, label) ->
		Text(
			text = label,
			style = style,
			fontWeight = fontWeight,
			color = color,
			maxLines = 1,
			softWrap = false,
		)
	}
}
