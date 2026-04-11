package org.koitharu.kotatsu.core.util.ext

import android.animation.ValueAnimator
import androidx.core.view.doOnNextLayout
import androidx.core.view.isEmpty
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.hannesdorfmann.adapterdelegates4.dsl.AdapterDelegateViewBindingViewHolder
import com.hannesdorfmann.adapterdelegates4.dsl.AdapterDelegateViewHolder

fun RecyclerView.removeItemDecoration(cls: Class<out RecyclerView.ItemDecoration>) {
	repeat(itemDecorationCount) { i ->
		if (cls.isInstance(getItemDecorationAt(i))) {
			removeItemDecorationAt(i)
			return
		}
	}
}

var RecyclerView.firstVisibleItemPosition: Int
	get() = (layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition()
		?: RecyclerView.NO_POSITION
	set(value) {
		if (value != RecyclerView.NO_POSITION) {
			(layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(value, 0)
		}
	}

val RecyclerView.visibleItemCount: Int
	get() = (layoutManager as? LinearLayoutManager)?.run {
		findLastVisibleItemPosition() - findFirstVisibleItemPosition()
	} ?: 0

fun <T> RecyclerView.ViewHolder.getItem(clazz: Class<T>): T? {
	val rawItem = when (this) {
		is AdapterDelegateViewBindingViewHolder<*, *> -> item
		is AdapterDelegateViewHolder<*> -> item
		else -> null
	} ?: return null
	return if (clazz.isAssignableFrom(rawItem.javaClass)) {
		clazz.cast(rawItem)
	} else {
		null
	}
}

val RecyclerView.isScrolledToTop: Boolean
	get() {
		if (isEmpty()) {
			return true
		}
		val holder = findViewHolderForAdapterPosition(0)
		return holder != null && holder.itemView.top >= 0
	}

val RecyclerView.LayoutManager?.firstVisibleItemPosition
	get() = when (this) {
		is LinearLayoutManager -> findFirstVisibleItemPosition()
		is StaggeredGridLayoutManager -> findFirstVisibleItemPositions(null)[0]
		else -> 0
	}

val RecyclerView.LayoutManager?.isLayoutReversed
	get() = when (this) {
		is LinearLayoutManager -> reverseLayout
		is StaggeredGridLayoutManager -> reverseLayout
		else -> false
	}

// https://medium.com/flat-pack-tech/quickly-scroll-to-the-top-of-a-recyclerview-da15b717f3c4
fun RecyclerView.smoothScrollToTop() {
	val layoutManager = layoutManager as? LinearLayoutManager ?: return

	if (!context.isAnimationsEnabled) {
		layoutManager.scrollToPositionWithOffset(0, 0)
		return
	}

	val totalOffset = computeVerticalScrollOffset()
	if (totalOffset <= 0) {
		return
	}

	val jumpBeforeScroll = layoutManager.findFirstVisibleItemPosition() > DEFAULT_JUMP_THRESHOLD
	if (jumpBeforeScroll) {
		layoutManager.scrollToPositionWithOffset(DEFAULT_JUMP_THRESHOLD, 0)
		doOnNextLayout {
			animateScrollOffsetToTop(DEFAULT_SCROLL_DURATION_MS)
		}
	} else {
		animateScrollOffsetToTop(DEFAULT_SCROLL_DURATION_MS)
	}
}

private const val DEFAULT_JUMP_THRESHOLD = 30
private const val DEFAULT_SCROLL_DURATION_MS = 1000L

private fun RecyclerView.animateScrollOffsetToTop(durationMs: Long) {
	val startOffset = computeVerticalScrollOffset()
	if (startOffset <= 0) {
		return
	}
	var previousOffset = startOffset
	ValueAnimator.ofInt(startOffset, 0).apply {
		duration = durationMs
		addUpdateListener { animator ->
			val currentOffset = animator.animatedValue as Int
			val delta = previousOffset - currentOffset
			if (delta != 0) {
				scrollBy(0, -delta)
				previousOffset = currentOffset
			}
		}
		start()
	}
}
