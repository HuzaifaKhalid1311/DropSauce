package org.koitharu.kotatsu.list.ui

import android.content.res.Resources
import org.koitharu.kotatsu.R
import kotlin.math.roundToInt

/**
 * The grid size setting is stored as a scale (`grid_size`, percent) but picked as a number of items
 * per row. Each stop maps to the scale that gives exactly that many columns on the current list width,
 * so a saved scale keeps meaning the same cover size on every screen, as it always has.
 */
object GridColumns {

	/** The old slider's floor; the stop with the most columns sits here. */
	private const val MIN_SCALE = 0.5f

	/** The old slider's ceiling. Two columns stayed the minimum up to it, so they still do. */
	private const val SINGLE_COLUMN_SCALE = 1.5f

	/** Width of the most recently laid-out manga list, in px; 0 until one has been shown. */
	@Volatile
	var lastGridWidth: Int = 0

	fun spanCount(width: Int, cellWidth: Float, scale: Float): Int {
		val minSpanCount = if (scale > SINGLE_COLUMN_SCALE) 1 else 2
		return (width / cellWidth).roundToInt().coerceAtLeast(minSpanCount)
	}

	fun referenceWidth(resources: Resources): Int =
		lastGridWidth.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels

	fun maxColumns(resources: Resources, width: Int): Int =
		spanCount(width, cellWidth(resources, MIN_SCALE), MIN_SCALE)

	fun columnsFor(resources: Resources, width: Int, scale: Float): Int =
		spanCount(width, cellWidth(resources, scale), scale).coerceIn(1, maxColumns(resources, width))

	/** Grid size in percent whose cells divide [width] into exactly [columns]. */
	fun gridSizeFor(resources: Resources, width: Int, columns: Int): Int {
		val gridWidth = resources.getDimension(R.dimen.preferred_grid_width)
		val spacing = resources.getDimension(R.dimen.grid_spacing)
		val scale = (width.toFloat() / columns - spacing) / gridWidth
		// Rounded down: a cell even a pixel wider than the list would fail GridSpanResolver's width check.
		return (scale * 100).toInt().coerceAtLeast((MIN_SCALE * 100).roundToInt())
	}

	private fun cellWidth(resources: Resources, scale: Float): Float =
		resources.getDimension(R.dimen.preferred_grid_width) * scale + resources.getDimension(R.dimen.grid_spacing)
}
