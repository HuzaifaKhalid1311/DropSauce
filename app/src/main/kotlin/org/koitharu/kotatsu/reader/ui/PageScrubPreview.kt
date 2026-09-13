package org.koitharu.kotatsu.reader.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewPropertyAnimator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.PopupWindow
import coil3.size.Size
import com.google.android.material.slider.Slider
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getAnimationDuration
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled
import org.koitharu.kotatsu.core.util.ext.isRtl
import org.koitharu.kotatsu.databinding.PopupReaderScrubPreviewBinding
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import kotlin.math.roundToInt

/**
 * The page thumbnail that floats beside the reader's slider while the user scrubs, so finding a
 * page stops being a guessing game. It rides on the same Coil fetcher as the thumbnails in the
 * chapters/pages sheet, which means an already-read page appears straight from cache.
 *
 * Purely decorative: the window is untouchable, so the drag never leaves the slider.
 */
class PageScrubPreview(private val slider: Slider) {

	private val context: Context = slider.context
	private val binding = PopupReaderScrubPreviewBinding.inflate(LayoutInflater.from(context))
	private val gap = context.resources.getDimensionPixelOffset(R.dimen.reader_scrub_preview_offset)
	private val popup = PopupWindow(binding.root, WRAP_CONTENT, WRAP_CONTENT).apply {
		isFocusable = false
		isTouchable = false
		isOutsideTouchable = false
		// The reader is edge-to-edge; let the preview sit over the system bar area if it has to.
		isClippingEnabled = false
		setBackgroundDrawable(null)
	}
	private var animator: ViewPropertyAnimator? = null
	private var isHiding = false
	private var loadedPageId: Long? = null
	private var pendingPage: ReaderPage? = null
	private var showsBelow = false

	private val loadRunnable = Runnable {
		val page = pendingPage ?: return@Runnable
		loadedPageId = page.id
		binding.imageViewThumb.setImageAsync(page)
	}

	init {
		val width = context.resources.getDimensionPixelSize(R.dimen.reader_scrub_preview_width)
		val height = context.resources.getDimensionPixelSize(R.dimen.reader_scrub_preview_height)
		// The popup is measured with UNSPECIFIED, so the request needs the size spelled out.
		binding.imageViewThumb.exactImageSize = Size(width, height)
		binding.root.measure(
			View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
			View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
		)
	}

	/** Shows the preview for [page], or moves the already-visible one onto it. */
	fun show(page: ReaderPage) {
		binding.textViewNumber.text = (page.index + 1).toString()
		if (page.id != loadedPageId) {
			pendingPage = page
			binding.root.removeCallbacks(loadRunnable)
			// A scrub crosses a lot of pages - only fetch the one the thumb actually settles on.
			binding.root.postDelayed(loadRunnable, LOAD_DELAY)
		}
		if (popup.isShowing) {
			popup.update(computeX(), computeY(), -1, -1)
			if (isHiding) {
				animateIn()
			}
		} else {
			if (slider.windowToken == null) {
				return
			}
			binding.root.alpha = 0f
			binding.root.scaleX = COLLAPSED_SCALE
			binding.root.scaleY = COLLAPSED_SCALE
			popup.showAtLocation(slider, Gravity.NO_GRAVITY, computeX(), computeY())
			animateIn()
		}
	}

	fun hide() {
		if (!popup.isShowing || isHiding) {
			return
		}
		binding.root.removeCallbacks(loadRunnable)
		animator?.cancel()
		if (!context.isAnimationsEnabled) {
			dismiss()
			return
		}
		isHiding = true
		animator = binding.root.animate()
			.alpha(0f)
			.scaleX(COLLAPSED_SCALE)
			.scaleY(COLLAPSED_SCALE)
			.setInterpolator(AccelerateInterpolator())
			.setDuration(context.getAnimationDuration(R.integer.config_shorterAnimTime))
			.setListener(
				object : AnimatorListenerAdapter() {
					override fun onAnimationEnd(animation: Animator) {
						if (isHiding) {
							dismiss()
						}
					}
				},
			)
	}

	/** Tears the window down immediately — call it when the host view leaves the window. */
	fun dismiss() {
		binding.root.removeCallbacks(loadRunnable)
		animator?.cancel()
		animator = null
		isHiding = false
		popup.dismiss()
	}

	private fun animateIn() {
		isHiding = false
		animator?.cancel()
		// Grow out of the slider, so the preview reads as coming from the thumb.
		binding.root.pivotX = binding.root.measuredWidth / 2f
		binding.root.pivotY = if (showsBelow) 0f else binding.root.measuredHeight.toFloat()
		if (!context.isAnimationsEnabled) {
			animator = null
			binding.root.alpha = 1f
			binding.root.scaleX = 1f
			binding.root.scaleY = 1f
			return
		}
		animator = binding.root.animate()
			.alpha(1f)
			.scaleX(1f)
			.scaleY(1f)
			.setInterpolator(DecelerateInterpolator())
			.setDuration(context.getAnimationDuration(R.integer.config_shorterAnimTime))
			.setListener(null)
	}

	private fun computeX(): Int {
		val location = IntArray(2)
		slider.getLocationOnScreen(location)
		val range = slider.valueTo - slider.valueFrom
		val fraction = if (range > 0f) (slider.value - slider.valueFrom) / range else 0f
		val thumbX = location[0] + slider.trackSidePadding +
			(if (slider.isRtl) 1f - fraction else fraction) * slider.trackWidth
		val width = binding.root.measuredWidth
		val root = slider.rootView
		val rootLocation = IntArray(2)
		root.getLocationOnScreen(rootLocation)
		val min = rootLocation[0]
		val max = rootLocation[0] + root.width - width
		return (thumbX - width / 2f).roundToInt().coerceIn(min, maxOf(min, max))
	}

	private fun computeY(): Int {
		val location = IntArray(2)
		slider.getLocationOnScreen(location)
		val root = slider.rootView
		val rootLocation = IntArray(2)
		root.getLocationOnScreen(rootLocation)
		// Tablets dock the reader bar inside the top app bar, so the preview has to drop below it.
		showsBelow = location[1] + slider.height / 2 < rootLocation[1] + root.height / 2
		return if (showsBelow) {
			location[1] + slider.height + gap
		} else {
			location[1] - binding.root.measuredHeight - gap
		}
	}

	private companion object {

		const val COLLAPSED_SCALE = 0.85f
		const val LOAD_DELAY = 90L
	}
}
