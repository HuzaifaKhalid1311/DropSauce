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
import androidx.annotation.Keep
import org.koitharu.kotatsu.R

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
			setProgress(view.width, view.height, 0f)
		}
		val originalOutlineProvider = view.outlineProvider
		val wasClippingToOutline = view.clipToOutline

		view.outlineProvider = outlineProvider
		view.clipToOutline = true

		return ValueAnimator.ofFloat(0f, 1f).apply {
			addUpdateListener { animation ->
				outlineProvider.setProgress(
					width = view.width,
					height = view.height,
					progress = animation.animatedValue as Float,
				)
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
			progress: Float,
		) {
			val diameter = minOf(cornerRadius * 2f, width.toFloat(), height.toFloat())
			val revealedWidth = diameter + (width - diameter) * progress
			val revealedHeight = diameter + (height - diameter) * progress
			bounds.set(
				if (growsFromLeft) 0f else width - revealedWidth,
				if (growsFromTop) 0f else height - revealedHeight,
				if (growsFromLeft) revealedWidth else width.toFloat(),
				if (growsFromTop) revealedHeight else height.toFloat(),
			)
		}
	}
}
