package org.koitharu.kotatsu.core.ui.list.fastscroll

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.annotation.AttrRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R

class FastScrollRecyclerView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	@AttrRes defStyleAttr: Int = androidx.recyclerview.R.attr.recyclerViewStyle,
) : RecyclerView(context, attrs, defStyleAttr) {

	val fastScroller = FastScroller(context, attrs)

	var isFastScrollerEnabled: Boolean = true
		set(value) {
			field = value
			fastScroller.isVisible = value && isVisible
		}

	init {
		fastScroller.id = R.id.fast_scroller
		fastScroller.layoutParams = ViewGroup.LayoutParams(
			LayoutParams.WRAP_CONTENT,
			LayoutParams.MATCH_PARENT,
		)
	}

	override fun setAdapter(adapter: Adapter<*>?) {
		super.setAdapter(adapter)
		fastScroller.setSectionIndexer(adapter as? FastScroller.SectionIndexer)
	}

	override fun setVisibility(visibility: Int) {
		super.setVisibility(visibility)
		fastScroller.visibility = if (isFastScrollerEnabled) visibility else GONE
	}

	override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
		super.setPadding(left, top, right, bottom)
		fastScroller.setPadding(left, top, right, bottom)
	}

	override fun setPaddingRelative(start: Int, top: Int, end: Int, bottom: Int) {
		super.setPaddingRelative(start, top, end, bottom)
		fastScroller.setPaddingRelative(start, top, end, bottom)
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		fastScroller.attachRecyclerView(this)
	}

	override fun onDetachedFromWindow() {
		fastScroller.detachRecyclerView()
		super.onDetachedFromWindow()
	}
}
