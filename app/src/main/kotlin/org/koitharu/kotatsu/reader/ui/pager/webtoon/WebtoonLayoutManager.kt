package org.koitharu.kotatsu.reader.ui.pager.webtoon

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

@Suppress("unused")
class WebtoonLayoutManager : LinearLayoutManager {

	constructor(context: Context) : super(context)
	constructor(
		context: Context,
		orientation: Int,
		reverseLayout: Boolean,
	) : super(context, orientation, reverseLayout)

	constructor(
		context: Context,
		attrs: AttributeSet?,
		defStyleAttr: Int,
		defStyleRes: Int,
	) : super(context, attrs, defStyleAttr, defStyleRes)

	override fun calculateExtraLayoutSpace(state: RecyclerView.State, extraLayoutSpace: IntArray) {
		if (state.hasTargetScrollPosition()) {
			super.calculateExtraLayoutSpace(state, extraLayoutSpace)
			return
		}
		// A screen of pages stays laid out (and decoded) on both sides: with only the scroll direction
		// covered, the page just left was recycled at once and showed black while it decoded again
		// as soon as the scroll turned back.
		extraLayoutSpace.fill(height)
	}
}
