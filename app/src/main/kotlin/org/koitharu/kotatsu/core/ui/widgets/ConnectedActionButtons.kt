package org.koitharu.kotatsu.core.ui.widgets

import android.view.ViewGroup
import androidx.core.view.children
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.shape.CornerFamily

/**
 * Shapes a row's connected action buttons (a `MaterialButtonGroup`) by hand: round outer ends, tight
 * inner joins, counted over the visible buttons only, so hiding one never leaves a square end.
 */
fun ViewGroup.applyConnectedActionShapes(outerCornerSize: Float, innerCornerSize: Float) {
	val buttons = children.filterIsInstance<MaterialButton>().filter { it.isVisible }.toList()
	buttons.forEachIndexed { index, button ->
		button.applyConnectedActionShape(
			isFirst = index == 0,
			isLast = index == buttons.lastIndex,
			outerCornerSize = outerCornerSize,
			innerCornerSize = innerCornerSize,
		)
	}
}

private fun MaterialButton.applyConnectedActionShape(
	isFirst: Boolean,
	isLast: Boolean,
	outerCornerSize: Float,
	innerCornerSize: Float,
) {
	val leftCornerSize = if (isFirst) outerCornerSize else innerCornerSize
	val rightCornerSize = if (isLast) outerCornerSize else innerCornerSize
	shapeAppearanceModel = shapeAppearanceModel.toBuilder()
		.setTopLeftCorner(CornerFamily.ROUNDED, leftCornerSize)
		.setBottomLeftCorner(CornerFamily.ROUNDED, leftCornerSize)
		.setTopRightCorner(CornerFamily.ROUNDED, rightCornerSize)
		.setBottomRightCorner(CornerFamily.ROUNDED, rightCornerSize)
		.build()
}
