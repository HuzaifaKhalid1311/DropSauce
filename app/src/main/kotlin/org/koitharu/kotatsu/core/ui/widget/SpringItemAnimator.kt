package org.koitharu.kotatsu.core.ui.widget

import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * Material 3 Expressive item animator backed by SpringForce instead of the
 * default linear interpolators. Drop-in replacement for DefaultItemAnimator
 * on lists that should feel springy (manga grids, history, downloads, feed).
 */
class SpringItemAnimator(
	private val stiffness: Float = SpringForce.STIFFNESS_LOW,
	private val dampingRatio: Float = SpringForce.DAMPING_RATIO_LOW_BOUNCY,
) : DefaultItemAnimator() {

	override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
		holder.itemView.translationY = holder.itemView.height.toFloat()
		holder.itemView.alpha = 0f
		SpringAnimation(holder.itemView, DynamicAnimation.TRANSLATION_Y, 0f).apply {
			spring.stiffness = stiffness
			spring.dampingRatio = dampingRatio
		}.start()
		SpringAnimation(holder.itemView, DynamicAnimation.ALPHA, 1f).apply {
			spring.stiffness = SpringForce.STIFFNESS_LOW
			spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
		}.start()
		dispatchAddFinished(holder)
		return false
	}

	override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
		SpringAnimation(holder.itemView, DynamicAnimation.ALPHA, 0f).apply {
			spring.stiffness = SpringForce.STIFFNESS_MEDIUM
			spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
			addEndListener { _, _, _, _ -> dispatchRemoveFinished(holder) }
		}.start()
		return false
	}
}
