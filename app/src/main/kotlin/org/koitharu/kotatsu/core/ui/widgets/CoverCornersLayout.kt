package org.koitharu.kotatsu.core.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.isGone
import androidx.core.view.marginBottom
import androidx.core.view.marginEnd
import androidx.core.view.marginStart
import androidx.core.view.marginTop

/**
 * Top strip of a manga cover: child 0 sits top-start (saved/favourite pill), child 1 top-end
 * (pin, progress + new-chapters column). When the cover is too narrow for both side by side, the
 * start child drops right below the end child instead of overlapping it.
 */
class CoverCornersLayout @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

	private var isStacked = false

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		super.onMeasure(widthMeasureSpec, heightMeasureSpec)
		val start = getChildAt(0)
		val end = getChildAt(1)
		isStacked = !start.isGone && !end.isGone && end.measuredWidth > 0 &&
			start.outerWidth + end.outerWidth > measuredWidth - paddingLeft - paddingRight
		if (isStacked) {
			val stackedHeight = paddingTop + end.outerHeight + start.outerHeight + paddingBottom
			setMeasuredDimension(measuredWidth, maxOf(measuredHeight, stackedHeight))
		}
	}

	override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
		super.onLayout(changed, left, top, right, bottom)
		if (isStacked) {
			val start = getChildAt(0)
			val end = getChildAt(1)
			start.offsetTopAndBottom(end.bottom + end.marginBottom + start.marginTop - start.top)
		}
	}

	private val View.outerWidth get() = measuredWidth + marginStart + marginEnd
	private val View.outerHeight get() = measuredHeight + marginTop + marginBottom
}
