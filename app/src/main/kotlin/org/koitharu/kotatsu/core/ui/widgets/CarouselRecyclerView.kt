package org.koitharu.kotatsu.core.ui.widgets

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.carousel.CarouselLayoutManager
import com.google.android.material.carousel.CarouselStrategy
import com.google.android.material.carousel.MaskableFrameLayout
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug

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

/**
 * [CarouselLayoutManager] that survives a layout pass too narrow to fit any item.
 *
 * The wide (rail/landscape) layouts lay the carousel out mid-measure at transient widths before the
 * real one arrives. With side padding, the library shrinks every keyline by the padding: a width
 * that leaves no room at all throws "There must be a keyline marked as focal", one that leaves only
 * a few pixels shrinks the focal item below the edge anchor and throws "Keylines after the last
 * focal keyline must be ordered by decreasing masked item size" (e.g. rotating to landscape).
 * That pass is thrown away anyway, so it is skipped; the next pass at the real width recalculates
 * the keylines normally (the failed recalculation never assigned them).
 */
class SafeCarouselLayoutManager(strategy: CarouselStrategy) : CarouselLayoutManager(strategy) {

	override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
		try {
			super.onLayoutChildren(recycler, state)
		} catch (e: RuntimeException) {
			// shortcut: catches the library's keyline checks; drop it if material fixes narrow widths
			if (e !is IllegalStateException && e !is IllegalArgumentException) throw e
			e.printStackTraceDebug()
		}
	}
}

/**
 * Carousel item whose mask also stops short of the carousel's visible edges.
 *
 * Tiles scrolling off the side are cut by whatever clips the carousel (its bounds, the screen), which
 * leaves a straight edge. Shrinking the mask to the visible part, minus the carousel's 4dp tile gap,
 * puts its rounded corners there instead, with the same gap to the edge as between tiles.
 */
class EdgeMaskableFrameLayout @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0,
) : MaskableFrameLayout(context, attrs, defStyleAttr) {

	private val rawMask = RectF()
	private val clampedMask = RectF()
	private val visibleRect = Rect()
	private val edgeGap = 4f * resources.displayMetrics.density
	private var hasRawMask = false

	override fun setMaskRectF(maskRect: RectF) {
		rawMask.set(maskRect)
		hasRawMask = true
		applyMask()
	}

	override fun offsetLeftAndRight(offset: Int) {
		super.offsetLeftAndRight(offset)
		applyMask()
	}

	override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
		super.onLayout(changed, left, top, right, bottom)
		applyMask()
	}

	private fun applyMask() {
		if (!hasRawMask) return
		clampedMask.set(rawMask)
		val carousel = parent as? View
		if (carousel != null && carousel.getLocalVisibleRect(visibleRect)) {
			clampedMask.left = rawMask.left.coerceAtLeast(visibleRect.left + edgeGap - x)
			clampedMask.right = rawMask.right.coerceAtMost(visibleRect.right - edgeGap - x)
				.coerceAtLeast(clampedMask.left)
		}
		if (clampedMask != maskRectF) {
			super.setMaskRectF(clampedMask)
		}
	}
}
