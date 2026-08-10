package org.koitharu.kotatsu.core.ui.list

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.core.view.ancestors
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

class FitHeightGridLayoutManager : GridLayoutManager {

	constructor(context: Context?, spanCount: Int) : super(context, spanCount)

	constructor(
		context: Context?,
		attrs: AttributeSet?,
		defStyleAttr: Int,
		defStyleRes: Int,
	) : super(context, attrs, defStyleAttr, defStyleRes)

	constructor(
		context: Context?,
		spanCount: Int,
		orientation: Int,
		reverseLayout: Boolean,
	) : super(context, spanCount, orientation, reverseLayout)

	/** The pager hosting this list, if any — see [onLayoutChildren]. */
	private var pager: ViewPager2? = null

	override fun onAttachedToWindow(view: RecyclerView) {
		super.onAttachedToWindow(view)
		pager = view.ancestors.filterIsInstance<ViewPager2>().firstOrNull()
	}

	override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
		pager = null
		super.onDetachedFromWindow(view, recycler)
	}

	/**
	 * ViewPager2 lays its pages out from inside its own measure pass, and in the nav-rail layouts
	 * (phone landscape, tablet) that runs twice with intermediate widths — first 0, then a fraction
	 * of the real one — before the correct pass arrives. Laid out that narrow, the whole grid fits
	 * the viewport, so RecyclerView clamps the scroll position to 0; the correct pass then restores
	 * the width but not the position. Every cover that finished loading triggered a layout, so
	 * while scrolling the list snapped back to the top continuously.
	 *
	 * A page is always exactly as wide as its pager, so anything narrower is one of those
	 * transients: leave the children where they are and let the correct pass do the work.
	 */
	override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
		val pagerWidth = pager?.measuredWidth ?: 0
		if (pagerWidth > 0 && width < pagerWidth) {
			return
		}
		super.onLayoutChildren(recycler, state)
	}

	override fun layoutDecoratedWithMargins(child: View, left: Int, top: Int, right: Int, bottom: Int) {
		if (orientation == RecyclerView.VERTICAL && child.layoutParams.height == LayoutParams.MATCH_PARENT) {
			val parentBottom = height - paddingBottom
			val offset = parentBottom - bottom
			super.layoutDecoratedWithMargins(child, left, top, right, bottom + offset)
		} else {
			super.layoutDecoratedWithMargins(child, left, top, right, bottom)
		}
	}
}
