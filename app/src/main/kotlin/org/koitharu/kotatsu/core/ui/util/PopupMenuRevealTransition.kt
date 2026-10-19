package org.koitharu.kotatsu.core.ui.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.graphics.RectF
import android.transition.TransitionValues
import android.transition.Visibility
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.annotation.Keep
import org.koitharu.kotatsu.R

/**
 * Unfolds the menu from its anchor corner: the width opens quickly, the height keeps easing down
 * after it, and the items fade in once there is room for them.
 */
@Keep
class PopupMenuRevealTransition(
	context: Context,
	attrs: AttributeSet,
) : Visibility(context, attrs) {

	private val cornerRadius = context.resources.getDimension(R.dimen.menu_popup_corner_radius)

	init {
		mode = MODE_IN
	}

	override fun onAppear(
		sceneRoot: ViewGroup,
		view: View,
		startValues: TransitionValues?,
		endValues: TransitionValues?,
	): Animator {
		val epicenter = epicenter
		val anchorX = (epicenter?.centerX() ?: view.width) - view.left
		val anchorY = (epicenter?.centerY() ?: 0) - view.top
		val growsFromLeft = anchorX <= view.width / 2f
		val growsFromTop = anchorY <= view.height / 2f
		val outlineProvider = RevealOutlineProvider(
			cornerRadius = cornerRadius,
			growsFromLeft = growsFromLeft,
			growsFromTop = growsFromTop,
		).apply {
			setProgress(view.width, view.height, 0f, 0f)
		}
		val originalOutlineProvider = view.outlineProvider
		val wasClippingToOutline = view.clipToOutline
		// Only the items fade; the surface stays opaque so its edge and shadow read as one shape
		val content = (view as? ViewGroup)?.getChildAt(0)

		view.outlineProvider = outlineProvider
		view.clipToOutline = true
		content?.alpha = 0f

		return ValueAnimator.ofFloat(0f, 1f).apply {
			interpolator = LinearInterpolator()
			addUpdateListener { animation ->
				val t = animation.animatedFraction
				outlineProvider.setProgress(
					width = view.width,
					height = view.height,
					widthProgress = widthInterpolator.getInterpolation((t / WIDTH_SHARE).coerceAtMost(1f)),
					heightProgress = heightInterpolator.getInterpolation(t),
				)
				content?.alpha = ((t - FADE_START) / FADE_SHARE).coerceIn(0f, 1f)
				view.invalidateOutline()
			}
			addListener(object : AnimatorListenerAdapter() {
				private var restored = false

				override fun onAnimationCancel(animation: Animator) = restoreOutline()

				override fun onAnimationEnd(animation: Animator) = restoreOutline()

				private fun restoreOutline() {
					if (restored) {
						return
					}
					restored = true
					content?.alpha = 1f
					view.outlineProvider = originalOutlineProvider
					view.clipToOutline = wasClippingToOutline
					view.invalidateOutline()
				}
			})
		}
	}

	private class RevealOutlineProvider(
		private val cornerRadius: Float,
		private val growsFromLeft: Boolean,
		private val growsFromTop: Boolean,
	) : ViewOutlineProvider() {

		private val bounds = RectF()

		override fun getOutline(view: View, outline: Outline) {
			outline.setRoundRect(
				bounds.left.toInt(),
				bounds.top.toInt(),
				bounds.right.toInt(),
				bounds.bottom.toInt(),
				cornerRadius,
			)
		}

		fun setProgress(
			width: Int,
			height: Int,
			widthProgress: Float,
			heightProgress: Float,
		) {
			val diameter = minOf(cornerRadius * 2f, width.toFloat(), height.toFloat())
			val revealedWidth = diameter + (width - diameter) * widthProgress
			val revealedHeight = diameter + (height - diameter) * heightProgress
			bounds.set(
				if (growsFromLeft) 0f else width - revealedWidth,
				if (growsFromTop) 0f else height - revealedHeight,
				if (growsFromLeft) revealedWidth else width.toFloat(),
				if (growsFromTop) revealedHeight else height.toFloat(),
			)
		}
	}

	private companion object {

		const val WIDTH_SHARE = 0.55f // of the duration
		const val FADE_START = 0.12f
		const val FADE_SHARE = 0.35f

		val widthInterpolator = PathInterpolator(0f, 0f, 0.2f, 1f) // M3 standard decelerate
		val heightInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f) // M3 emphasized decelerate
	}
}
