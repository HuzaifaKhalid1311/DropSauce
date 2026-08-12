package org.koitharu.kotatsu.core.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * A horizontal carousel that always takes its width from its container, never from its content.
 *
 * A [com.google.android.material.carousel.CarouselLayoutManager] derives its keylines — and so its
 * scroll range — from the width of its container. If the RecyclerView is ever measured without an
 * exact width it auto-measures itself to its children instead, computes keylines for that width, and
 * decides everything fits: the scroll extent comes out larger than the scroll range, so it is not
 * scrollable and it re-clamps its scroll offset to the start. Worse, the row above then adopts that
 * content width as its own and hands it back as an exact spec, so the bogus width grows frame after
 * frame (observed: 2568 → 2949 → 5718 in the wide layouts, three passes per frame while dragging).
 * The carousel could never scroll, so the parent scroll view took the gesture and moved vertically
 * instead — the flicker.
 *
 * Forcing the width from the parent cuts that loop at its only entry point: the width is now the same
 * value a `match_parent` child is supposed to get, negative margins included.
 */
class CarouselRecyclerView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = androidx.recyclerview.R.attr.recyclerViewStyle,
) : RecyclerView(context, attrs, defStyleAttr) {

	override fun onMeasure(widthSpec: Int, heightSpec: Int) {
		super.onMeasure(containerWidthSpec() ?: widthSpec, heightSpec)
	}

	/** The width a `match_parent` child of our container gets, or `null` before the container is laid out. */
	private fun containerWidthSpec(): Int? {
		val container = parent as? ViewGroup ?: return null
		val available = container.width - container.paddingLeft - container.paddingRight
		if (available <= 0) {
			return null
		}
		val lp = layoutParams as? MarginLayoutParams
		val width = available - (lp?.leftMargin ?: 0) - (lp?.rightMargin ?: 0)
		return MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), MeasureSpec.EXACTLY)
	}
}
