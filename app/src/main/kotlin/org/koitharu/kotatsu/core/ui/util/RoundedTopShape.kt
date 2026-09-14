package org.koitharu.kotatsu.core.ui.util

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Clip shape for a scrolling container: rounds only the top corners, so content disappearing under
 * the toolbar is cut with the same radius as the content's own cards instead of a flat line.
 * [inset] pulls the rounded edges in to where the cards start — as clipping rather than padding, so
 * the content itself is not moved — and the shape runs past the bottom edge to keep it square.
 *
 * The View equivalent is `View.roundTopCorners`.
 */
class RoundedTopShape(
	private val radius: Dp,
	private val inset: Dp = 0.dp,
) : Shape {

	override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
		val radiusPx = with(density) { radius.toPx() }
		val insetPx = with(density) { inset.toPx() }
		val corner = CornerRadius(radiusPx)
		return Outline.Rounded(
			RoundRect(
				left = insetPx,
				top = 0f,
				right = size.width - insetPx,
				bottom = size.height + radiusPx,
				topLeftCornerRadius = corner,
				topRightCornerRadius = corner,
				bottomLeftCornerRadius = CornerRadius.Zero,
				bottomRightCornerRadius = CornerRadius.Zero,
			),
		)
	}
}
